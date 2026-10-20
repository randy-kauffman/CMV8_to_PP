package com.banco.cmv8.application;

import com.banco.cmv8.domain.Adjunto;
import com.banco.cmv8.domain.ClaveAdjunto;
import com.banco.cmv8.domain.EstadoIngesta;
import com.banco.cmv8.domain.RegistroIngesta;
import com.banco.cmv8.domain.port.AgendaDepuracion;
import com.banco.cmv8.domain.port.AlmacenAdjuntos;
import com.banco.cmv8.domain.port.RegistroIngestas;
import com.banco.cmv8.domain.port.RepositorioAdjuntos;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Timer diario de depuración (ADR-003 §4.5): la fecha manda, nuestro código ejecuta. Procesa la agenda de hoy
 * y de los días que hayan quedado pendientes. Si hay un humano pendiente (Observada vigente, Detenida,
 * Procesando) no borra y corre la fecha siete días. Idempotente: si el blob ya no está, igual cierra Dataverse.
 * Un WORM no vencido lo rechaza Storage: se reprograma.
 */
public class Depurar {

    public static final int DIAS_POSTERGACION = 7;
    private static final int DIAS_MAXIMOS_ATRAS = 60;

    private final AgendaDepuracion agenda;
    private final RegistroIngestas registros;
    private final RepositorioAdjuntos repositorio;
    private final AlmacenAdjuntos almacen;
    private final Clock reloj;

    public Depurar(AgendaDepuracion agenda, RegistroIngestas registros, RepositorioAdjuntos repositorio,
                   AlmacenAdjuntos almacen, Clock reloj) {
        this.agenda = agenda;
        this.registros = registros;
        this.repositorio = repositorio;
        this.almacen = almacen;
        this.reloj = reloj;
    }

    public int ejecutar(Logger log) {
        LocalDate hoy = LocalDate.now(reloj);
        LocalDate desde = agenda.ultimoDiaProcesado().map(d -> d.plusDays(1)).orElse(hoy);
        if (desde.isBefore(hoy.minusDays(DIAS_MAXIMOS_ATRAS))) {
            desde = hoy.minusDays(DIAS_MAXIMOS_ATRAS);
        }
        int depurados = 0;
        boolean avanzarPuntero = true;
        for (LocalDate dia = desde; !dia.isAfter(hoy); dia = dia.plusDays(1)) {
            boolean fallo = false;
            for (ClaveAdjunto clave : agenda.pendientes(dia)) {
                try {
                    if (depurarUno(dia, clave, hoy, log)) {
                        depurados++;
                    }
                } catch (RuntimeException e) {
                    fallo = true;
                    log.log(Level.WARNING, "No se pudo depurar; se reintenta mañana. adjunto=" + clave, e);
                }
            }
            // Un día con fallas no se marca procesado (ni los siguientes): mañana se relee desde ese día; lo ya
            // depurado no reaparece porque su entrada se canceló.
            avanzarPuntero = avanzarPuntero && !fallo;
            if (avanzarPuntero) {
                agenda.marcarDiaProcesado(dia);
            }
        }
        final int total = depurados;
        log.info(() -> "Depuración: " + total + " adjuntos depurados; agenda procesada hasta " + hoy);
        return depurados;
    }

    private boolean depurarUno(LocalDate dia, ClaveAdjunto clave, LocalDate hoy, Logger log) {
        Optional<RegistroIngesta> encontrado = registros.obtener(clave);
        if (encontrado.isEmpty()) {
            agenda.cancelar(dia, clave);
            return false;
        }
        RegistroIngesta r = encontrado.get();
        if (r.fechaDepuracion() == null || r.fechaDepuracion().isAfter(hoy)) {
            // La fecha se extendió o se congeló después de agendar: la entrada vieja sobra.
            agenda.cancelar(dia, clave);
            return false;
        }
        if (r.estado() == EstadoIngesta.DETENIDA || r.estado() == EstadoIngesta.PROCESANDO) {
            postergar(dia, clave, r, hoy, log);
            return false;
        }
        if (r.estado() == EstadoIngesta.OBSERVADA) {
            // Venció el plazo de corrección: Rechazado "vencida sin corrección".
            return cerrar(dia, clave, r, EstadoIngesta.RECHAZADO, "Vencida sin corrección", log);
        }
        if (r.estado() == EstadoIngesta.ARCHIVADO) {
            // El documento sigue en Content Manager: solo se vacía la ruta.
            return cerrar(dia, clave, r, EstadoIngesta.ARCHIVADO, null, log);
        }
        if (r.estado() == EstadoIngesta.ALMACENADO) {
            return cerrar(dia, clave, r, EstadoIngesta.DEPURADO, null, log);
        }
        // Creada, Subido, Error, Rechazado, Depurado con fecha: restos; se borra el blob si existe y se cierra la agenda.
        borrarSiExiste(r, log);
        agenda.cancelar(dia, clave);
        return false;
    }

    private boolean cerrar(LocalDate dia, ClaveAdjunto clave, RegistroIngesta r, EstadoIngesta estadoFinal,
                           String mensaje, Logger log) {
        if (!borrarSiExiste(r, log)) {
            // Storage rechazó el borrado (política de inmutabilidad vigente, por ejemplo): todavía no.
            postergar(dia, clave, r, LocalDate.now(reloj), log);
            return false;
        }
        Map<String, Object> cambios = new LinkedHashMap<>();
        cambios.put(Adjunto.COL_ESTADO, estadoFinal);
        cambios.put(Adjunto.COL_RUTA, null);
        if (mensaje != null) {
            cambios.put(Adjunto.COL_MENSAJE, mensaje);
        }
        try {
            repositorio.actualizar(clave, cambios);
        } catch (SolicitudNoEncontradaException e) {
            log.info(() -> "La fila ya no existe en Dataverse; se cierra solo el registro operativo. adjunto=" + clave);
        }
        registros.guardar(r.conEstado(estadoFinal).conMensaje(mensaje == null ? r.mensaje() : mensaje)
                .conFechaDepuracion(null));
        agenda.cancelar(dia, clave);
        log.info(() -> "Adjunto depurado. adjunto=" + clave + " estadoFinal=" + estadoFinal + " contenedor=" + r.contenedor());
        return true;
    }

    /** @return true si el blob ya no existe (borrado ahora o antes). */
    private boolean borrarSiExiste(RegistroIngesta r, Logger log) {
        if (!almacen.existe(r.contenedor(), r.ruta())) {
            return true;
        }
        try {
            almacen.borrar(r.contenedor(), r.ruta());
            return true;
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Storage no permitió borrar el blob (¿política de inmutabilidad vigente?). adjunto="
                    + r.clave(), e);
            return false;
        }
    }

    private void postergar(LocalDate dia, ClaveAdjunto clave, RegistroIngesta r, LocalDate hoy, Logger log) {
        LocalDate nueva = hoy.plusDays(DIAS_POSTERGACION);
        agenda.cancelar(dia, clave);
        agenda.programar(nueva, clave);
        registros.guardar(r.conFechaDepuracion(nueva));
        log.info(() -> "Depuración postergada hasta " + nueva + " (estado " + r.estado() + "). adjunto=" + clave);
    }
}
