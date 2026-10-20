package com.banco.cmv8.adapters.out.dataverse;

/**
 * Nombres lógicos de la solución base v2 (ADR-003 §1.2, {@code dataverse/esquema-v2.py}). Los nombres lógicos son
 * los SchemaName en minúsculas; las propiedades de navegación conservan el SchemaName (con mayúsculas) porque así
 * las publica Dataverse. Los entity set names NO están acá: se resuelven por metadata para no adivinar la
 * pluralización. Las columnas de las tablas de adjuntos tampoco: dependen del prefijo de cada solución
 * ({@link com.banco.cmv8.domain.ClaveAdjunto#columna(String)}).
 */
public final class NombresDataverse {

    public static final String VERSION_API = "v9.2";

    /** Columnas de sistema comunes a toda tabla. */
    public static final String MODIFICADO = "modifiedon";

    /** Largo máximo de los textos largos (memo) del esquema, incluido {@code mensajeerror}. */
    public static final int LARGO_MEMO = 4000;
    public static final int LARGO_NOMBRE = 200;
    public static final int LARGO_TEXTO_400 = 400;

    // ---------- cmv8_tipodocumento ----------
    public static final String TABLA_TIPO = "cmv8_tipodocumento";
    public static final String TIPO_ID = "cmv8_tipodocumentoid";
    public static final String TIPO_NOMBRE = "cmv8_nombre";
    public static final String TIPO_CODIGO = "cmv8_codigo";
    public static final String TIPO_ORIGEN = "cmv8_origentipo";
    public static final String TIPO_CMIS_ID = "cmv8_tipocmisid";
    public static final String TIPO_LOCAL_NAME = "cmv8_localname";
    public static final String TIPO_VERSIONABLE = "cmv8_versionable";
    public static final String TIPO_AUSENTE = "cmv8_ausenteenservidor";
    public static final String TIPO_REVISADO = "cmv8_revisado";
    public static final String TIPO_CAMBIOS = "cmv8_cambios";
    public static final String TIPO_DESCUBIERTO_EN = "cmv8_descubiertoen";
    public static final String TIPO_EN_USO = "cmv8_enuso";
    public static final String TIPO_CARPETA = "cmv8_carpeta";
    public static final String TIPO_ORIGEN_CARPETA = "cmv8_origencarpeta";
    public static final String TIPO_DESTINO = "cmv8_destino";
    public static final String TIPO_MOMENTO = "cmv8_momento";
    public static final String TIPO_DIAS_RETENCION = "cmv8_diasretencion";
    public static final String TIPO_INMUTABLE = "cmv8_inmutable";

    /** Código de {@code cmv8_origencarpeta} para una carpeta fijada a mano (1 = derivada). */
    public static final int ORIGEN_CARPETA_DERIVADA = 1;
    public static final int ORIGEN_CARPETA_MANUAL = 2;

    public static final String SELECT_TIPO = String.join(",",
            TIPO_ID, TIPO_NOMBRE, TIPO_CODIGO, TIPO_ORIGEN, TIPO_CMIS_ID, TIPO_LOCAL_NAME, TIPO_VERSIONABLE,
            TIPO_AUSENTE, TIPO_REVISADO, TIPO_CAMBIOS, TIPO_DESCUBIERTO_EN, TIPO_EN_USO, TIPO_CARPETA,
            TIPO_ORIGEN_CARPETA, TIPO_DESTINO, TIPO_MOMENTO, TIPO_DIAS_RETENCION, TIPO_INMUTABLE);

