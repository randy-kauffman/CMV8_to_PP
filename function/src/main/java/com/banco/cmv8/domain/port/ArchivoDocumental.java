package com.banco.cmv8.domain.port;

import com.banco.cmv8.domain.SolicitudIngesta;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Sistema de registro documental (IBM Content Manager V8 vía CMIS). */
public interface ArchivoDocumental {

    /**
     * Crea el documento y devuelve su objectId.
     *
     * @param propiedades propiedades adicionales ya resueltas (se suman al mapeo configurado)
     */
    String archivar(SolicitudIngesta solicitud, InputStream contenido, long tamano, Map<String, Object> propiedades);

    /** Busca un documento ya creado para la solicitud (idempotencia ante caídas entre archivar y marcar). */
    Optional<String> buscarPorSolicitud(SolicitudIngesta solicitud);
}
