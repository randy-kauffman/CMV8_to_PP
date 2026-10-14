package com.banco.cmv8.application;

/**
 * Entra no entregó el token de Storage en nombre del usuario (OBO): consentimiento faltante, acceso
 * condicional, secreto vencido, etc. (HTTP 502). El mensaje nunca incluye tokens.
 */
public class IntercambioTokenException extends RuntimeException {

    private final String codigoEntra;

    public IntercambioTokenException(String mensaje, String codigoEntra, Throwable causa) {
        super(mensaje, causa);
        this.codigoEntra = codigoEntra;
    }

    /** Código de error de Entra (p. ej. invalid_grant, interaction_required) o "-". */
    public String codigoEntra() {
        return codigoEntra;
    }
}
