package com.banco.cmv8.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Fila de una tabla de adjuntos de Dataverse (ADR-003 §1.1): las columnas reservadas más TODOS los demás
 * valores de la fila, porque el valor de un metadato de origen "columna" es una columna real de la tabla
 * del maker con nombre lógico convenido.
 *
 * @param valores todas las columnas de la fila por nombre lógico completo (con prefijo), tal como las
 *                devuelve Dataverse; los lookups vienen como {@code _<nombre>_value}
 */
public record Adjunto(
        ClaveAdjunto clave,
        String nombreArchivo,
        Long tamanoBytes,
        String contentType,
        EstadoIngesta estado,
        String rutaBlob,
        String cmisObjectId,
        UUID tipoDocumentoId,
        String mensajeError,
        Map<String, Object> valores,
        Instant modificadoEn) {

    /** Nombres lógicos (sin prefijo) de las columnas reservadas. */
    public static final String COL_ESTADO = "estadoingesta";
    public static final String COL_TIPO = "tipodocumento";
    public static final String COL_RUTA = "rutablob";
    public static final String COL_CMIS = "cmisobjectid";
    public static final String COL_MENSAJE = "mensajeerror";
    public static final String COL_NOMBRE_ARCHIVO = "nombrearchivo";
    public static final String COL_TAMANO = "tamanobytes";
    public static final String COL_CONTENT_TYPE = "contenttype";

    public Adjunto {
        Objects.requireNonNull(clave, "clave");
        Objects.requireNonNull(estado, "estado");
        valores = valores == null ? Map.of() : Map.copyOf(valores);
    }

    public UUID id() {
        return clave.id();
    }

    public boolean yaArchivado() {
        return estado == EstadoIngesta.ARCHIVADO && cmisObjectId != null && !cmisObjectId.isBlank();
    }

    public boolean tieneTipo() {
        return tipoDocumentoId != null;
    }

    /** Valor de una columna del maker por nombre lógico sin prefijo (origen "columna" de una propiedad). */
    public Optional<Object> valor(String nombreLogico) {
        return Optional.ofNullable(valores.get(clave.columna(nombreLogico)));
    }

    public Adjunto conEstado(EstadoIngesta nuevo) {
        return new Adjunto(clave, nombreArchivo, tamanoBytes, contentType, nuevo, rutaBlob, cmisObjectId,
                tipoDocumentoId, mensajeError, valores, modificadoEn);
    }
}
