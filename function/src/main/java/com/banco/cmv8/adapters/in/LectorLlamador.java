package com.banco.cmv8.adapters.in;

import com.banco.cmv8.config.Json;
import com.banco.cmv8.domain.ContextoLlamador;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * Arma el ContextoLlamador desde los encabezados HTTP.
 *
 * <p>Prioridad: encabezados de la autenticación integrada de App Service (X-MS-CLIENT-PRINCIPAL-ID,
 * X-MS-CLIENT-PRINCIPAL), que el front-end de la plataforma inyecta tras validar el token; luego los
 * claims oid/tid del bearer SIN validar la firma (solo para reenviarlos; la validación la hace
 * App Service en S7). Hasta S7, el oid es declarativo: cualquiera podría fabricar un token.
 */
public final class LectorLlamador {

    private static final String CLAIM_OID_LARGO = "http://schemas.microsoft.com/identity/claims/objectidentifier";
    private static final String CLAIM_TID_LARGO = "http://schemas.microsoft.com/identity/claims/tenantid";

    private LectorLlamador() {
    }

    public static ContextoLlamador desde(Map<String, String> encabezados) {
        Optional<String> bearer = encabezado(encabezados, "Authorization")
                .filter(v -> v.regionMatches(true, 0, "Bearer ", 0, 7))
                .map(v -> v.substring(7).trim())
                .filter(v -> !v.isEmpty());
        Optional<JsonNode> claimsJwt = bearer.flatMap(LectorLlamador::payloadJwt);
        Optional<JsonNode> principal = encabezado(encabezados, "X-MS-CLIENT-PRINCIPAL")
                .flatMap(LectorLlamador::decodificarPrincipal);

        Optional<String> oid = encabezado(encabezados, "X-MS-CLIENT-PRINCIPAL-ID")
                .or(() -> principal.flatMap(p -> claimPrincipal(p, CLAIM_OID_LARGO, "oid")))
                .or(() -> claimsJwt.flatMap(c -> texto(c, "oid")));
        Optional<String> tid = principal.flatMap(p -> claimPrincipal(p, CLAIM_TID_LARGO, "tid"))
                .or(() -> claimsJwt.flatMap(c -> texto(c, "tid")));
        Optional<String> ip = encabezado(encabezados, "X-Forwarded-For").map(LectorLlamador::primeraIp);

        return new ContextoLlamador(oid, tid, bearer, ip);
    }

    /** Búsqueda sin distinguir mayúsculas (el worker de Java puede entregar los nombres en minúsculas). */
    static Optional<String> encabezado(Map<String, String> encabezados, String nombre) {
        if (encabezados == null) {
            return Optional.empty();
        }
        for (Map.Entry<String, String> e : encabezados.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(nombre)
                    && e.getValue() != null && !e.getValue().isBlank()) {
                return Optional.of(e.getValue().trim());
            }
        }
        return Optional.empty();
    }

    static Optional<JsonNode> payloadJwt(String jwt) {
        String[] partes = jwt.split("\\.");
        if (partes.length < 2) {
            return Optional.empty();
        }
        try {
            byte[] json = Base64.getUrlDecoder().decode(partes[1]);
            return Optional.of(Json.MAPPER.readTree(new String(json, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Optional<JsonNode> decodificarPrincipal(String base64) {
        try {
            byte[] json = Base64.getDecoder().decode(base64);
            return Optional.of(Json.MAPPER.readTree(new String(json, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** X-MS-CLIENT-PRINCIPAL: {"claims":[{"typ":"...","val":"..."}]}. */
    private static Optional<String> claimPrincipal(JsonNode principal, String tipoLargo, String tipoCorto) {
        for (JsonNode c : principal.path("claims")) {
            String typ = c.path("typ").asText("");
            if (typ.equals(tipoLargo) || typ.equals(tipoCorto)) {
                String val = c.path("val").asText("");
                if (!val.isBlank()) {
                    return Optional.of(val);
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> texto(JsonNode nodo, String campo) {
        JsonNode v = nodo.get(campo);
        return v == null || v.isNull() || v.asText().isBlank() ? Optional.empty() : Optional.of(v.asText());
    }

    /** Primera IP de X-Forwarded-For, sin puerto (Azure agrega ":puerto" a IPv4). */
    static String primeraIp(String xff) {
        String primera = xff.split(",")[0].trim();
        if (primera.startsWith("[")) {
            int cierre = primera.indexOf(']');
            return cierre > 0 ? primera.substring(1, cierre) : primera;
        }
        if (primera.chars().filter(c -> c == ':').count() == 1) {
            return primera.substring(0, primera.indexOf(':'));
        }
        return primera;
    }
}
