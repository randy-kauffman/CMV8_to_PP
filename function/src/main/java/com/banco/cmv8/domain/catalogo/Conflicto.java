package com.banco.cmv8.domain.catalogo;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Diferencia entre el catálogo y Content Manager, o rechazo imprevisto de CM (ADR-003 §4.4). Una fila de
 * {@code cmv8_conflicto} por clave; la deduplicación vive en la Azure Table.
 *
 * @param propiedadId id de wire de la propiedad afectada, si el conflicto es de una propiedad
 */
public record Conflicto(
        Clase clase,
        Severidad severidad,
        UUID tipoDocumentoId,
        String propiedadId,
        String detalle) {

    public enum Clase {
        CATALOGO_DESACTUALIZADO(1), OBLIGATORIA_SIN_ORIGEN(2), COLUMNA_INEXISTENTE(3),
        CARPETA_INEXISTENTE(4), TIPO_AUSENTE(5), RECHAZO_CM(6);
        public final int codigo;
        Clase(int c) { codigo = c; }
    }

    public enum Severidad {
        /** Content Manager rechazaría el archivado: los adjuntos quedan Detenida. */
        BLOQUEA(1),
        /** El catálogo es más estricto que CM: no detiene nada. */
        AVISO(2);
        public final int codigo;
        Severidad(int c) { codigo = c; }
    }

    public Conflicto {
        Objects.requireNonNull(clase, "clase");
        Objects.requireNonNull(severidad, "severidad");
        Objects.requireNonNull(tipoDocumentoId, "tipoDocumentoId");
    }

    public static Conflicto bloqueante(Clase clase, UUID tipo, String propiedadId, String detalle) {
        return new Conflicto(clase, Severidad.BLOQUEA, tipo, propiedadId, detalle);
    }

    public static Conflicto aviso(Clase clase, UUID tipo, String propiedadId, String detalle) {
        return new Conflicto(clase, Severidad.AVISO, tipo, propiedadId, detalle);
    }

    public Optional<String> propiedad() {
        return Optional.ofNullable(propiedadId);
    }

    public boolean bloquea() {
        return severidad == Severidad.BLOQUEA;
    }

    /** tipo + propiedad + clase: la misma clave que usa la Azure Table para deduplicar. */
    public String clave() {
        return tipoDocumentoId + "|" + (propiedadId == null ? "" : propiedadId) + "|" + clase.name();
    }
}
