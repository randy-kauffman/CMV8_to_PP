package com.banco.cmv8.application;

import com.banco.cmv8.domain.Adjunto;
import com.banco.cmv8.domain.ClaveAdjunto;
import com.banco.cmv8.domain.ContextoLlamador;
import com.banco.cmv8.domain.EstadoIngesta;
import com.banco.cmv8.domain.MensajeIngesta;
import com.banco.cmv8.domain.RegistroIngesta;
import com.banco.cmv8.domain.port.AgendaDepuracion;
import com.banco.cmv8.domain.port.ColaIngesta;
import com.banco.cmv8.domain.port.RegistroIngestas;
import com.banco.cmv8.domain.port.RepositorioAdjuntos;

import java.util.Map;
import java.util.logging.Logger;

/**
 * Operación Reanudar (ADR-003 §4.4): el usuario corrigió los datos de un adjunto Observada. Explícita porque
 * el Watchdog ya no mira Dataverse. Relee la fila suplantando (una request) para tomar los valores corregidos,
 * vuelve a Subido, cancela el vencimiento de la observación y reencola. También sirve para reintentar un Error.
 */
public class Reanudar {

    private final RepositorioAdjuntos repositorio;
    private final RegistroIngestas registros;
    private final AgendaDepuracion agenda;
    private final VinculoUsuario vinculo;

    public Reanudar(RepositorioAdjuntos repositorio, RegistroIngestas registros, AgendaDepuracion agenda,
                    VinculoUsuario vinculo) {
        this.repositorio = repositorio;
        this.registros = registros;
        this.agenda = agenda;
        this.vinculo = vinculo;
    }

    public void ejecutar(ClaveAdjunto clave, ContextoLlamador llamador, ColaIngesta cola, Logger log) {
        vinculo.exigirIdentidad(llamador);
        Adjunto adjunto = repositorio.obtenerSuplantando(clave, llamador)
                .orElseThrow(() -> new SolicitudNoEncontradaException("El adjunto no existe"));
        if (adjunto.estado() != EstadoIngesta.OBSERVADA && adjunto.estado() != EstadoIngesta.ERROR) {
            throw new EstadoInvalidoException("Solo se reanuda un adjunto Observada o en Error; está en "
                    + adjunto.estado());
        }
        RegistroIngesta registro = registros.obtener(clave)
                .orElseThrow(() -> new SolicitudNoEncontradaException("El adjunto no tiene registro operativo"));
        if (adjunto.rutaBlob() == null || !clave.esSuRuta(adjunto.rutaBlob())) {
            throw new SolicitudNoEncontradaException("El adjunto no tiene archivo disponible");
        }
        registro.depuracion().ifPresent(dia -> agenda.cancelar(dia, clave));
        // Los valores corregidos viajan en el registro operativo: el worker no vuelve a leer Dataverse.
        registros.guardar(registro.conValores(adjunto.valores()).conTipo(adjunto.tipoDocumentoId())
                .conEstado(EstadoIngesta.SUBIDO).conMensaje(null).conFechaDepuracion(null));
        repositorio.actualizar(clave, Map.of(Adjunto.COL_ESTADO, EstadoIngesta.SUBIDO));
        cola.encolar(new MensajeIngesta(clave, registro.contenedor(), adjunto.rutaBlob(), "reanudar"));
        log.info(() -> "Adjunto reanudado por el usuario. adjunto=" + clave + " desde=" + adjunto.estado()
                + " llamador=" + llamador);
    }
}
