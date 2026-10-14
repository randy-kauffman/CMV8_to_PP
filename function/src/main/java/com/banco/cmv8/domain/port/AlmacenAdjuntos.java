package com.banco.cmv8.domain.port;

import java.io.InputStream;
import java.util.Optional;

/** Acceso del worker a los blobs del contenedor de ingesta. */
public interface AlmacenAdjuntos {

    /** Stream de lectura; el llamador lo cierra. No carga el blob en memoria. */
    InputStream abrir(String rutaBlob);

    long tamano(String rutaBlob);

    boolean existe(String rutaBlob);

    /** Borrado idempotente: no falla si el blob ya no existe. */
    void borrar(String rutaBlob);

    /**
     * Resultado del antivirus guardado por Defender en la etiqueta de índice
     * "Malware Scanning scan result", si existe.
     */
    Optional<String> resultadoEscaneo(String rutaBlob);
}
