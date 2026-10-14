package com.banco.cmv8.domain.port;

import com.banco.cmv8.domain.Cliente;
import com.banco.cmv8.domain.EstadoSolicitud;
import com.banco.cmv8.domain.SolicitudIngesta;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Puerto hacia la tabla SolicitudIngesta. Opera con la identidad de la aplicación. */
public interface RepositorioSolicitudes {

    Optional<SolicitudIngesta> obtener(UUID id);

    void actualizarEstado(UUID id, EstadoSolicitud estado);

    /** Guarda la ruta del blob calculada por el broker (la ruta nunca la decide el cliente). */
    void registrarRutaBlob(UUID id, String rutaBlob);

    /** Guarda los datos de cliente resueltos desde AS400. */
    void registrarCliente(UUID id, Cliente cliente);

    void marcarArchivado(UUID id, String cmisObjectId);

    void marcarRechazado(UUID id, String motivo);

    void marcarError(UUID id, String mensaje);

    /** Solicitudes en {@code estado} sin modificar hace más de {@code antiguedad}. */
    List<SolicitudIngesta> listarEstancadas(EstadoSolicitud estado, Duration antiguedad);
}
