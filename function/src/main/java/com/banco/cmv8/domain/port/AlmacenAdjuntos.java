package com.banco.cmv8.domain.port;

import java.io.InputStream;
import java.time.Instant;
import java.util.Optional;

/**
 * Blobs de la cuenta de adjuntos con la identidad administrada (ADR-003 §7): el contenedor de ingesta (en vuelo)
 * y el de almacenados (lo que se queda). Toda operación recibe el contenedor explícito.
 */
public interface AlmacenAdjuntos {

    InputStream abrir(String contenedor, String ruta);

    long tamano(String contenedor, String ruta);

    boolean existe(String contenedor, String ruta);

    void borrar(String contenedor, String ruta);

    /** Escribe un blob completo desde un stream (ingesta desde Power Automate, ADR-003 §4.7). */
    void escribir(String contenedor, String ruta, InputStream contenido, long tamano, String contentType);

    /** Copia del lado del servidor y borrado del origen. Mover no dispara BlobCreated en ingesta. */
    void mover(String contenedorOrigen, String rutaOrigen, String contenedorDestino, String rutaDestino);

    /**
     * Política de inmutabilidad a nivel de versión hasta {@code hasta}; bloqueada si {@code bloquear}
     * (solo bloqueada cumple regulación; bloqueada solo se extiende, nunca se acorta ni se borra).
     */
    void ponerInmutable(String contenedor, String ruta, Instant hasta, boolean bloquear);

    /** Resultado del escaneo de Defender (etiqueta de índice), si hay. */
    Optional<String> resultadoEscaneo(String contenedor, String ruta);
}
