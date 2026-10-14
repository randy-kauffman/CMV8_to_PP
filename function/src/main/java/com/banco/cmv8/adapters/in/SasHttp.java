package com.banco.cmv8.adapters.in;

import com.banco.cmv8.application.EmitirSasLectura;
import com.banco.cmv8.application.EmitirSasSubida;
import com.banco.cmv8.config.Fabrica;
import com.banco.cmv8.config.Json;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Broker de SAS. ANONYMOUS a nivel de Functions: la autenticación Entra la impone la autenticación
 * integrada de App Service (S7). Nunca se loguea la URL con SAS, el bearer ni el tokenStorage (S1b).
 */
public class SasHttp {

    @FunctionName("SasSubida")
    public HttpResponseMessage subida(
            @HttpTrigger(name = "req", methods = {HttpMethod.POST}, authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "sas/subida") HttpRequestMessage<Optional<String>> req,
            ExecutionContext ctx) {
        Logger log = ctx.getLogger();
        try {
            EmitirSasSubida.Peticion peticion = leerPeticion(req.getBody().orElse(""));
            if (peticion == null) {
                return RespuestasHttp.error(req, HttpStatus.BAD_REQUEST, "Cuerpo JSON inválido");
            }
            ContextoLlamador llamador = LectorLlamador.desde(req.getHeaders());
            EmitirSasSubida.Resultado r = Fabrica.get().emitirSasSubida().ejecutar(peticion, llamador, log);

            Map<String, Object> cuerpo = new LinkedHashMap<>();
            cuerpo.put("uploadUrl", r.uploadUrl());
            cuerpo.put("rutaBlob", r.rutaBlob());
            cuerpo.put("expiraUtc", iso(r.expiraUtc()));
            cuerpo.put("bloqueBytes", r.bloqueBytes());
            cuerpo.put("versionServicio", r.versionServicio());
            // S1b: solo con SAS ligada al usuario. El PCF lo manda como Authorization: Bearer a Storage.
            if (r.tokenStorage() != null) {
                cuerpo.put("tokenStorage", r.tokenStorage());
                cuerpo.put("tokenStorageExpiraUtc", iso(r.tokenStorageExpiraUtc()));
            }
            return RespuestasHttp.json(req, HttpStatus.OK, cuerpo);
        } catch (RuntimeException e) {
            return RespuestasHttp.desdeExcepcion(req, e, log);
        }
    }

    @FunctionName("SasLectura")
    public HttpResponseMessage lectura(
            @HttpTrigger(name = "req", methods = {HttpMethod.GET}, authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "sas/lectura/{solicitudId}") HttpRequestMessage<Optional<String>> req,
            @BindingName("solicitudId") String solicitudId,
            ExecutionContext ctx) {
        Logger log = ctx.getLogger();
        try {
            UUID id;
            try {
                id = UUID.fromString(solicitudId);
            } catch (IllegalArgumentException e) {
                return RespuestasHttp.error(req, HttpStatus.BAD_REQUEST, "solicitudId no es un GUID");
            }
            ContextoLlamador llamador = LectorLlamador.desde(req.getHeaders());
            EmitirSasLectura.Resultado r = Fabrica.get().emitirSasLectura().ejecutar(id, llamador, log);

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

    private static EmitirSasSubida.Peticion leerPeticion(String cuerpo) {
        if (cuerpo == null || cuerpo.isBlank()) {
            return null;
        }
        try {
            return Json.MAPPER.readValue(cuerpo, EmitirSasSubida.Peticion.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String iso(OffsetDateTime fecha) {
        return DateTimeFormatter.ISO_INSTANT.format(fecha.toInstant());
    }
}
