package com.banco.cmv8.application;

import java.util.Optional;

/**
 * Entra no entregó el token de Storage en nombre del usuario (OBO): consentimiento faltante, acceso
 * condicional, credencial del broker inválida, etc. El mensaje nunca incluye tokens.
 *
 * <p>Si el código es {@code interaction_required} (acceso condicional sobre Storage que el token del llamador no
 * satisface), Entra manda un desafío de claims que el cliente debería presentar al volver a autenticarse;
 * se propaga en {@link #claims()} para devolverlo en {@code WWW-Authenticate} (HTTP 401). Cualquier otro
 * código es una falla de dependencia (HTTP 502).
 */
public class IntercambioTokenException extends RuntimeException {

    public static final String INTERACCION_REQUERIDA = "interaction_required";

    private final String codigoEntra;
    private final String claims;

    public IntercambioTokenException(String mensaje, String codigoEntra, Throwable causa) {
        this(mensaje, codigoEntra, null, causa);
    }

    public IntercambioTokenException(String mensaje, String codigoEntra, String claims, Throwable causa) {
        super(mensaje, causa);
        this.codigoEntra = codigoEntra;
        this.claims = claims == null || claims.isBlank() ? null : claims;
    }

    /** Código de error de Entra (p. ej. invalid_grant, interaction_required) o "-". */
    public String codigoEntra() {
        return codigoEntra;
    }

    public boolean requiereInteraccion() {
        return INTERACCION_REQUERIDA.equals(codigoEntra);
    }

    /** Desafío de claims de acceso condicional (JSON), si Entra lo mandó. */
    public Optional<String> claims() {
        return Optional.ofNullable(claims);
    }
}
