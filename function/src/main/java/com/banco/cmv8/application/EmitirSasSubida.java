package com.banco.cmv8.application;

import com.banco.cmv8.domain.ContextoLlamador;
import com.banco.cmv8.domain.EstadoSolicitud;
import com.banco.cmv8.domain.PoliticaSas;
import com.banco.cmv8.domain.SasEmitida;
import com.banco.cmv8.domain.SolicitudIngesta;
import com.banco.cmv8.domain.TokenUsuario;
import com.banco.cmv8.domain.ValidacionException;
import com.banco.cmv8.domain.port.AutorizadorSolicitud;
import com.banco.cmv8.domain.port.EmisorSas;
import com.banco.cmv8.domain.port.RepositorioSolicitudes;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Caso de uso: emitir la SAS de subida de UN blob, después de que Dataverse confirma
 * que el llamador puede escribir la fila de la solicitud.
 */
public class EmitirSasSubida {

    /** Tamaño de bloque por defecto recomendado al PCF (4 MiB). Configurable con SAS_BLOQUE_MB. */
    public static final int BLOQUE_BYTES = 4 * 1024 * 1024;

    public record Peticion(UUID solicitudId, String nombreArchivo, Long tamanoBytes, String contentType) {
    }

    /**
     * {@code tokenStorage} y {@code tokenStorageExpiraUtc} solo vienen con SAS ligada al usuario (S1b); si no, null.
     * uploadUrl y tokenStorage son secretos: el toString no los incluye.
     */
    public record Resultado(String uploadUrl, String rutaBlob, OffsetDateTime expiraUtc, int bloqueBytes,
                            String versionServicio, String tokenStorage, OffsetDateTime tokenStorageExpiraUtc) {

        public Resultado(String uploadUrl, String rutaBlob, OffsetDateTime expiraUtc, int bloqueBytes,
                         String versionServicio) {
            this(uploadUrl, rutaBlob, expiraUtc, bloqueBytes, versionServicio, null, null);
        }

        @Override
        public String toString() {
            return "Resultado[rutaBlob=" + rutaBlob + ", expiraUtc=" + expiraUtc
                    + ", tokenStorage=" + (tokenStorage == null ? "-" : "***") + "]";
        }
    }

    private final PoliticaSas politica;
    private final AutorizadorSolicitud autorizador;
    private final RepositorioSolicitudes repositorio;
    private final EmisorSas emisor;
    private final Duration vigencia;
    private final int bloqueBytes;
    private final VinculoUsuario vinculo;

    public EmitirSasSubida(PoliticaSas politica, AutorizadorSolicitud autorizador, RepositorioSolicitudes repositorio,
                           EmisorSas emisor, Duration vigencia) {
        this(politica, autorizador, repositorio, emisor, vigencia, BLOQUE_BYTES, VinculoUsuario.desactivado());
    }

    public EmitirSasSubida(PoliticaSas politica, AutorizadorSolicitud autorizador, RepositorioSolicitudes repositorio,
                           EmisorSas emisor, Duration vigencia, int bloqueBytes) {
        this(politica, autorizador, repositorio, emisor, vigencia, bloqueBytes, VinculoUsuario.desactivado());
    }

    public EmitirSasSubida(PoliticaSas politica, AutorizadorSolicitud autorizador, RepositorioSolicitudes repositorio,
                           EmisorSas emisor, Duration vigencia, int bloqueBytes, VinculoUsuario vinculo) {
        this.vinculo = vinculo;
        this.politica = politica;
        this.autorizador = autorizador;
        this.repositorio = repositorio;
        this.emisor = emisor;
        this.vigencia = vigencia;
        this.bloqueBytes = bloqueBytes;
    }

    public Resultado ejecutar(Peticion peticion, ContextoLlamador llamador, Logger log) {
        if (peticion == null || peticion.solicitudId() == null) {
            throw new ValidacionException("El solicitudId es obligatorio");
        }
        UUID id = peticion.solicitudId();
        politica.validarTamano(peticion.tamanoBytes());
        String ruta = politica.construirRuta(id, peticion.nombreArchivo());
        vinculo.exigirIdentidad(llamador);

        if (!autorizador.puedeEscribir(id, llamador)) {
            log.warning(() -> "SAS de subida denegada. solicitudId=" + id + " llamador=" + llamador);
            throw new AccesoDenegadoException("No tiene permiso de escritura sobre la solicitud");
        }

        SolicitudIngesta solicitud = repositorio.obtener(id)
                .orElseThrow(() -> new SolicitudNoEncontradaException("La solicitud no existe"));
        if (solicitud.estado() != EstadoSolicitud.CREADA && solicitud.estado() != EstadoSolicitud.ERROR) {
            throw new EstadoInvalidoException("La solicitud está en estado " + solicitud.estado()
                    + " y no admite una nueva subida");
        }

        // OBO antes de registrar la ruta: si Entra no entrega el token, la fila no cambia.
        Optional<TokenUsuario> token = vinculo.tokenStorage(llamador);
        repositorio.registrarRutaBlob(id, ruta);
        SasEmitida sas = emisor.emitirSubida(ruta, vigencia, llamador);
        log.info(() -> "SAS de subida emitida. solicitudId=" + id + " rutaBlob=" + ruta + " expira=" + sas.expira()
                + " ligadaUsuario=" + token.isPresent()
                + token.map(t -> " tokenStorageExpira=" + t.expira()).orElse("") + " llamador=" + llamador);
        return new Resultado(sas.url(), ruta, sas.expira(), bloqueBytes, sas.versionServicio(),
                token.map(TokenUsuario::valor).orElse(null), token.map(TokenUsuario::expira).orElse(null));
    }
}
