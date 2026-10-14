package com.banco.cmv8.application;

import com.banco.cmv8.domain.ContextoLlamador;
import com.banco.cmv8.domain.TokenUsuario;
import com.banco.cmv8.domain.port.IntercambiadorTokenUsuario;

import java.util.Objects;
import java.util.Optional;

/**
 * Regla de SAS ligada al usuario (S1b, SAS_LIGADA_USUARIO=true): exige la identidad del llamador y consigue,
 * por OBO, el token de Storage que el PCF debe mandar junto con la SAS. Desactivada, no hace nada.
 */
public final class VinculoUsuario {

    private static final VinculoUsuario DESACTIVADO = new VinculoUsuario(null);

    private final IntercambiadorTokenUsuario intercambiador;

    private VinculoUsuario(IntercambiadorTokenUsuario intercambiador) {
        this.intercambiador = intercambiador;
    }

    public static VinculoUsuario desactivado() {
        return DESACTIVADO;
    }

    public static VinculoUsuario activo(IntercambiadorTokenUsuario intercambiador) {
        return new VinculoUsuario(Objects.requireNonNull(intercambiador, "intercambiador"));
    }

    public boolean activo() {
        return intercambiador != null;
    }

    /** Sin oid o sin bearer no se puede ligar la SAS ni hacer OBO: 401 antes de tocar Dataverse. */
    void exigirIdentidad(ContextoLlamador llamador) {
        if (!activo()) {
            return;
        }
        if (llamador.objectId().isEmpty()) {
            throw new NoAutenticadoException(
                    "La SAS ligada al usuario requiere la identidad Entra del llamador (falta el oid)");
        }
        if (llamador.bearerToken().isEmpty()) {
            throw new NoAutenticadoException(
                    "La SAS ligada al usuario requiere el token Entra del llamador (falta Authorization: Bearer)");
        }
    }

    /** Token de Storage del usuario, o vacío si la regla está desactivada. */
    Optional<TokenUsuario> tokenStorage(ContextoLlamador llamador) {
        return activo() ? Optional.of(intercambiador.tokenStorage(llamador)) : Optional.empty();
    }
}
