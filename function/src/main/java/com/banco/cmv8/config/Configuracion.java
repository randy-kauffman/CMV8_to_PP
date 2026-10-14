package com.banco.cmv8.config;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Lectura centralizada de app settings (variables de entorno) con valores por defecto y validación.
 * Las obligatorias se validan al usarlas, para que un endpoint que no las necesita no falle.
 * Nunca incluir valores de secretos en los mensajes de error.
 */
public final class Configuracion {

    public enum ModoAutorizacion { DATAVERSE, NINGUNA }

    private final Map<String, String> entorno;

    public Configuracion(Map<String, String> entorno) {
        this.entorno = Map.copyOf(entorno);
    }

    public static Configuracion desdeEntorno() {
        return new Configuracion(System.getenv());
    }

    // ---------------------------------------------------------------- genéricos

    public Optional<String> opcional(String nombre) {
        String valor = entorno.get(nombre);
        return valor == null || valor.isBlank() ? Optional.empty() : Optional.of(valor.trim());
    }

    public String requerido(String nombre) {
        return opcional(nombre).orElseThrow(() ->
                new IllegalStateException("Falta la configuración obligatoria " + nombre));
    }

    private String conDefecto(String nombre, String defecto) {
        return opcional(nombre).orElse(defecto);
    }

    private long entero(String nombre, long defecto) {
        Optional<String> valor = opcional(nombre);
        if (valor.isEmpty()) {
            return defecto;
        }
        try {
            return Long.parseLong(valor.get());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("La configuración " + nombre + " debe ser un número entero");
        }
    }

    private boolean booleano(String nombre, boolean defecto) {
        return opcional(nombre).map(v -> v.equalsIgnoreCase("true") || v.equals("1")).orElse(defecto);
    }

    private Duration minutos(String nombre, long defecto) {
        long m = entero(nombre, defecto);
        if (m <= 0) {
            throw new IllegalStateException("La configuración " + nombre + " debe ser mayor que cero");
        }
        return Duration.ofMinutes(m);
    }

    private static String sinBarraFinal(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    // ---------------------------------------------------------------- identidad

    /** Client id de la identidad administrada asignada por el usuario (vacío en local). */
    public Optional<String> azureClientId() {
        return opcional("AZURE_CLIENT_ID");
    }

    // ---------------------------------------------------------------- storage de adjuntos

    /** Termina en '/'. */
    public String blobEndpoint() {
        String url = requerido("ADJUNTOS_BLOB_ENDPOINT");
        return url.endsWith("/") ? url : url + "/";
    }

    public String contenedor() {
        return conDefecto("ADJUNTOS_CONTAINER", "cmv8-ingesta");
    }

    public Duration vigenciaSasSubida() {
        return minutos("SAS_UPLOAD_MINUTOS", 15);
    }

    public Duration vigenciaSasLectura() {
        return minutos("SAS_READ_MINUTOS", 5);
    }

    public boolean sasLigadaUsuario() {
        return booleano("SAS_LIGADA_USUARIO", false);
    }

    public long tamanoMaximoBytes() {
        return entero("TAMANO_MAXIMO_BYTES", 1L << 30);
    }

    /** Tamaño de bloque que se recomienda al PCF, en MiB (1 a 100). Permite experimentar sin republicar el PCF. */
    public int bloqueBytes() {
        long mb = entero("SAS_BLOQUE_MB", 4);
        if (mb < 1 || mb > 100) {
            throw new IllegalStateException("SAS_BLOQUE_MB debe estar entre 1 y 100, vino " + mb);
        }
        return (int) (mb * 1024 * 1024);
    }

    // ---------------------------------------------------------------- dataverse

    /** Sin '/' final, por ejemplo https://org.crm.dynamics.com */
    public String dataverseUrl() {
        return sinBarraFinal(requerido("DATAVERSE_URL"));
    }

    public ModoAutorizacion modoAutorizacion() {
        String modo = conDefecto("AUTORIZACION_MODO", "dataverse").toLowerCase(Locale.ROOT);
        return switch (modo) {
            case "dataverse" -> ModoAutorizacion.DATAVERSE;
            case "ninguna" -> ModoAutorizacion.NINGUNA;
            default -> throw new IllegalStateException("AUTORIZACION_MODO debe ser 'dataverse' o 'ninguna'");
        };
    }

    // ---------------------------------------------------------------- cola y watchdog

    public String colaIngesta() {
        return conDefecto("COLA_INGESTA", "ingesta-pendiente");
    }

    public Duration antiguedadEstancada() {
        return minutos("ESTANCADA_MINUTOS", 15);
    }

    /** true: el worker exige la etiqueta de Defender "No threats found" antes de archivar. */
    public boolean escaneoRequerido() {
        return booleano("ESCANEO_REQUERIDO", false);
    }

    // ---------------------------------------------------------------- CMIS

    public String cmisUrl() {
        return requerido("CMIS_URL");
    }

    public String cmisUsuario() {
        return requerido("CMIS_USER");
    }

    public String cmisClave() {
        return requerido("CMIS_PASSWORD");
    }

    public Optional<String> cmisRepositorio() {
        return opcional("CMIS_REPOSITORY_ID");
    }

    public String cmisCarpeta() {
        return requerido("CMIS_FOLDER_PATH");
    }

    public String cmisTipo() {
        return conDefecto("CMIS_TYPE_ID", "cmis:document");
    }

    public String cmisPropiedadesJson() {
        return conDefecto("CMIS_PROPIEDADES_JSON", "{}");
    }

    // ---------------------------------------------------------------- AS400

    public String as400Host() {
        return requerido("AS400_HOST");
    }

    public String as400Usuario() {
        return requerido("AS400_USER");
    }

    public String as400Clave() {
        return requerido("AS400_PASSWORD");
    }

    public boolean as400Seguro() {
        return booleano("AS400_SECURE", true);
    }

    public String as400SqlCliente() {
        String sql = requerido("AS400_SQL_CLIENTE");
        long marcadores = sql.chars().filter(c -> c == '?').count();
        if (marcadores != 1) {
            throw new IllegalStateException("AS400_SQL_CLIENTE debe tener exactamente un marcador '?'");
        }
        return sql;
    }
}
