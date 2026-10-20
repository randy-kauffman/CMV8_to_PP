package com.banco.cmv8.adapters.in;

import com.banco.cmv8.adapters.out.cola.ColaIngestaSalida;
import com.banco.cmv8.application.RegistrarResultadoEscaneo;
import com.banco.cmv8.config.Fabrica;
import com.banco.cmv8.config.Json;
import com.banco.cmv8.domain.ClaveAdjunto;
import com.banco.cmv8.domain.ResultadoEscaneo;
import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.OutputBinding;
import com.microsoft.azure.functions.annotation.EventGridTrigger;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.QueueOutput;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Entradas de Event Grid. Los nombres de función coinciden con las suscripciones de infra/main.bicep
 * (functions/OnScanResult y functions/OnBlobCreated). Si el caso de uso falla, la excepción se propaga
 * y Event Grid reintenta según su política. La ruta es {@code <tabla>/<id>/<archivo>} (ADR-003 §1.1).
 */
public class EventosEventGrid {

    static final String TIPO_ESCANEO = "Microsoft.Security.MalwareScanningResult";
    static final String TIPO_BLOB_CREADO = "Microsoft.Storage.BlobCreated";

    /** Resultado de Defender for Storage (tópico personalizado). */
    @FunctionName("OnScanResult")
    public void onScanResult(
            @EventGridTrigger(name = "evento") String evento,
            @QueueOutput(name = "salida", queueName = "%COLA_INGESTA%", connection = "AzureWebJobsStorage")
            OutputBinding<List<String>> salida,
            ExecutionContext ctx) {
        Logger log = ctx.getLogger();
        JsonNode e = leer(evento);
        if (e == null || !TIPO_ESCANEO.equals(e.path("eventType").asText())) {
            log.warning(() -> "OnScanResult: evento ignorado (tipo inesperado o JSON inválido)");
            return;
        }
        JsonNode data = e.path("data");
        String resultadoTexto = data.path("scanResultType").asText(null);
        List<String> malware = new ArrayList<>();
        data.path("scanResultDetails").path("malwareNamesFound").forEach(n -> malware.add(n.asText()));
        procesar(data.path("blobUri").asText(null), ResultadoEscaneo.desdeTexto(resultadoTexto),
                malware.isEmpty() ? resultadoTexto : String.join(", ", malware), "escaneo", salida, log);
    }

    /** Sin Defender: BlobCreated del tópico de sistema. Se trata como limpio (no hay escaneo). */
    @FunctionName("OnBlobCreated")
    public void onBlobCreated(
            @EventGridTrigger(name = "evento") String evento,
            @QueueOutput(name = "salida", queueName = "%COLA_INGESTA%", connection = "AzureWebJobsStorage")
            OutputBinding<List<String>> salida,
            ExecutionContext ctx) {
        Logger log = ctx.getLogger();
        JsonNode e = leer(evento);
        if (e == null || !TIPO_BLOB_CREADO.equals(e.path("eventType").asText())) {
            log.warning(() -> "OnBlobCreated: evento ignorado (tipo inesperado o JSON inválido)");
            return;
        }
        procesar(e.path("data").path("url").asText(null), ResultadoEscaneo.LIMPIO, null, "blob-creado", salida, log);
    }

    private static void procesar(String urlBlob, ResultadoEscaneo resultado, String detalle, String origen,
                                 OutputBinding<List<String>> salida, Logger log) {
        Fabrica fabrica = Fabrica.get();
        String contenedor = fabrica.configuracion().contenedor();
        Optional<String> ruta = UbicacionBlob.rutaRelativa(urlBlob, contenedor);
        Optional<ClaveAdjunto> clave = ruta.flatMap(ClaveAdjunto::deRuta);
        if (ruta.isEmpty() || clave.isEmpty()) {
            log.warning(() -> "Evento de un blob fuera del contenedor de ingesta o del esquema <tabla>/<id>/<archivo>; se ignora");
            return;
        }
        ColaIngestaSalida cola = new ColaIngestaSalida();
        fabrica.registrarResultadoEscaneo().ejecutar(
                new RegistrarResultadoEscaneo.EventoEscaneo(clave.get(), ruta.get(), resultado, detalle, origen), cola, log);
        AdjuntosHttp.entregar(cola, salida);
    }

    private static JsonNode leer(String json) {
        try {
            return json == null ? null : Json.MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
