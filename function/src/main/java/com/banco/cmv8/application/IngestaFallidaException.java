package com.banco.cmv8.application;

/** Falla del worker; se relanza para que la cola reintente. */
public class IngestaFallidaException extends RuntimeException {

    public IngestaFallidaException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
