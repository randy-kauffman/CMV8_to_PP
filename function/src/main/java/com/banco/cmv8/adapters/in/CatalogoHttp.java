package com.banco.cmv8.adapters.in;

import com.banco.cmv8.adapters.out.cola.ColaIngestaSalida;
import com.banco.cmv8.application.catalogo.AnalizadorCatalogo;
import com.banco.cmv8.application.catalogo.DescubrirTipos;
import com.banco.cmv8.application.catalogo.ValidarTabla;
import com.banco.cmv8.application.catalogo.ValidarTipo;
import com.banco.cmv8.config.Fabrica;
import com.banco.cmv8.domain.ContextoLlamador;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Operaciones de administración del catálogo (ADR-003 §5): descubrir, actualizar, validar tipo, validar tabla,
 * resolver conflicto. La compuerta es el rol de administrador documental en Dataverse (lectura suplantada de
 * conflictos); ValidarTabla la usa el maker con su propio rol.
 */
public class CatalogoHttp {

    /** POST /api/catalogo/descubrir */
    @FunctionName("DescubrirTipos")
    public HttpResponseMessage descubrir(
            @HttpTrigger(name = "req", methods = {HttpMethod.POST}, authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "catalogo/descubrir") HttpRequestMessage<Optional<String>> req,
            ExecutionContext ctx) {
        return correrDescubrimiento(req, false, ctx.getLogger());
    }

    /** POST /api/catalogo/actualizar: solo los tipos en uso, conservando decisiones. */
    @FunctionName("ActualizarTipos")
    public HttpResponseMessage actualizar(
            @HttpTrigger(name = "req", methods = {HttpMethod.POST}, authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "catalogo/actualizar") HttpRequestMessage<Optional<String>> req,
            ExecutionContext ctx) {
        return correrDescubrimiento(req, true, ctx.getLogger());
    }

    private static HttpResponseMessage correrDescubrimiento(HttpRequestMessage<Optional<String>> req, boolean soloEnUso,
                                                            Logger log) {
        try {
            ContextoLlamador llamador = LectorLlamador.desde(req.getHeaders());
            DescubrirTipos.Resumen r = Fabrica.get().descubrirTipos().ejecutar(soloEnUso, llamador, log);
            Map<String, Object> cuerpo = new LinkedHashMap<>();
            cuerpo.put("nuevos", r.nuevos());
            cuerpo.put("actualizados", r.actualizados());
            cuerpo.put("sinCambios", r.sinCambios());
            cuerpo.put("ausentes", r.ausentes());
            cuerpo.put("avisos", r.avisos());
            return RespuestasHttp.json(req, HttpStatus.OK, cuerpo);
        } catch (RuntimeException e) {
            return RespuestasHttp.desdeExcepcion(req, e, log);
        }
    }

    /** GET /api/catalogo/validar?tipo={id}: sin tipo valida todos los en uso. */
    @FunctionName("ValidarTipo")
    public HttpResponseMessage validarTipo(
            @HttpTrigger(name = "req", methods = {HttpMethod.GET}, authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "catalogo/validar") HttpRequestMessage<Optional<String>> req,
            ExecutionContext ctx) {
        Logger log = ctx.getLogger();
        try {
            Optional<UUID> tipo = leerUuid(req.getQueryParameters().get("tipo"));
            ValidarTipo.Informe informe = Fabrica.get().validarTipo().ejecutar(tipo, LectorLlamador.desde(req.getHeaders()), log);
            Map<String, Object> cuerpo = new LinkedHashMap<>();
            cuerpo.put("criticos", informe.criticos());
            cuerpo.put("avisos", informe.avisos());
            cuerpo.put("hallazgos", hallazgos(informe.hallazgos()));
            return RespuestasHttp.json(req, HttpStatus.OK, cuerpo);
        } catch (RuntimeException e) {
            return RespuestasHttp.desdeExcepcion(req, e, log);
        }
    }

    /** GET /api/catalogo/validar-tabla/{tabla}?tipo={id} */
    @FunctionName("ValidarTabla")
    public HttpResponseMessage validarTabla(
            @HttpTrigger(name = "req", methods = {HttpMethod.GET}, authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "catalogo/validar-tabla/{tabla}") HttpRequestMessage<Optional<String>> req,
            @BindingName("tabla") String tabla,
            ExecutionContext ctx) {
        Logger log = ctx.getLogger();
        try {
            Optional<UUID> tipo = leerUuid(req.getQueryParameters().get("tipo"));
            ValidarTabla.Informe informe = Fabrica.get().validarTabla().ejecutar(tabla, tipo,
                    LectorLlamador.desde(req.getHeaders()), log);
            Map<String, Object> cuerpo = new LinkedHashMap<>();
            cuerpo.put("tabla", informe.tabla());
            cuerpo.put("prefijo", informe.prefijo());
            cuerpo.put("ok", informe.ok());
            cuerpo.put("faltantes", informe.faltantes());
            cuerpo.put("avisos", informe.avisos());
            return RespuestasHttp.json(req, HttpStatus.OK, cuerpo);
        } catch (RuntimeException e) {
            return RespuestasHttp.desdeExcepcion(req, e, log);
        }
    }

    /** POST /api/catalogo/conflictos/{id}/reanudar */
    @FunctionName("ReanudarConflicto")
    public HttpResponseMessage reanudarConflicto(
            @HttpTrigger(name = "req", methods = {HttpMethod.POST}, authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "catalogo/conflictos/{id}/reanudar") HttpRequestMessage<Optional<String>> req,
            @BindingName("id") String id,
            @QueueOutput(name = "salida", queueName = "%COLA_INGESTA%", connection = "AzureWebJobsStorage")
            OutputBinding<List<String>> salida,
            ExecutionContext ctx) {
        Logger log = ctx.getLogger();
        try {
            UUID conflictoId = leerUuid(id).orElse(null);
            if (conflictoId == null) {
                return RespuestasHttp.error(req, HttpStatus.BAD_REQUEST, "El id del conflicto no es un GUID");
            }
            ColaIngestaSalida cola = new ColaIngestaSalida();
            int reencolados = Fabrica.get().reanudarConflicto().ejecutar(conflictoId, LectorLlamador.desde(req.getHeaders()),
                    cola, log);
            AdjuntosHttp.entregar(cola, salida);
            return RespuestasHttp.json(req, HttpStatus.OK, Map.of("conflictoId", conflictoId, "reencolados", reencolados));
        } catch (RuntimeException e) {
            return RespuestasHttp.desdeExcepcion(req, e, log);
        }
    }

    private static Optional<UUID> leerUuid(String valor) {
        if (valor == null || valor.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(valor.trim()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static List<Map<String, Object>> hallazgos(List<AnalizadorCatalogo.Hallazgo> lista) {
        List<Map<String, Object>> salida = new ArrayList<>();
        for (AnalizadorCatalogo.Hallazgo h : lista) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nivel", h.nivel().name());
            m.put("tipo", h.tipo());
            m.put("propiedad", h.propiedad());
            m.put("mensaje", h.mensaje());
            salida.add(m);
        }
        return salida;
    }
}
