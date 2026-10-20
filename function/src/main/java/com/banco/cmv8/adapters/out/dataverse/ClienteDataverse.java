package com.banco.cmv8.adapters.out.dataverse;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Cliente OData v4 mínimo para la Web API de Dataverse con java.net.http y la identidad administrada
 * (application user en Dataverse). Un solo HttpClient y una sola credencial por proceso.
 */
public class ClienteDataverse {

    public record Respuesta(int estado, String cuerpo) {

        public boolean exitosa() {
            return estado >= 200 && estado < 300;
        }
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Duration MARGEN_TOKEN = Duration.ofMinutes(5);
    private static final int LARGO_MAXIMO_CUERPO_ERROR = 1000;

    private final HttpClient http;
    private final TokenCredential credencial;
    private final TokenRequestContext alcance;
    private final String baseApi;
    private volatile AccessToken token;

    public ClienteDataverse(TokenCredential credencial, String dataverseUrl) {
        this.credencial = credencial;
        String url = dataverseUrl.endsWith("/") ? dataverseUrl.substring(0, dataverseUrl.length() - 1) : dataverseUrl;
        this.alcance = new TokenRequestContext().addScopes(url + "/.default");
        this.baseApi = url + "/api/data/" + NombresDataverse.VERSION_API + "/";
        this.http = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /** Codifica un valor para la query string (espacios como %20, no '+'). */
    public static String codificar(String valor) {
        return URLEncoder.encode(valor, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public Respuesta get(String rutaRelativa, Map<String, String> encabezados) {
        return enviar("GET", rutaRelativa, null, encabezados);
    }

    /** PATCH con If-Match: * para no crear la fila por upsert si no existe. */
    public Respuesta patch(String rutaRelativa, String cuerpoJson) {
        return enviar("PATCH", rutaRelativa, cuerpoJson, Map.of("If-Match", "*"));
    }

    /**
     * POST con {@code Prefer: return=representation}: Dataverse devuelve 201 con la fila creada (acotada por el
     * $select de la ruta), así el id se lee del cuerpo sin una segunda request.
     */
    public Respuesta crear(String rutaRelativa, String cuerpoJson) {
        return enviar("POST", rutaRelativa, cuerpoJson, Map.of("Prefer", "return=representation"));
    }

    public Respuesta enviar(String metodo, String rutaRelativa, String cuerpoJson, Map<String, String> encabezados) {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(baseApi + rutaRelativa))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + tokenVigente())
                .header("OData-MaxVersion", "4.0")
                .header("OData-Version", "4.0")
                .header("Accept", "application/json");
        if (cuerpoJson != null) {
            req.header("Content-Type", "application/json; charset=utf-8");
            req.method(metodo, HttpRequest.BodyPublishers.ofString(cuerpoJson, StandardCharsets.UTF_8));
        } else {
            req.method(metodo, HttpRequest.BodyPublishers.noBody());
        }
        if (encabezados != null) {
            encabezados.forEach(req::header);
        }
        try {
            HttpResponse<String> r = http.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Respuesta(r.statusCode(), r.body());
        } catch (IOException e) {
            throw new DataverseException("Error de red al llamar a Dataverse (" + metodo + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataverseException("Llamada a Dataverse interrumpida", e);
        }
    }

    /** Lanza DataverseException con el detalle (recortado) si la respuesta no es 2xx. */
    public static Respuesta exigirExito(Respuesta r, String operacion) {
        if (!r.exitosa()) {
            String cuerpo = r.cuerpo() == null ? "" : r.cuerpo();
            if (cuerpo.length() > LARGO_MAXIMO_CUERPO_ERROR) {
                cuerpo = cuerpo.substring(0, LARGO_MAXIMO_CUERPO_ERROR);
            }
            throw new DataverseException(operacion + " devolvió HTTP " + r.estado() + ": " + cuerpo, r.estado());
        }
        return r;
    }

    /** azure-identity ya cachea, pero evitamos incluso la llamada mientras el token tenga margen. */
    private String tokenVigente() {
        AccessToken actual = token;
        if (actual == null || actual.getExpiresAt().isBefore(OffsetDateTime.now().plus(MARGEN_TOKEN))) {
            synchronized (this) {
                actual = token;
                if (actual == null || actual.getExpiresAt().isBefore(OffsetDateTime.now().plus(MARGEN_TOKEN))) {
                    actual = credencial.getTokenSync(alcance);
                    token = actual;
                }
            }
        }
        return actual.getToken();
    }
}
