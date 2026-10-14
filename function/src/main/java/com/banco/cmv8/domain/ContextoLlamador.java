package com.banco.cmv8.domain;

import java.util.Optional;

/**
 * Identidad del usuario que llama a la Function, tal como llega en los encabezados.
 * El token NO se valida aquí: lo valida la autenticación integrada de App Service (S7).
 * Nunca se loguea el bearerToken.
 */
public record ContextoLlamador(
        Optional<String> objectId,
        Optional<String> tenantId,
        Optional<String> bearerToken,
        Optional<String> ip) {

    public ContextoLlamador {
        objectId = objectId == null ? Optional.empty() : objectId;
        tenantId = tenantId == null ? Optional.empty() : tenantId;
        bearerToken = bearerToken == null ? Optional.empty() : bearerToken;
        ip = ip == null ? Optional.empty() : ip;
    }

    public static ContextoLlamador anonimo() {
        return new ContextoLlamador(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** Representación segura para logs: sin token. */
    @Override
    public String toString() {
        return "ContextoLlamador[oid=" + objectId.orElse("-") + ", tid=" + tenantId.orElse("-")
                + ", ip=" + ip.orElse("-") + ", token=" + (bearerToken.isPresent() ? "***" : "-") + "]";
    }
}
