package com.banco.cmv8.adapters.in;

import com.banco.cmv8.application.AccesoDenegadoException;
import com.banco.cmv8.application.EstadoInvalidoException;
import com.banco.cmv8.application.IntercambioTokenException;
import com.banco.cmv8.application.NoAutenticadoException;
import com.banco.cmv8.application.SolicitudNoEncontradaException;
import com.banco.cmv8.config.Json;
import com.banco.cmv8.domain.ValidacionException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;

import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Respuestas JSON y traducción de excepciones a códigos HTTP. */
final class RespuestasHttp {

    private RespuestasHttp() {
    }

    static HttpResponseMessage json(HttpRequestMessage<?> req, HttpStatus estado, Object cuerpo) {
        String texto;
        try {
            texto = Json.MAPPER.writeValueAsString(cuerpo);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar la respuesta", e);
        }
        return req.createResponseBuilder(estado)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Cache-Control", "no-store")
                .body(texto)
                .build();
    }

    static HttpResponseMessage error(HttpRequestMessage<?> req, HttpStatus estado, String mensaje) {
        return json(req, estado, Map.of("error", mensaje));
    }

    /** 401 con {@code WWW-Authenticate: Bearer error="interaction_required", claims="..."}. */
    static HttpResponseMessage desafioClaims(HttpRequestMessage<?> req, IntercambioTokenException ite) {
        String cuerpo;
        try {
            cuerpo = Json.MAPPER.writeValueAsString(Map.of(
                    "error", "El acceso a Storage requiere que el usuario vuelva a autenticarse (acceso condicional)",
                    "codigoEntra", ite.codigoEntra(),
                    "claims", ite.claims().orElse("")));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar la respuesta", e);
        }
        StringBuilder desafio = new StringBuilder("Bearer error=\"").append(IntercambioTokenException.INTERACCION_REQUERIDA)
                .append("\"");
        ite.claims().ifPresent(c -> desafio.append(", claims=\"").append(c.replace("\"", "\\\"")).append("\""));
        return req.createResponseBuilder(HttpStatus.UNAUTHORIZED)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Cache-Control", "no-store")
                .header("WWW-Authenticate", desafio.toString())
                .body(cuerpo)
                .build();
    }

    /** Traduce excepciones conocidas; lo demás es 500 con un id de correlación (sin detalles internos). */
    static HttpResponseMessage desdeExcepcion(HttpRequestMessage<?> req, RuntimeException e, Logger log) {
        if (e instanceof ValidacionException) {
            return error(req, HttpStatus.BAD_REQUEST, e.getMessage());
        }
        if (e instanceof NoAutenticadoException) {
            return error(req, HttpStatus.UNAUTHORIZED, e.getMessage());
        }
        if (e instanceof IntercambioTokenException ite) {
            // Solo el código de Entra (nunca el token).
            log.warning(() -> "OBO hacia Storage fallido. codigoEntra=" + ite.codigoEntra()
                    + " conClaims=" + ite.claims().isPresent());
            if (ite.requiereInteraccion()) {
                // Acceso condicional sobre Storage que el token del llamador no satisface: el estándar (RFC 6750 §3.1)
                // es 401 con el desafío de claims para que el cliente vuelva a autenticarse cumpliéndolo.
                return desafioClaims(req, ite);
            }
            // 502: falla una dependencia (Entra o la credencial del broker), no el llamador.
            return error(req, HttpStatus.BAD_GATEWAY, e.getMessage() + " (" + ite.codigoEntra() + ")");
        }
        if (e instanceof AccesoDenegadoException) {
            return error(req, HttpStatus.FORBIDDEN, e.getMessage());
        }
        if (e instanceof SolicitudNoEncontradaException) {
            return error(req, HttpStatus.NOT_FOUND, e.getMessage());
        }
        if (e instanceof EstadoInvalidoException) {
            return error(req, HttpStatus.CONFLICT, e.getMessage());
        }
        String incidente = UUID.randomUUID().toString();
        log.log(Level.SEVERE, "Error no controlado. incidente=" + incidente + " causa=" + causaRaiz(e), e);
        return error(req, HttpStatus.INTERNAL_SERVER_ERROR, "Error interno. Incidente " + incidente);
    }

    private static String causaRaiz(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return e.getClass().getName() + ": " + e.getMessage() + " | raiz=" + t.getClass().getName() + ": " + t.getMessage();
    }
}
