package com.banco.cmv8.domain;

/**
 * Los diez estados del ciclo de vida de un adjunto (ADR-003 §2). El código coincide con el valor del choice
 * global {@code cmv8_estadoingesta}. Un estado dice DÓNDE está el archivo y quién tiene que actuar;
 * la intención (ir a Content Manager ahora, después o nunca) vive en el tipo de documento.
 */
public enum EstadoIngesta {
    /** Registro creado, archivo todavía no subido. Actúa el usuario. */
    CREADA(1),
    /** Archivo en el contenedor de ingesta, esperando proceso. */
    SUBIDO(2),
    /** El worker lo está archivando. */
    PROCESANDO(3),
    /** Guardado en Content Manager. Terminal. */
    ARCHIVADO(4),
    /** Rechazado de forma definitiva (malware o vencido sin corrección). Terminal. */
    RECHAZADO(5),
    /** Falló por una causa técnica. Actúa operación: reintentar. */
    ERROR(6),
    /** En el depósito (Blob), no en Content Manager. Terminal para solo-blob; en espera para diferido. */
    ALMACENADO(7),
    /** Falta o es inválido un valor de este archivo. Actúa el usuario: corregir y reanudar. */
    OBSERVADA(8),
    /** Conflicto entre el catálogo y Content Manager. Actúa el administrador documental. */
    DETENIDA(9),
    /** Eliminado del depósito por retención. Terminal. */
    DEPURADO(10);

    private final int codigo;

    EstadoIngesta(int codigo) {
        this.codigo = codigo;
    }

    public int codigo() {
        return codigo;
    }

    public static EstadoIngesta desdeCodigo(int codigo) {
        for (EstadoIngesta e : values()) {
            if (e.codigo == codigo) {
                return e;
            }
        }
        throw new IllegalArgumentException("Código de estado desconocido: " + codigo);
    }

    /** Terminales: el worker no vuelve a tocar el adjunto. */
    public boolean esFinal() {
        return this == ARCHIVADO || this == RECHAZADO || this == DEPURADO;
    }

    /** Estados desde los que se puede emitir una SAS de subida. */
    public boolean admiteSubida() {
        return this == CREADA || this == ERROR;
    }

    /** Hay un humano pendiente: el timer de depuración no borra y los reintentos automáticos no aplican. */
    public boolean esperaHumano() {
        return this == OBSERVADA || this == DETENIDA;
    }
}
