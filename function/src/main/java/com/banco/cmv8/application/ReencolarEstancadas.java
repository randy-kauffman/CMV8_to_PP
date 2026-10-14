package com.banco.cmv8.application;

import com.banco.cmv8.domain.EstadoSolicitud;
import com.banco.cmv8.domain.MensajeIngesta;
import com.banco.cmv8.domain.SolicitudIngesta;
import com.banco.cmv8.domain.port.ColaIngesta;
import com.banco.cmv8.domain.port.RepositorioSolicitudes;

import java.time.Duration;
import java.util.List;
import java.util.logging.Logger;

/**
 * Watchdog: reencola las solicitudes que quedaron en SUBIDO sin resultado del antivirus
 * (evento perdido o función caída). El worker es idempotente, así que reencolar de más es seguro.
 */
public class ReencolarEstancadas {

    private final RepositorioSolicitudes repositorio;
    private final Duration antiguedad;

    public ReencolarEstancadas(RepositorioSolicitudes repositorio, Duration antiguedad) {
        this.repositorio = repositorio;
        this.antiguedad = antiguedad;
    }

    public int ejecutar(ColaIngesta cola, Logger log) {
        List<SolicitudIngesta> estancadas = repositorio.listarEstancadas(EstadoSolicitud.SUBIDO, antiguedad);
        int encoladas = 0;
        for (SolicitudIngesta s : estancadas) {
            if (s.rutaBlob() == null || s.rutaBlob().isBlank()) {
                log.warning(() -> "Solicitud estancada sin rutaBlob; se omite. solicitudId=" + s.id());
                continue;
            }
            cola.encolar(new MensajeIngesta(s.id(), s.rutaBlob(), "watchdog"));
            encoladas++;
        }
        final int total = encoladas;
        log.info(() -> "Watchdog: " + total + " solicitudes reencoladas de " + estancadas.size() + " estancadas");
        return encoladas;
    }
}
