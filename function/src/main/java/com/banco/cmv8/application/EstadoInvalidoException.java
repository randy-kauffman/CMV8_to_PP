package com.banco.cmv8.application;

/** La solicitud está en un estado que no admite la operación (HTTP 409). */
public class EstadoInvalidoException extends RuntimeException {

    public EstadoInvalidoException(String mensaje) {
        super(mensaje);
    }
}
