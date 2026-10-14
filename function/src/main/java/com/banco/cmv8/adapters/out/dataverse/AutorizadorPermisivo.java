package com.banco.cmv8.adapters.out.dataverse;

import com.banco.cmv8.domain.ContextoLlamador;
import com.banco.cmv8.domain.port.AutorizadorSolicitud;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * AUTORIZACION_MODO=ninguna. SOLO para el spike S1 (probar la carga sin Dataverse).
 * Nunca usar en un entorno con datos reales.
 */
public class AutorizadorPermisivo implements AutorizadorSolicitud {

    private static final Logger LOG = Logger.getLogger(AutorizadorPermisivo.class.getName());

    @Override
    public boolean puedeEscribir(UUID solicitudId, ContextoLlamador llamador) {
        LOG.warning(() -> "AUTORIZACION_MODO=ninguna: escritura permitida sin verificar. solicitudId=" + solicitudId);
        return true;
    }

    @Override
    public boolean puedeLeer(UUID solicitudId, ContextoLlamador llamador) {
        LOG.warning(() -> "AUTORIZACION_MODO=ninguna: lectura permitida sin verificar. solicitudId=" + solicitudId);
        return true;
    }
}
