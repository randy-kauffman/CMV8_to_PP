package com.banco.cmv8.application;

import com.banco.cmv8.application.catalogo.CatalogoEnCache;
import com.banco.cmv8.domain.Adjunto;
import com.banco.cmv8.domain.ClaveAdjunto;
import com.banco.cmv8.domain.ContextoLlamador;
import com.banco.cmv8.domain.EstadoIngesta;
import com.banco.cmv8.domain.MensajeIngesta;
import com.banco.cmv8.domain.RegistroIngesta;
import com.banco.cmv8.domain.catalogo.TipoDocumento;
import com.banco.cmv8.domain.port.ColaIngesta;
import com.banco.cmv8.domain.port.RegistroIngestas;
import com.banco.cmv8.domain.port.RepositorioAdjuntos;

import java.util.logging.Logger;

/**
 * Operación Archivar (ADR-003 §4.3): el evento de negocio ocurrió, o alguien decidió promover un solo-blob a
 * Content Manager. Autoriza suplantando (una request), exige que el tipo ACTUAL del adjunto tenga ItemType
 * (un tipo local se promueve cambiándole primero el tipo en la app) y encola. Hacia arriba sí, hacia abajo no.
 */
public class ArchivarAlmacenado {

    private final RepositorioAdjuntos repositorio;
    private final RegistroIngestas registros;
    private final CatalogoEnCache catalogo;
    private final VinculoUsuario vinculo;

    public ArchivarAlmacenado(RepositorioAdjuntos repositorio, RegistroIngestas registros, CatalogoEnCache catalogo,
                              VinculoUsuario vinculo) {
        this.repositorio = repositorio;
        this.registros = registros;
        this.catalogo = catalogo;
        this.vinculo = vinculo;
    }

    public void ejecutar(ClaveAdjunto clave, ContextoLlamador llamador, ColaIngesta cola, Logger log) {
        vinculo.exigirIdentidad(llamador);
        Adjunto adjunto = repositorio.obtenerSuplantando(clave, llamador)
                .orElseThrow(() -> new SolicitudNoEncontradaException("El adjunto no existe"));
        if (adjunto.estado() != EstadoIngesta.ALMACENADO) {
            throw new EstadoInvalidoException("Solo se puede archivar un adjunto en estado Almacenado; está en "
                    + adjunto.estado());
        }
        TipoDocumento tipo = catalogo.tipo(adjunto.tipoDocumentoId())
                .orElseThrow(() -> new EstadoInvalidoException("El adjunto no tiene un tipo de documento válido"));
        if (!tipo.vaAContentManager()) {
            throw new EstadoInvalidoException("El tipo " + tipo.nombre() + " no va a Content Manager; "
                    + "cambiá el tipo del adjunto a uno descubierto antes de archivar");
        }
        RegistroIngesta registro = registros.obtener(clave)
                .orElseThrow(() -> new SolicitudNoEncontradaException("El adjunto no tiene registro operativo"));
        if (adjunto.rutaBlob() == null || !clave.esSuRuta(adjunto.rutaBlob())) {
            throw new SolicitudNoEncontradaException("El adjunto no tiene archivo disponible");
        }
        // El tipo pudo cambiar en Dataverse (promoción): el registro operativo toma el tipo actual.
        registros.guardar(registro.conTipo(adjunto.tipoDocumentoId()));
        cola.encolar(new MensajeIngesta(clave, registro.contenedor(), adjunto.rutaBlob(), "archivar"));
        log.info(() -> "Archivado solicitado. adjunto=" + clave + " tipo=" + tipo.codigo() + " llamador=" + llamador);
    }
}
