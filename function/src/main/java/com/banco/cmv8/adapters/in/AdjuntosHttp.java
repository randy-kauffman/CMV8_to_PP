package com.banco.cmv8.adapters.in;

import com.banco.cmv8.adapters.out.cola.ColaIngestaSalida;
import com.banco.cmv8.config.Fabrica;
import com.banco.cmv8.domain.ClaveAdjunto;
import com.banco.cmv8.domain.ContextoLlamador;
import com.banco.cmv8.domain.ValidacionException;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.OutputBinding;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.BindingName;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;
import com.microsoft.azure.functions.annotation.QueueOutput;

import java.io.ByteArrayInputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Operaciones sobre un adjunto (ADR-003 §5): archivar un almacenado, reanudar una observada, ingestar desde un
 * flujo y extender la retención. Todas autorizan suplantando al llamador (Easy Auth antes).
 */
public class AdjuntosHttp {

    /** POST /api/adjuntos/{tabla}/{id}/archivar */
    @FunctionName("Archivar")
    public HttpResponseMessage archivar(
            @HttpTrigger(name = "req", methods = {HttpMethod.POST}, authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "adjuntos/{tabla}/{id}/archivar") HttpRequestMessage<Optional<String>> req,
            @BindingName("tabla") String tabla, @BindingName("id") String id,
            @QueueOutput(name = "salida", queueName = "%COLA_INGESTA%", connection = "AzureWebJobsStorage")
            OutputBinding<List<String>> salida,
            ExecutionContext ctx) {
        Logger log = ctx.getLogger();
        try {
            ClaveAdjunto clave = ClaveAdjunto.de(tabla, id);
            ColaIngestaSalida cola = new ColaIngestaSalida();
            Fabrica.get().archivarAlmacenado().ejecutar(clave, LectorLlamador.desde(req.getHeaders()), cola, log);
            entregar(cola, salida);
            return RespuestasHttp.json(req, HttpStatus.ACCEPTED, Map.of("adjunto", clave.toString(), "accion", "archivar"));
        } catch (RuntimeException e) {
            return RespuestasHttp.desdeExcepcion(req, e, log);
        }
    }

    /** POST /api/adjuntos/{tabla}/{id}/reanudar */
    @FunctionName("Reanudar")
    public HttpResponseMessage reanudar(
            @HttpTrigger(name = "req", methods = {HttpMethod.POST}, authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "adjuntos/{tabla}/{id}/reanudar") HttpRequestMessage<Optional<String>> req,
            @BindingName("tabla") String tabla, @BindingName("id") String id,
            @QueueOutput(name = "salida", queueName = "%COLA_INGESTA%", connection = "AzureWebJobsStorage")
            OutputBinding<List<String>> salida,
            ExecutionContext ctx) {
        Logger log = ctx.getLogger();
        try {
            ClaveAdjunto clave = ClaveAdjunto.de(tabla, id);
            ColaIngestaSalida cola = new ColaIngestaSalida();
            Fabrica.get().reanudar().ejecutar(clave, LectorLlamador.desde(req.getHeaders()), cola, log);
            entregar(cola, salida);
            return RespuestasHttp.json(req, HttpStatus.ACCEPTED, Map.of("adjunto", clave.toString(), "accion", "reanudar"));
        } catch (RuntimeException e) {
            return RespuestasHttp.desdeExcepcion(req, e, log);
        }
    }

    /**
     * POST /api/adjuntos/{tabla}/{id}/ingestar con el archivo crudo en el cuerpo, {@code Content-Type} del archivo
     * y el nombre en {@code X-Nombre-Archivo} (URL-encoded). Para Power Automate (ADR-003 §4.7); tope de 100 MB
     * por acción de flujo.
     */
    @FunctionName("Ingestar")
    public HttpResponseMessage ingestar(
            @HttpTrigger(name = "req", methods = {HttpMethod.POST}, authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "adjuntos/{tabla}/{id}/ingestar") HttpRequestMessage<Optional<byte[]>> req,
            @BindingName("tabla") String tabla, @BindingName("id") String id,
            @QueueOutput(name = "salida", queueName = "%COLA_INGESTA%", connection = "AzureWebJobsStorage")
            OutputBinding<List<String>> salida,
            ExecutionContext ctx) {
        Logger log = ctx.getLogger();
        try {
            ClaveAdjunto clave = ClaveAdjunto.de(tabla, id);
            byte[] contenido = req.getBody().orElse(new byte[0]);
            String nombre = LectorLlamador.encabezado(req.getHeaders(), "X-Nombre-Archivo")
                    .map(v -> URLDecoder.decode(v, StandardCharsets.UTF_8))
                    .orElseThrow(() -> new ValidacionException("Falta el encabezado X-Nombre-Archivo"));
            String contentType = LectorLlamador.encabezado(req.getHeaders(), "Content-Type").orElse(null);
            ColaIngestaSalida cola = new ColaIngestaSalida();
            String ruta = Fabrica.get().ingestarArchivo().ejecutar(clave, nombre, contentType, contenido.length,
                    new ByteArrayInputStream(contenido), LectorLlamador.desde(req.getHeaders()), cola, log);
            entregar(cola, salida);
            return RespuestasHttp.json(req, HttpStatus.ACCEPTED, Map.of("adjunto", clave.toString(), "rutaBlob", ruta));
        } catch (RuntimeException e) {
            return RespuestasHttp.desdeExcepcion(req, e, log);
        }
    }

    /** POST /api/adjuntos/{tabla}/{id}/retencion {"fecha": "2030-12-31"} o {"fecha": null} para congelar. */
    @FunctionName("ExtenderRetencion")
    public HttpResponseMessage retencion(
            @HttpTrigger(name = "req", methods = {HttpMethod.POST}, authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "adjuntos/{tabla}/{id}/retencion") HttpRequestMessage<Optional<String>> req,
            @BindingName("tabla") String tabla, @BindingName("id") String id,
            ExecutionContext ctx) {
        Logger log = ctx.getLogger();
        try {
            ClaveAdjunto clave = ClaveAdjunto.de(tabla, id);
            Map<?, ?> cuerpo = SasHttp.leer(req.getBody().orElse("{}"), Map.class);
            if (cuerpo == null) {
                return RespuestasHttp.error(req, HttpStatus.BAD_REQUEST, "Cuerpo JSON inválido");
            }
            Optional<LocalDate> fecha;
            Object valor = cuerpo.get("fecha");
            try {
                fecha = valor == null || String.valueOf(valor).isBlank() ? Optional.empty()
                        : Optional.of(LocalDate.parse(String.valueOf(valor)));
            } catch (DateTimeParseException e) {
                return RespuestasHttp.error(req, HttpStatus.BAD_REQUEST, "La fecha debe ser yyyy-MM-dd");
            }
            Fabrica.get().extenderRetencion().ejecutar(clave, fecha, LectorLlamador.desde(req.getHeaders()), log);
            return RespuestasHttp.json(req, HttpStatus.OK, Map.of("adjunto", clave.toString(),
                    "retencionHasta", fecha.map(LocalDate::toString).orElse("permanente")));
        } catch (RuntimeException e) {
            return RespuestasHttp.desdeExcepcion(req, e, log);
        }
    }

    static void entregar(ColaIngestaSalida cola, OutputBinding<List<String>> salida) {
        if (!cola.vacia()) {
            salida.setValue(cola.mensajes());
        }
    }
}