    // ---------- cmv8_propiedadcm ----------
    public static final String TABLA_PROPIEDAD = "cmv8_propiedadcm";
    public static final String PROP_ID = "cmv8_propiedadcmid";
    public static final String PROP_NOMBRE = "cmv8_nombre";
    public static final String PROP_PROPIEDAD_ID = "cmv8_propiedadid";
    public static final String PROP_NOMBRE_VISIBLE = "cmv8_nombrevisible";
    public static final String PROP_TIPO_DATO = "cmv8_tipodato";
    public static final String PROP_OBLIGATORIA = "cmv8_obligatoria";
    public static final String PROP_VALOR_DEFECTO = "cmv8_valorpordefecto";
    public static final String PROP_LARGO_MAXIMO = "cmv8_largomaximo";
    public static final String PROP_LISTA_VALORES = "cmv8_listavalores";
    public static final String PROP_HEREDADA = "cmv8_heredada";
    public static final String PROP_DECISION = "cmv8_decision";
    public static final String PROP_ORIGEN = "cmv8_origen";
    public static final String PROP_NOMBRE_LOGICO = "cmv8_nombrelogico";
    public static final String PROP_CONSULTA = "cmv8_consulta";
    public static final String PROP_LITERAL = "cmv8_literal";
    public static final String PROP_PATRON = "cmv8_patron";
    public static final String PROP_MOMENTO_VALIDACION = "cmv8_momentovalidacion";
    /** Valor de lectura del lookup al tipo ({@code _<lookup>_value}), usable en $filter. */
    public static final String PROP_TIPO_VALOR = "_cmv8_tipodocumento_value";

    public static final String SELECT_PROPIEDAD = String.join(",",
            PROP_ID, PROP_NOMBRE, PROP_PROPIEDAD_ID, PROP_NOMBRE_VISIBLE, PROP_TIPO_DATO, PROP_OBLIGATORIA,
            PROP_VALOR_DEFECTO, PROP_LARGO_MAXIMO, PROP_LISTA_VALORES, PROP_HEREDADA, PROP_DECISION, PROP_ORIGEN,
            PROP_NOMBRE_LOGICO, PROP_CONSULTA, PROP_LITERAL, PROP_PATRON, PROP_MOMENTO_VALIDACION);

    // ---------- cmv8_conflicto ----------
    public static final String TABLA_CONFLICTO = "cmv8_conflicto";
    public static final String CONF_ID = "cmv8_conflictoid";
    public static final String CONF_NOMBRE = "cmv8_nombre";
    public static final String CONF_CLASE = "cmv8_clase";
    public static final String CONF_SEVERIDAD = "cmv8_severidad";
    public static final String CONF_DETALLE = "cmv8_detalle";
    public static final String CONF_DETECTADO_EN = "cmv8_detectadoen";
    public static final String CONF_ESTADO = "cmv8_estadoconflicto";
    public static final String CONF_RESUELTO_EN = "cmv8_resueltoen";
    public static final String CONF_ADJUNTOS_DETENIDOS = "cmv8_adjuntosdetenidos";
    public static final String CONF_CLAVE = "cmv8_clave";
    public static final String CONF_TIPO_VALOR = "_cmv8_tipodocumento_value";

    /** Códigos de {@code cmv8_estadoconflicto}. */
    public static final int CONFLICTO_ABIERTO = 1;
    public static final int CONFLICTO_RESUELTO = 2;

    public static final String SELECT_CONFLICTO = String.join(",",
            CONF_ID, CONF_CLAVE, CONF_TIPO_VALOR, CONF_CLASE, CONF_ESTADO);

    // ---------- relaciones y navegación ----------
    /**
     * Propiedad de navegación de colección tipo → propiedades: el SchemaName de la relación 1:N
     * ({@code ReferencedEntityNavigationPropertyName} por defecto).
     */
    public static final String NAV_TIPO_PROPIEDADES = "cmv8_tipodocumento_propiedadcm";
    /**
     * Propiedades de navegación single-valued (lado N): el SchemaName de la lookup
     * ({@code ReferencingEntityNavigationPropertyName} por defecto). Son sensibles a mayúsculas en @odata.bind.
     */
    public static final String NAV_TIPO_DOCUMENTO = "cmv8_TipoDocumento";
    public static final String NAV_PROPIEDAD_CM = "cmv8_PropiedadCm";

    private NombresDataverse() {
    }
}
