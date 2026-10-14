package com.banco.cmv8.domain;

import java.time.OffsetDateTime;

/**
 * Access token Entra de un usuario para un recurso aguas abajo (Storage), obtenido por OBO.
 * {@code valor} es un secreto: NUNCA loguearlo.
 */
public record TokenUsuario(String valor, OffsetDateTime expira) {

    @Override
    public String toString() {
        return "TokenUsuario[valor=***, expira=" + expira + "]";
    }
}
