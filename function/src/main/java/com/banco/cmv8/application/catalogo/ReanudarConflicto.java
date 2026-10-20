package com.banco.cmv8.application.catalogo;

import com.banco.cmv8.application.AccesoDenegadoException;
import com.banco.cmv8.application.SolicitudNoEncontradaException;
import com.banco.cmv8.domain.ContextoLlamador;
import com.banco.cmv8.domain.EstadoIngesta;
import com.banco.cmv8.domain.MensajeIngesta;
import com.banco.cmv8.domain.RegistroIngesta;
import com.banco.cmv8.domain.catalogo.ConflictoRegistrado;
import com.banco.cmv8.domain.port.CatalogoTipos;
import com.banco.cmv8.domain.port.ColaIngesta;
import com.banco.cmv8.domain.port.RegistroIngestas;

import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Operación ReanudarConflicto (ADR-003 §4.4): el administrador resolvió el conflicto; se invalida el caché del
 * catálogo, se marca resuelto, se buscan en la Azure Table los adjuntos detenidos por esa clave (cero requests a
 * Dataverse) y se reencolan. Si al reprocesar falta el valor en la fila, el worker los manda a Observada:
 * catálogo → administrador → datos → usuario.
 */
public class ReanudarConflicto {

    private final CatalogoTipos catalogo;
    private final CatalogoEnCache cache;
    private final RegistroIngestas registros;

    public ReanudarConflicto(CatalogoTipos catalogo, CatalogoEnCache cache, RegistroIngestas registros) {
        this.catalogo = catalogo;
        this.cache = cache;
        this.registros = registros;
    }

    public int ejecutar(UUID conflictoId, ContextoLlamador llamador, ColaIngesta cola, Logger log) {
        if (!catalogo.puedeAdministrar(llamador)) {
            throw new AccesoDenegadoException("Solo el administrador documental puede resolver conflictos");
        }
        ConflictoRegistrado conflicto = catalogo.obtenerConflicto(conflictoId)
                .orElseThrow(() -> new SolicitudNoEncontradaException("El conflicto no existe"));
        cache.invalidar();
        if (conflicto.abierto()) {
            catalogo.marcarConflictoResuelto(conflictoId);
        }
        registros.borrarConflicto(conflicto.clave());
        List<RegistroIngesta> detenidos = registros.listarPorConflicto(conflicto.clave());
        int reencolados = 0;
        for (RegistroIngesta r : detenidos) {
            if (r.estado() != EstadoIngesta.DETENIDA) {
                continue;
            }
            registros.guardar(r.conEstado(EstadoIngesta.SUBIDO).conConflicto(null).conMensaje(null));
            cola.encolar(new MensajeIngesta(r.clave(), r.contenedor(), r.ruta(), "conflicto"));
            reencolados++;
        }
        final int total = reencolados;
        log.info(() -> "Conflicto resuelto. conflictoId=" + conflictoId + " clase=" + conflicto.clase()
                + " reencolados=" + total + " llamador=" + llamador);
        return reencolados;
    }
}
