package com.banco.cmv8.adapters.in;

import com.banco.cmv8.domain.ContextoLlamador;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EntradasHttpTest {

    private static String b64url(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rutaRelativaDesdeUrlDelBlob() {
        assertEquals(Optional.of("abc/mi archivo.pdf"), UbicacionBlob.rutaRelativa(
                "https://cuenta.blob.core.windows.net/cmv8-ingesta/abc/mi%20archivo.pdf", "cmv8-ingesta"));
        assertEquals(Optional.empty(), UbicacionBlob.rutaRelativa(
                "https://cuenta.blob.core.windows.net/otro/abc/a.pdf", "cmv8-ingesta"));
        assertEquals(Optional.empty(), UbicacionBlob.rutaRelativa(null, "cmv8-ingesta"));
    }

    @Test
    void leeOidYTidDelBearerSinValidar() {
        String jwt = b64url("{\"alg\":\"none\"}") + "." + b64url("{\"oid\":\"o-1\",\"tid\":\"t-1\"}") + ".firma";
        ContextoLlamador c = LectorLlamador.desde(Map.of(
                "authorization", "Bearer " + jwt,
                "x-forwarded-for", "203.0.113.7:51234, 10.0.0.1"));

        assertEquals(Optional.of("o-1"), c.objectId());
        assertEquals(Optional.of("t-1"), c.tenantId());
        assertEquals(Optional.of("203.0.113.7"), c.ip());
    }

    @Test
    void prefiereEncabezadosDeAppService() {
        String principal = Base64.getEncoder().encodeToString(
                ("{\"claims\":[{\"typ\":\"http://schemas.microsoft.com/identity/claims/tenantid\",\"val\":\"t-2\"}]}")
                        .getBytes(StandardCharsets.UTF_8));
        String jwt = b64url("{}") + "." + b64url("{\"oid\":\"o-jwt\",\"tid\":\"t-jwt\"}") + ".x";
        ContextoLlamador c = LectorLlamador.desde(Map.of(
                "Authorization", "Bearer " + jwt,
                "X-MS-CLIENT-PRINCIPAL-ID", "o-2",
                "X-MS-CLIENT-PRINCIPAL", principal));

        assertEquals(Optional.of("o-2"), c.objectId());
        assertEquals(Optional.of("t-2"), c.tenantId());
    }

    @Test
    void sinAuthorizationUsaElTokenDelTokenStoreDeEasyAuth() {
        String jwt = b64url("{}") + "." + b64url("{\"oid\":\"o-3\",\"tid\":\"t-3\"}") + ".x";
        ContextoLlamador c = LectorLlamador.desde(Map.of("x-ms-token-aad-access-token", jwt));

        assertEquals(Optional.of(jwt), c.bearerToken());
        assertEquals(Optional.of("o-3"), c.objectId());
    }

    @Test
    void ipv6EntreCorchetes() {
        assertEquals("2001:db8::1", LectorLlamador.primeraIp("[2001:db8::1]:443"));
        assertEquals("2001:db8::1", LectorLlamador.primeraIp("2001:db8::1"));
    }
}
