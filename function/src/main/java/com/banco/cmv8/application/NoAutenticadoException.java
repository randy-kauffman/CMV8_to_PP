package com.banco.cmv8.application;

/** Falta la identidad del llamador (HTTP 401). */
public class NoAutenticadoException extends RuntimeException {

    public NoAutenticadoException(String mensaje) {
        super(mensaje);
    }
}
