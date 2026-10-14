package com.banco.cmv8.adapters.out.memoria;

import com.banco.cmv8.domain.Cliente;
import com.banco.cmv8.domain.EstadoSolicitud;
import com.banco.cmv8.domain.SolicitudIngesta;
import com.banco.cmv8.domain.port.RepositorioSolicitudes;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Sustituto de Dataverse SOLO para el spike S1 (AUTORIZACION_MODO=ninguna y sin DATAVERSE_URL):
 * toda solicitud "existe" en estado CREADA y las escrituras se descartan con un warning.
 */
public class RepositorioSinDataverse implements RepositorioSolicitudes {

    private static final Logger LOG = Logger.getLogger(RepositorioSinDataverse.class.getName());

    @Override
    public Optional<SolicitudIngesta> obtener(UUID id) {
        return Optional.of(new SolicitudIngesta(id, null, null, null, EstadoSolicitud.CREADA,
                null, null, null, null, null, null, null, null));
    }

    @Override
    public void actualizarEstado(UUID id, EstadoSolicitud estado) {
        descartar(id, "actualizarEstado " + estado);
    }

    @Override
    public void registrarRutaBlob(UUID id, String rutaBlob) {
        descartar(id, "registrarRutaBlob");
    }

    @Override
    public void registrarCliente(UUID id, Cliente cliente) {
        descartar(id, "registrarCliente");
    }

    @Override
    public void marcarArchivado(UUID id, String cmisObjectId) {
        descartar(id, "marcarArchivado");
    }

    @Override
    public void marcarRechazado(UUID id, String motivo) {
        descartar(id, "marcarRechazado");
    }

    @Override
    public void marcarError(UUID id, String mensaje) {
        descartar(id, "marcarError");
    }

    @Override
    public List<SolicitudIngesta> listarEstancadas(EstadoSolicitud estado, Duration antiguedad) {
        return List.of();
    }

    private static void descartar(UUID id, String operacion) {
        LOG.warning(() -> "Sin Dataverse: se descarta " + operacion + ". solicitudId=" + id);
    }
}
