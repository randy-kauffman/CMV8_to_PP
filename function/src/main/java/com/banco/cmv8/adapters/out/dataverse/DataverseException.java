package com.banco.cmv8.adapters.out.dataverse;

/** Respuesta inesperada de la Web API de Dataverse. */
public class DataverseException extends RuntimeException {

    private final int estadoHttp;

    public DataverseException(String mensaje, int estadoHttp) {
        super(mensaje);
        this.estadoHttp = estadoHttp;
    }

    public DataverseException(String mensaje, Throwable causa) {
        super(mensaje, causa);
        this.estadoHttp = -1;
    }

    public int estadoHttp() {
        return estadoHttp;
    }
}
