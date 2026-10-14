package com.banco.cmv8.application;

/** La solicitud o su blob no existe (HTTP 404). */
public class SolicitudNoEncontradaException extends RuntimeException {

    public SolicitudNoEncontradaException(String mensaje) {
        super(mensaje);
    }
}
