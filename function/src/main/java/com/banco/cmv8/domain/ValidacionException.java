package com.banco.cmv8.domain;

/** Entrada inválida del llamador (se traduce a HTTP 400). */
public class ValidacionException extends RuntimeException {

    public ValidacionException(String mensaje) {
        super(mensaje);
    }
}
