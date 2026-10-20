package com.banco.cmv8.application;

import com.banco.cmv8.application.catalogo.CatalogoEnCache;
import com.banco.cmv8.domain.ClaveAdjunto;
import com.banco.cmv8.domain.ContextoLlamador;
import com.banco.cmv8.domain.RegistroIngesta;
import com.banco.cmv8.domain.ValidacionException;
import com.banco.cmv8.domain.port.AgendaDepuracion;
import com.banco.cmv8.domain.port.AlmacenAdjuntos;
import com.banco.cmv8.domain.port.CatalogoTipos;
import com.banco.cmv8.domain.port.RegistroIngestas;
import com.banco.cmv8.domain.port.RepositorioAdjuntos;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Operación de administración (ADR-003 §4.5): extender la retención de un adjunto, o congelarla (fecha nula =
 * retención legal). Sobre un tipo inmutable solo se extiende, porque la política bloqueada no se acorta.
 */
public class ExtenderRetencion {

    private final RepositorioAdjuntos repositorio;
    private final RegistroIngestas registros;
    private final AgendaDepuracion agenda;
    private final AlmacenAdjuntos almacen;
    private final CatalogoEnCache catalogo;
    private final CatalogoTipos catalogoTipos;
    private final boolean wormHabilitado;
    private final boolean wormBloquear;
    private final Clock reloj;

    public ExtenderRetencion(RepositorioAdjuntos repositorio, RegistroIngestas registros, AgendaDepuracion agenda,
                             AlmacenAdjuntos almacen, CatalogoEnCache catalogo, CatalogoTipos catalogoTipos,
                             boolean wormHabilitado, boolean wormBloquear, Clock reloj) {
        this.catalogoTipos = catalogoTipos;
        this.repositorio = repositorio;
        this.registros = registros;
        this.agenda = agenda;
        this.almacen = almacen;
        this.catalogo = catalogo;
        this.wormHabilitado = wormHabilitado;
        this.wormBloquear = wormBloquear;
        this.reloj = reloj;
    }

    /** @param nuevaFecha vacía = congelar (permanente) */
    public void ejecutar(ClaveAdjunto clave, Optional<LocalDate> nuevaFecha, ContextoLlamador llamador, Logger log) {
        // Operación de administración (ADR-003 §5): rol de administrador documental y, además, lectura suplantada.
        if (!catalogoTipos.puedeAdministrar(llamador)) {
            throw new AccesoDenegadoException("Solo el administrador documental puede cambiar la retención");
        }
        repositorio.obtenerSuplantando(clave, llamador)
                .orElseThrow(() -> new SolicitudNoEncontradaException("El adjunto no existe"));
        RegistroIngesta r = registros.obtener(clave)
                .orElseThrow(() -> new SolicitudNoEncontradaException("El adjunto no tiene registro operativo"));
        boolean inmutable = catalogo.tipo(r.tipoDocumentoId()).map(t -> t.inmutable()).orElse(false) && wormHabilitado;
        if (nuevaFecha.isPresent()) {
            if (nuevaFecha.get().isBefore(LocalDate.now(reloj))) {
                throw new ValidacionException("La fecha de retención no puede ser pasada");
            }
            if (inmutable && r.fechaDepuracion() != null && nuevaFecha.get().isBefore(r.fechaDepuracion())) {
                throw new ValidacionException("Un adjunto inmutable solo admite extender la retención, no acortarla");
            }
        }
        // Pendiente (ADR-003 §13): retención legal como legal hold por versión en Storage para tipos inmutables;
        // hoy congelar = fecha nula en la agenda (el timer no lo toca) y, si es inmutable, la política vigente sigue.
        r.depuracion().ifPresent(dia -> agenda.cancelar(dia, clave));
        nuevaFecha.ifPresent(dia -> agenda.programar(dia, clave));
        if (inmutable && nuevaFecha.isPresent()) {
            almacen.ponerInmutable(r.contenedor(), r.ruta(),
                    nuevaFecha.get().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(), wormBloquear);
        }
        registros.guardar(r.conFechaDepuracion(nuevaFecha.orElse(null)));
        log.info(() -> "Retención " + (nuevaFecha.isPresent() ? "extendida hasta " + nuevaFecha.get() : "congelada")
                + ". adjunto=" + clave + " llamador=" + llamador);
    }
}
