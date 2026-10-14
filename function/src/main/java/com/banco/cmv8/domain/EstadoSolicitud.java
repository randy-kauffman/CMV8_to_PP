package com.banco.cmv8.domain;

/**
 * Estados de la tabla SolicitudIngesta. El código coincide con el valor de la opción
 * de la columna cmv8_estado en Dataverse.
 */
public enum EstadoSolicitud {
    CREADA(1),
    SUBIDO(2),
    PROCESANDO(3),
    ARCHIVADO(4),
    RECHAZADO(5),
    ERROR(6);

    private final int codigo;

    EstadoSolicitud(int codigo) {
        this.codigo = codigo;
    }

    public int codigo() {
        return codigo;
    }

    public static EstadoSolicitud desdeCodigo(int codigo) {
        for (EstadoSolicitud e : values()) {
            if (e.codigo == codigo) {
                return e;
            }
        }
        throw new IllegalArgumentException("Código de estado desconocido: " + codigo);
    }

    /** Estados finales: el worker no vuelve a tocar la solicitud. */
    public boolean esFinal() {
        return this == ARCHIVADO || this == RECHAZADO;
    }
}
