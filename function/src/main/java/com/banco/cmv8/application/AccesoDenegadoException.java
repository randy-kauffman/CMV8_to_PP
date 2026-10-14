package com.banco.cmv8.application;

/** El llamador no tiene acceso a la solicitud (HTTP 403). */
public class AccesoDenegadoException extends RuntimeException {

    public AccesoDenegadoException(String mensaje) {
        super(mensaje);
    }
}
