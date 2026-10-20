package com.banco.cmv8.application;

import com.banco.cmv8.domain.Adjunto;
import com.banco.cmv8.domain.ClaveAdjunto;
import com.banco.cmv8.domain.ContextoLlamador;
import com.banco.cmv8.domain.EstadoIngesta;
import com.banco.cmv8.domain.RegistroIngesta;
import com.banco.cmv8.domain.SasEmitida;
import com.banco.cmv8.domain.TokenUsuario;
import com.banco.cmv8.domain.port.AlmacenAdjuntos;
import com.banco.cmv8.domain.port.EmisorSas;
import com.banco.cmv8.domain.port.RegistroIngestas;
import com.banco.cmv8.domain.port.RepositorioAdjuntos;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * SAS de lectura del blob de un adjunto, esté en ingesta (en vuelo) o en almacenados (retenido, solo-blob,
 * diferido). La autorización es la lectura suplantada de la fila. Un Archivado sin blob ya solo está en
 * Content Manager (la lectura desde CM queda pendiente, como en v1).
 */
public class EmitirSasLectura {

    public record Resultado(String downloadUrl, OffsetDateTime expiraUtc, String tokenStorage,
                            OffsetDateTime tokenStorageExpiraUtc) {
        @Override
        public String toString() {
            return "Resultado[expiraUtc=" + expiraUtc + ", tokenStorage=" + (tokenStorage == null ? "-" : "***") + "]";
        }
    }

    private final RepositorioAdjuntos repositorio;
    private final RegistroIngestas registros;
    private final AlmacenAdjuntos almacen;
    private final EmisorSas emisor;
    private final VinculoUsuario vinculo;
    private final String contenedorIngesta;
    private final String contenedorAlmacenados;
    private final Duration vigencia;

    public EmitirSasLectura(RepositorioAdjuntos repositorio, RegistroIngestas registros, AlmacenAdjuntos almacen,
                            EmisorSas emisor, VinculoUsuario vinculo, String contenedorIngesta,
                            String contenedorAlmacenados, Duration vigencia) {
        this.repositorio = repositorio;
        this.registros = registros;
        this.almacen = almacen;
        this.emisor = emisor;
        this.vinculo = vinculo;
        this.contenedorIngesta = contenedorIngesta;
        this.contenedorAlmacenados = contenedorAlmacenados;
        this.vigencia = vigencia;
    }

    public Resultado ejecutar(ClaveAdjunto clave, ContextoLlamador llamador, Logger log) {
        vinculo.exigirIdentidad(llamador);
        Adjunto adjunto = repositorio.obtenerSuplantando(clave, llamador)
                .orElseThrow(() -> new SolicitudNoEncontradaException("El adjunto no existe"));
        if (adjunto.estado() == EstadoIngesta.RECHAZADO || adjunto.estado() == EstadoIngesta.DEPURADO) {
            throw new EstadoInvalidoException("El archivo ya no está disponible (" + adjunto.estado() + ")");
        }
        String ruta = adjunto.rutaBlob();
        if (ruta == null || ruta.isBlank()) {
            if (adjunto.estado() == EstadoIngesta.ARCHIVADO) {
                // TODO: traer el documento de Content Manager a un contenedor de staging (plan, sección 2).
                throw new EstadoInvalidoException("El documento ya está solo en Content Manager");
            }
            throw new SolicitudNoEncontradaException("El adjunto no tiene archivo disponible");
        }
        if (!clave.esSuRuta(ruta)) {
            throw new SolicitudNoEncontradaException("El adjunto no tiene archivo disponible");
        }
        String contenedor = registros.obtener(clave).map(RegistroIngesta::contenedor)
                .orElse(adjunto.estado() == EstadoIngesta.ALMACENADO || adjunto.estado() == EstadoIngesta.ARCHIVADO
                        ? contenedorAlmacenados : contenedorIngesta);
        if (!almacen.existe(contenedor, ruta)) {
            throw new SolicitudNoEncontradaException("El adjunto no tiene archivo disponible");
        }
        Optional<TokenUsuario> token = vinculo.tokenStorage(llamador);
        SasEmitida sas = emisor.emitirLectura(contenedor, ruta, vigencia, llamador);
        log.info(() -> "SAS de lectura emitida. adjunto=" + clave + " contenedor=" + contenedor
                + " ligadaUsuario=" + token.isPresent());
        return new Resultado(sas.url(), sas.expira(), token.map(TokenUsuario::valor).orElse(null),
                token.map(TokenUsuario::expira).orElse(null));
    }
}
