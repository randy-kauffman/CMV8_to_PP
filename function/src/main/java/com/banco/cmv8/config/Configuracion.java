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

    // ---------------------------------------------------------------- app del broker (OBO, S1b)

    /** Client id de la app registration del broker (la misma audiencia que valida Easy Auth). */
    public String brokerClientId() {
        return requerido("BROKER_CLIENT_ID");
    }

    public String brokerTenantId() {
        return requerido("BROKER_TENANT_ID");
    }

    /**
     * Secreto de la app del broker para el OBO. SOLO desarrollo local: en Azure la credencial del broker es la
     * identidad administrada ({@link #azureClientId()}) por credencial federada y este valor no existe. Nunca loguear.
     */
    public Optional<String> brokerClientSecret() {
        return opcional("BROKER_CLIENT_SECRET");
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

    /** Contenedor de lo que se queda en Blob: retenidos, solo-blob, diferidos en espera (ADR-003 §7). */
    public String contenedorAlmacenados() {
        return conDefecto("ADJUNTOS_CONTAINER_ALMACENADOS", "cmv8-almacenados");
    }

    /** Termina en '/'. */
    public String tableEndpoint() {
        String url = requerido("ADJUNTOS_TABLE_ENDPOINT");
        return url.endsWith("/") ? url : url + "/";
    }

    public String tablaIngesta() {
        return conDefecto("TABLA_INGESTA", "ingesta");
    }

    public String tablaDepuracion() {
        return conDefecto("TABLA_DEPURACION", "depuracion");
    }

    /** Días que un adjunto en Observada espera corrección antes de pasar a Rechazado (ADR-003 §4.5). */
    public int observadaDias() {
        return (int) entero("OBSERVADA_DIAS", 30);
    }

    public boolean wormHabilitado() {
        return booleano("WORM_HABILITADO", false);
    }

    public boolean wormBloquear() {
        return booleano("WORM_BLOQUEAR", true);
    }

    public Duration catalogoCache() {
        return minutos("CATALOGO_CACHE_MINUTOS", 10);
    }

    /** Tope de adjuntos por lote de SAS (ADR-003 §4.1). */
    public int loteMaximo() {
        return (int) entero("SAS_LOTE_MAXIMO", 20);
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

    /** Conexiones máximas del pool por instancia de la Function. */
    public int as400PoolMaximo() {
        return (int) entero("AS400_POOL_MAX", 4);
    }

    /**
     * Vida máxima de una conexión del pool. Debe ser menor que el timeout de inactividad del job QZDASOINIT
     * y que el idle timeout del firewall hacia el IBM i, para que el pool recicle antes de que el otro lado corte.
     */
    public Duration as400PoolVidaMaxima() {
        return minutos("AS400_POOL_VIDA_MAX_MINUTOS", 10);
    }

    /** v1: una sola consulta con un '?'. En v2 es la consulta con nombre "cliente" si no hay AS400_CONSULTAS_JSON. */
    public Optional<String> as400SqlCliente() {
        return opcional("AS400_SQL_CLIENTE");
    }

    /**
     * v2 (ADR-003 §6): consultas con nombre como JSON {@code {"nombre": "SELECT ... WHERE X = :parametro"}}.
     * Los parámetros se escriben {@code :nombre} y se pasan por nombre desde el catálogo. El SQL vive solo acá.
     */
    public String as400ConsultasJson() {
        return conDefecto("AS400_CONSULTAS_JSON", "");
    }

    // ---------------------------------------------------------------- CMIS v2
    /** Prefijo a quitar del id de wire para obtener el nombre canónico; vacío = regla por defecto (último '.' o ':'). */
    public Optional<String> cmisPrefijoPropiedades() {
        return opcional("CMIS_PREFIJO_PROPIEDADES");
    }

    /** Nombre canónico de la propiedad que trae el ID corto del tipo (regla de CMCourier); vacío = solo el nombre visible. */
    public Optional<String> cmisPropiedadIdCorto() {
        return opcional("CMIS_PROPIEDAD_ID_CORTO");
    }

    /** Plantilla de la carpeta derivada de un tipo descubierto; {localName} se reemplaza. Por defecto {@code /{localName}}. */
    public String cmisCarpetaPlantilla() {
        return conDefecto("CMIS_CARPETA_PLANTILLA", "/{localName}");
    }

    /** Minutos de antigüedad para que VigilarCatalogo considere vencida su última corrida (evita solapes). */
    public Duration vigilanciaCatalogo() {
        return minutos("VIGILAR_CATALOGO_MINUTOS", 60);
    }
}
