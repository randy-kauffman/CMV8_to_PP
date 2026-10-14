package com.banco.cmv8.application;

import com.banco.cmv8.domain.ContextoLlamador;
import com.banco.cmv8.domain.EstadoSolicitud;
import com.banco.cmv8.domain.PoliticaSas;
import com.banco.cmv8.domain.SasEmitida;
import com.banco.cmv8.domain.SolicitudIngesta;
import com.banco.cmv8.domain.ValidacionException;
import com.banco.cmv8.domain.port.AutorizadorSolicitud;
import com.banco.cmv8.domain.port.EmisorSas;
import com.banco.cmv8.domain.port.RepositorioSolicitudes;

import java.time.Duration;
import java.time.OffsetDateTime;
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

    public record Resultado(String uploadUrl, String rutaBlob, OffsetDateTime expiraUtc, int bloqueBytes,
                            String versionServicio) {

        @Override
        public String toString() {
            return "Resultado[rutaBlob=" + rutaBlob + ", expiraUtc=" + expiraUtc + "]";
        }
    }

    private final PoliticaSas politica;
    private final AutorizadorSolicitud autorizador;
    private final RepositorioSolicitudes repositorio;
    private final EmisorSas emisor;
    private final Duration vigencia;
    private final int bloqueBytes;

    public EmitirSasSubida(PoliticaSas politica, AutorizadorSolicitud autorizador, RepositorioSolicitudes repositorio,
                           EmisorSas emisor, Duration vigencia) {
        this(politica, autorizador, repositorio, emisor, vigencia, BLOQUE_BYTES);
    }

    public EmitirSasSubida(PoliticaSas politica, AutorizadorSolicitud autorizador, RepositorioSolicitudes repositorio,
                           EmisorSas emisor, Duration vigencia, int bloqueBytes) {
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

        repositorio.registrarRutaBlob(id, ruta);
        SasEmitida sas = emisor.emitirSubida(ruta, vigencia, llamador);
        log.info(() -> "SAS de subida emitida. solicitudId=" + id + " rutaBlob=" + ruta + " expira=" + sas.expira());
        return new Resultado(sas.url(), ruta, sas.expira(), bloqueBytes, sas.versionServicio());
    }
}
