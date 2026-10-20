package com.banco.cmv8.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Mensaje de la cola de ingesta: qué adjunto procesar y dónde está su blob.
 *
 * @param contenedor contenedor del blob (ingesta, o almacenados cuando lo encola la operación Archivar)
 * @param origen     quién lo encoló (escaneo, blob-creado, watchdog, archivar, reanudar, conflicto), solo para trazabilidad
 */
public record MensajeIngesta(String tabla, UUID id, String contenedor, String rutaBlob, String origen) {

    public MensajeIngesta {
        Objects.requireNonNull(tabla, "tabla");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(contenedor, "contenedor");
        Objects.requireNonNull(rutaBlob, "rutaBlob");
    }

    public MensajeIngesta(ClaveAdjunto clave, String contenedor, String rutaBlob, String origen) {
        this(clave.tabla(), clave.id(), contenedor, rutaBlob, origen);
    }

    public ClaveAdjunto clave() {
        return new ClaveAdjunto(tabla, id);
    }
}
