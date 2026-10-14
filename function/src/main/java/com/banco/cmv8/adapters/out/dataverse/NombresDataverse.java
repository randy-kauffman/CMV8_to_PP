package com.banco.cmv8.adapters.out.dataverse;

/** Nombres lógicos de la tabla SolicitudIngesta (dataverse/metadata). */
public final class NombresDataverse {

    public static final String VERSION_API = "v9.2";

    public static final String CONJUNTO = "cmv8_solicitudingestas";

    public static final String ID = "cmv8_solicitudingestaid";
    public static final String NOMBRE = "cmv8_nombre";
    public static final String NOMBRE_ARCHIVO = "cmv8_nombrearchivo";
    public static final String TAMANO_BYTES = "cmv8_tamanobytes";
    public static final String CONTENT_TYPE = "cmv8_contenttype";
    public static final String ESTADO = "cmv8_estado";
    public static final String RUTA_BLOB = "cmv8_rutablob";
    public static final String CMIS_OBJECT_ID = "cmv8_cmisobjectid";
    public static final String ID_CLIENTE = "cmv8_idcliente";
    public static final String ID_CLIENTE2 = "cmv8_idcliente2";
    public static final String NOMBRE_CLIENTE = "cmv8_nombrecliente";
    public static final String AFILIADO_PADRE = "cmv8_afiliadopadre";
    public static final String ID_DUENO = "cmv8_iddueno";
    public static final String MENSAJE_ERROR = "cmv8_mensajeerror";
    public static final String MODIFICADO = "modifiedon";
    public static final String STATECODE = "statecode";

    /** Largo máximo de cmv8_mensajeerror. */
    public static final int LARGO_MENSAJE_ERROR = 4000;

    /** $select completo para mapear a SolicitudIngesta. */
    public static final String SELECT_COMPLETO = String.join(",",
            ID, NOMBRE_ARCHIVO, TAMANO_BYTES, CONTENT_TYPE, ESTADO, RUTA_BLOB, CMIS_OBJECT_ID,
            ID_CLIENTE, ID_CLIENTE2, NOMBRE_CLIENTE, AFILIADO_PADRE, ID_DUENO, MENSAJE_ERROR, MODIFICADO);

    private NombresDataverse() {
    }
}
