package com.banco.cmv8.domain;

import java.time.OffsetDateTime;

/**
 * SAS emitida para un blob. {@code url} contiene el token: NUNCA loguearla.
 */
public record SasEmitida(String url, OffsetDateTime expira, String versionServicio) {

    @Override
    public String toString() {
        return "SasEmitida[expira=" + expira + ", versionServicio=" + versionServicio + "]";
    }
}
