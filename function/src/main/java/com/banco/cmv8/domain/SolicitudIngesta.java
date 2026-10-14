package com.banco.cmv8.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Fila de seguimiento de una ingesta (tabla SolicitudIngesta en Dataverse).
 * Los campos opcionales pueden venir en null.
 */
public record SolicitudIngesta(
        UUID id,
        String nombreArchivo,
        Long tamanoBytes,
        String contentType,
        EstadoSolicitud estado,
        String rutaBlob,
        String cmisObjectId,
        String idCliente,
        String idCliente2,
        String nombreCliente,
        String afiliadoPadre,
        String idDueno,
        String mensajeError) {

    public SolicitudIngesta {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(estado, "estado");
    }

    public boolean yaArchivada() {
        return estado == EstadoSolicitud.ARCHIVADO && cmisObjectId != null && !cmisObjectId.isBlank();
    }

    public boolean tieneIdCliente() {
        return idCliente != null && !idCliente.isBlank();
    }

    public boolean faltaDatosCliente() {
        return idCliente2 == null || idCliente2.isBlank();
    }

    /** Devuelve una copia con los datos de cliente resueltos desde AS400. */
    public SolicitudIngesta conCliente(Cliente cliente) {
        return new SolicitudIngesta(id, nombreArchivo, tamanoBytes, contentType, estado, rutaBlob, cmisObjectId,
                cliente.idCliente(), cliente.idCliente2(), cliente.nombre(), cliente.afiliadoPadre(),
                cliente.idDueno(), mensajeError);
    }

    public SolicitudIngesta conEstado(EstadoSolicitud nuevo) {
        return new SolicitudIngesta(id, nombreArchivo, tamanoBytes, contentType, nuevo, rutaBlob, cmisObjectId,
                idCliente, idCliente2, nombreCliente, afiliadoPadre, idDueno, mensajeError);
    }
}
