package com.banco.cmv8.domain.port;

import com.banco.cmv8.domain.Adjunto;
import com.banco.cmv8.domain.ClaveAdjunto;
import com.banco.cmv8.domain.ContextoLlamador;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Tablas de adjuntos de Dataverse, cualquiera sea la solución (ADR-003 §1.1). Dataverse participa en dos
 * momentos por archivo: autorizar (suplantando al usuario) y recibir el resultado (con el usuario de aplicación).
 */
public interface RepositorioAdjuntos {

    /**
     * La única llamada de autorización: PATCH de {@code rutablob} suplantando al llamador con
     * {@code Prefer: return=representation}. Si el usuario no puede escribir la fila, Dataverse devuelve 403
     * y se lanza {@link com.banco.cmv8.application.AccesoDenegadoException}; si la fila no existe, 404 y
     * {@link com.banco.cmv8.application.SolicitudNoEncontradaException}. Devuelve la fila completa.
     */
    Adjunto registrarRutaSuplantando(ClaveAdjunto clave, String ruta, ContextoLlamador llamador);

    /** Lectura suplantando al llamador (SAS de lectura, Reanudar, Archivar): 403 → acceso denegado. */
    Optional<Adjunto> obtenerSuplantando(ClaveAdjunto clave, ContextoLlamador llamador);

    /** Lectura con el usuario de aplicación (operación y compensaciones). */
    Optional<Adjunto> obtener(ClaveAdjunto clave);

    /**
     * PATCH con el usuario de aplicación. Las claves son nombres lógicos SIN prefijo (por ejemplo
     * {@link Adjunto#COL_ESTADO}); el adaptador antepone el prefijo derivado de la tabla. Un valor
     * {@code null} limpia la columna; un {@link com.banco.cmv8.domain.EstadoIngesta} se escribe por su código.
     */
    void actualizar(ClaveAdjunto clave, Map<String, Object> cambios);

    /** Nombres lógicos completos de las columnas de la tabla (metadata), para ValidarTabla. */
    Set<String> columnas(String tabla);
}
