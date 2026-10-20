package com.banco.cmv8.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Fila de la Azure Table {@code ingesta} (ADR-003 §3): la verdad OPERATIVA de un adjunto. Dataverse guarda la
 * verdad de negocio. La escribe el broker al emitir la SAS y el worker en cada paso; nadie más la lee.
 *
 * @param valores        columnas de la fila de Dataverse al emitir la SAS (origen "columna" de las propiedades)
 * @param fechaDepuracion día en que el timer Depurar borra el blob; nula = permanente (o en espera, si es diferido)
 * @param conflictoClave clave del conflicto de catálogo que lo tiene Detenida; nula si no aplica
 */
public record RegistroIngesta(
        ClaveAdjunto clave,
        EstadoIngesta estado,
        UUID tipoDocumentoId,
        String contenedor,
        String ruta,
        String solicitanteOid,
        String nombreArchivo,
        Long tamanoBytes,
        String contentType,
        Map<String, Object> valores,
        String cmisObjectId,
        int intentos,
        String mensaje,
        String conflictoClave,
        LocalDate fechaDepuracion,
        Instant sasEmitidaEn,
        Instant subidoEn,
        Instant procesadoEn) {

    public RegistroIngesta {
        Objects.requireNonNull(clave, "clave");
        Objects.requireNonNull(estado, "estado");
        Objects.requireNonNull(contenedor, "contenedor");
        Objects.requireNonNull(ruta, "ruta");
        valores = valores == null ? Map.of() : Map.copyOf(valores);
    }

    /** Registro inicial que escribe el broker al emitir la SAS. */
    public static RegistroIngesta alEmitirSas(Adjunto adjunto, String contenedor, String ruta, String solicitanteOid,
                                              Instant ahora) {
        return new RegistroIngesta(adjunto.clave(), EstadoIngesta.CREADA, adjunto.tipoDocumentoId(), contenedor, ruta,
                solicitanteOid, adjunto.nombreArchivo(), adjunto.tamanoBytes(), adjunto.contentType(), adjunto.valores(),
                null, 0, null, null, null, ahora, null, null);
    }

    public Optional<LocalDate> depuracion() {
        return Optional.ofNullable(fechaDepuracion);
    }

    public RegistroIngesta conEstado(EstadoIngesta nuevo) {
        return new RegistroIngesta(clave, nuevo, tipoDocumentoId, contenedor, ruta, solicitanteOid, nombreArchivo,
                tamanoBytes, contentType, valores, cmisObjectId, intentos, mensaje, conflictoClave, fechaDepuracion,
                sasEmitidaEn, subidoEn, procesadoEn);
    }

    public RegistroIngesta conUbicacion(String nuevoContenedor, String nuevaRuta) {
        return new RegistroIngesta(clave, estado, tipoDocumentoId, nuevoContenedor, nuevaRuta, solicitanteOid,
                nombreArchivo, tamanoBytes, contentType, valores, cmisObjectId, intentos, mensaje, conflictoClave,
                fechaDepuracion, sasEmitidaEn, subidoEn, procesadoEn);
    }

    public RegistroIngesta conMensaje(String nuevoMensaje) {
        return new RegistroIngesta(clave, estado, tipoDocumentoId, contenedor, ruta, solicitanteOid, nombreArchivo,
                tamanoBytes, contentType, valores, cmisObjectId, intentos, nuevoMensaje, conflictoClave,
                fechaDepuracion, sasEmitidaEn, subidoEn, procesadoEn);
    }

    public RegistroIngesta conConflicto(String clave) {
        return new RegistroIngesta(this.clave, estado, tipoDocumentoId, contenedor, ruta, solicitanteOid,
                nombreArchivo, tamanoBytes, contentType, valores, cmisObjectId, intentos, mensaje, clave,
                fechaDepuracion, sasEmitidaEn, subidoEn, procesadoEn);
    }

    public RegistroIngesta conCmisObjectId(String objectId) {
        return new RegistroIngesta(clave, estado, tipoDocumentoId, contenedor, ruta, solicitanteOid, nombreArchivo,
                tamanoBytes, contentType, valores, objectId, intentos, mensaje, conflictoClave, fechaDepuracion,
                sasEmitidaEn, subidoEn, procesadoEn);
    }

    public RegistroIngesta conFechaDepuracion(LocalDate fecha) {
        return new RegistroIngesta(clave, estado, tipoDocumentoId, contenedor, ruta, solicitanteOid, nombreArchivo,
                tamanoBytes, contentType, valores, cmisObjectId, intentos, mensaje, conflictoClave, fecha,
                sasEmitidaEn, subidoEn, procesadoEn);
    }

    public RegistroIngesta conValores(Map<String, Object> nuevosValores) {
        return new RegistroIngesta(clave, estado, tipoDocumentoId, contenedor, ruta, solicitanteOid, nombreArchivo,
                tamanoBytes, contentType, nuevosValores, cmisObjectId, intentos, mensaje, conflictoClave,
                fechaDepuracion, sasEmitidaEn, subidoEn, procesadoEn);
    }

    public RegistroIngesta conTipo(UUID nuevoTipo) {
        return new RegistroIngesta(clave, estado, nuevoTipo, contenedor, ruta, solicitanteOid, nombreArchivo,
                tamanoBytes, contentType, valores, cmisObjectId, intentos, mensaje, conflictoClave,
                fechaDepuracion, sasEmitidaEn, subidoEn, procesadoEn);
    }

    public RegistroIngesta subido(Instant ahora) {
        return new RegistroIngesta(clave, EstadoIngesta.SUBIDO, tipoDocumentoId, contenedor, ruta, solicitanteOid,
                nombreArchivo, tamanoBytes, contentType, valores, cmisObjectId, intentos, mensaje, conflictoClave,
                fechaDepuracion, sasEmitidaEn, ahora, procesadoEn);
    }

    public RegistroIngesta intentoMas(Instant ahora) {
        return new RegistroIngesta(clave, EstadoIngesta.PROCESANDO, tipoDocumentoId, contenedor, ruta, solicitanteOid,
                nombreArchivo, tamanoBytes, contentType, valores, cmisObjectId, intentos + 1, mensaje, conflictoClave,
                fechaDepuracion, sasEmitidaEn, subidoEn, ahora);
    }
}
