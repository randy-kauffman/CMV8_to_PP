package com.banco.cmv8.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Mensaje de la cola de ingesta: qué solicitud procesar y dónde está su blob.
 *
 * @param origen quién lo encoló (escaneo, blob-creado, watchdog), solo para trazabilidad
 */
public record MensajeIngesta(UUID solicitudId, String rutaBlob, String origen) {

    public MensajeIngesta {
        Objects.requireNonNull(solicitudId, "solicitudId");
        Objects.requireNonNull(rutaBlob, "rutaBlob");
    }
}
