package com.banco.cmv8.application;

import com.banco.cmv8.domain.ContextoLlamador;
import com.banco.cmv8.domain.EstadoSolicitud;
import com.banco.cmv8.domain.PoliticaSas;
import com.banco.cmv8.domain.SasEmitida;
import com.banco.cmv8.domain.SolicitudIngesta;
import com.banco.cmv8.domain.TokenUsuario;
import com.banco.cmv8.domain.ValidacionException;
import com.banco.cmv8.domain.port.AlmacenAdjuntos;
import com.banco.cmv8.domain.port.AutorizadorSolicitud;
import com.banco.cmv8.domain.port.EmisorSas;
import com.banco.cmv8.domain.port.RepositorioSolicitudes;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Caso de uso: SAS de lectura del blob de una solicitud que todavía no se archivó.
 * La lectura de documentos ya archivados (staging desde Content Manager) queda pendiente.
 */
public class EmitirSasLectura {

    /** {@code tokenStorage*} solo con SAS ligada al usuario (S1b); si no, null. Nunca loguear url ni token. */
    public record Resultado(String downloadUrl, OffsetDateTime expiraUtc, String tokenStorage,
                            OffsetDateTime tokenStorageExpiraUtc) {

        public Resultado(String downloadUrl, OffsetDateTime expiraUtc) {
            this(downloadUrl, expiraUtc, null, null);
        }

        @Override
        public String toString() {
            return "Resultado[expiraUtc=" + expiraUtc + ", tokenStorage=" + (tokenStorage == null ? "-" : "***") + "]";
        }
    }

    private final AutorizadorSolicitud autorizador;
    private final RepositorioSolicitudes repositorio;
    private final AlmacenAdjuntos almacen;
    private final EmisorSas emisor;
    private final Duration vigencia;
    private final VinculoUsuario vinculo;

    public EmitirSasLectura(AutorizadorSolicitud autorizador, RepositorioSolicitudes repositorio,
                            AlmacenAdjuntos almacen, EmisorSas emisor, Duration vigencia) {
        this(autorizador, repositorio, almacen, emisor, vigencia, VinculoUsuario.desactivado());
    }

    public EmitirSasLectura(AutorizadorSolicitud autorizador, RepositorioSolicitudes repositorio,
                            AlmacenAdjuntos almacen, EmisorSas emisor, Duration vigencia, VinculoUsuario vinculo) {
        this.vinculo = vinculo;
        this.autorizador = autorizador;
        this.repositorio = repositorio;
        this.almacen = almacen;
        this.emisor = emisor;
        this.vigencia = vigencia;
    }

    public Resultado ejecutar(UUID solicitudId, ContextoLlamador llamador, Logger log) {
        if (solicitudId == null) {
            throw new ValidacionException("El solicitudId es obligatorio");
        }
        vinculo.exigirIdentidad(llamador);
        if (!autorizador.puedeLeer(solicitudId, llamador)) {
            log.warning(() -> "SAS de lectura denegada. solicitudId=" + solicitudId + " llamador=" + llamador);
            throw new AccesoDenegadoException("No tiene permiso de lectura sobre la solicitud");
        }
        SolicitudIngesta solicitud = repositorio.obtener(solicitudId)
                .orElseThrow(() -> new SolicitudNoEncontradaException("La solicitud no existe"));
        if (solicitud.estado() == EstadoSolicitud.ARCHIVADO) {
            // TODO: traer el documento de Content Manager a un contenedor de staging (plan, sección 2).
            throw new EstadoInvalidoException("El documento ya está archivado en Content Manager");
        }
        if (solicitud.estado() == EstadoSolicitud.RECHAZADO) {
            throw new EstadoInvalidoException("El archivo fue rechazado por el antivirus");
        }
        String ruta = solicitud.rutaBlob();
        if (ruta == null || ruta.isBlank() || !PoliticaSas.rutaPerteneceA(ruta, solicitudId)
                || !almacen.existe(ruta)) {
            throw new SolicitudNoEncontradaException("La solicitud no tiene archivo disponible");
        }
        Optional<TokenUsuario> token = vinculo.tokenStorage(llamador);
        SasEmitida sas = emisor.emitirLectura(ruta, vigencia, llamador);
        log.info(() -> "SAS de lectura emitida. solicitudId=" + solicitudId + " rutaBlob=" + ruta
                + " ligadaUsuario=" + token.isPresent());
        return new Resultado(sas.url(), sas.expira(), token.map(TokenUsuario::valor).orElse(null),
                token.map(TokenUsuario::expira).orElse(null));
    }
}
