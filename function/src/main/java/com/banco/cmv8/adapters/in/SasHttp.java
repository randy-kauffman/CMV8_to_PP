package com.banco.cmv8.adapters.in;

import com.banco.cmv8.application.EmitirSasLectura;
import com.banco.cmv8.application.EmitirSasLote;
import com.banco.cmv8.config.Fabrica;
import com.banco.cmv8.config.Json;
import com.banco.cmv8.domain.ClaveAdjunto;
import com.banco.cmv8.domain.ContextoLlamador;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.BindingName;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Broker de SAS v2 (ADR-003 §4.1 y §5). ANONYMOUS a nivel de Functions: la autenticación Entra la impone Easy
 * Auth. Nunca se loguea la URL con SAS, el bearer ni el tokenStorage.
 */
public class SasHttp {

    /** POST /api/sas/lote {"tabla": "...", "items": [{"id", "nombreArchivo", "tamanoBytes", "contentType"}]} */
    @FunctionName("SasLote")
    public HttpResponseMessage lote(
            @HttpTrigger(name = "req", methods = {HttpMethod.POST}, authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "sas/lote") HttpRequestMessage<Optional<String>> req,
            ExecutionContext ctx) {
        Logger log = ctx.getLogger();
        try {
            EmitirSasLote.Peticion peticion = leer(req.getBody().orElse(""), EmitirSasLote.Peticion.class);
            if (peticion == null) {
                return RespuestasHttp.error(req, HttpStatus.BAD_REQUEST, "Cuerpo JSON inválido");
            }
            ContextoLlamador llamador = LectorLlamador.desde(req.getHeaders());
            EmitirSasLote.Resultado r = Fabrica.get().emitirSasLote().ejecutar(peticion, llamador, log);
            List<Map<String, Object>> items = new ArrayList<>();
            for (EmitirSasLote.ItemResultado i : r.items()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", i.id());
                if (i.exitoso()) {
                    item.put("uploadUrl", i.uploadUrl());
                    item.put("rutaBlob", i.rutaBlob());
                    item.put("expiraUtc", iso(i.expiraUtc()));
                } else {
                    item.put("error", i.error());
                    item.put("codigo", i.codigoError());
                }
                items.add(item);
            }
            Map<String, Object> cuerpo = new LinkedHashMap<>();
            cuerpo.put("items", items);
            cuerpo.put("bloqueBytes", r.bloqueBytes());
            cuerpo.put("versionServicio", r.versionServicio());
            // Un solo token: es del usuario, no del archivo. El PCF lo manda como Authorization: Bearer a Storage.
            if (r.tokenStorage() != null) {
                cuerpo.put("tokenStorage", r.tokenStorage());
                cuerpo.put("tokenStorageExpiraUtc", iso(r.tokenStorageExpiraUtc()));
            }
            return RespuestasHttp.json(req, HttpStatus.OK, cuerpo);
        } catch (RuntimeException e) {
            return RespuestasHttp.desdeExcepcion(req, e, log);
        }
    }

    /** GET /api/sas/lectura/{tabla}/{id} */
    @FunctionName("SasLectura")
    public HttpResponseMessage lectura(
            @HttpTrigger(name = "req", methods = {HttpMethod.GET}, authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "sas/lectura/{tabla}/{id}") HttpRequestMessage<Optional<String>> req,
            @BindingName("tabla") String tabla,
            @BindingName("id") String id,
            ExecutionContext ctx) {
        Logger log = ctx.getLogger();
        try {
            ClaveAdjunto clave = ClaveAdjunto.de(tabla, id);
            ContextoLlamador llamador = LectorLlamador.desde(req.getHeaders());
            EmitirSasLectura.Resultado r = Fabrica.get().emitirSasLectura().ejecutar(clave, llamador, log);
            Map<String, Object> cuerpo = new LinkedHashMap<>();
            cuerpo.put("downloadUrl", r.downloadUrl());
            cuerpo.put("expiraUtc", iso(r.expiraUtc()));
            if (r.tokenStorage() != null) {
                cuerpo.put("tokenStorage", r.tokenStorage());
                cuerpo.put("tokenStorageExpiraUtc", iso(r.tokenStorageExpiraUtc()));
            }
            return RespuestasHttp.json(req, HttpStatus.OK, cuerpo);
        } catch (RuntimeException e) {
            return RespuestasHttp.desdeExcepcion(req, e, log);
        }
    }

    static <T> T leer(String cuerpo, Class<T> tipo) {
        if (cuerpo == null || cuerpo.isBlank()) {
            return null;
        }
        try {
            return Json.MAPPER.readValue(cuerpo, tipo);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    static String iso(OffsetDateTime fecha) {
        return fecha == null ? null : DateTimeFormatter.ISO_INSTANT.format(fecha.toInstant());
    }
}
