package com.banco.cmv8.adapters.out.blob;

import com.azure.core.util.Context;
import com.azure.core.util.polling.LongRunningOperationStatus;
import com.azure.core.util.polling.PollResponse;
import com.azure.core.util.polling.SyncPoller;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobCopyInfo;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobImmutabilityPolicy;
import com.azure.storage.blob.models.BlobImmutabilityPolicyMode;
import com.azure.storage.blob.models.CopyStatusType;
import com.azure.storage.blob.models.ParallelTransferOptions;
import com.azure.storage.blob.options.BlobBeginCopyOptions;
import com.azure.storage.blob.options.BlobInputStreamOptions;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import com.banco.cmv8.domain.port.AlmacenAdjuntos;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Blobs de la cuenta de adjuntos con la identidad administrada (ADR-003 §7). Un solo {@link BlobServiceClient};
 * el contenedor ({@code cmv8-ingesta} o {@code cmv8-almacenados}) llega explícito en cada llamada.
 *
 * <p>Mover es copia del lado del servidor + borrado del origen. Se usa Copy Blob ({@code beginCopy}) y no Copy
 * Blob From URL: dentro de la MISMA cuenta, Copy Blob autoriza la lectura del origen con el mismo token Entra de
 * la petición (REST "Copy Blob", tabla de autorización: origen en la misma cuenta → Entra sí, versión
 * 2017-11-09+), sin {@code x-ms-copy-source-authorization}, y no tiene el tope de 256 MiB de la copia sincrónica.
 * La identidad necesita leer el origen y escribir el destino (Blob Data Contributor en ambos contenedores).
 */
public class AlmacenAdjuntosBlob implements AlmacenAdjuntos {

    private static final Logger LOG = Logger.getLogger(AlmacenAdjuntosBlob.class.getName());

    /** Etiqueta de índice que escribe Defender for Storage (blobScanResultsOptions = BlobIndexTags). */
    public static final String ETIQUETA_ESCANEO = "Malware Scanning scan result";

    /** Tamaño de cada lectura por rango y de cada bloque subido: memoria acotada sin importar el tamaño (S4b). */
    private static final int BLOQUE = 4 * 1024 * 1024;

    /** Bloques en vuelo al subir: memoria del upload ≈ BLOQUE × CONCURRENCIA_SUBIDA. */
    private static final int CONCURRENCIA_SUBIDA = 2;

    /** Una copia dentro de la cuenta suele terminar en la misma respuesta; el sondeo es la red para blobs grandes. */
    private static final Duration INTERVALO_SONDEO_COPIA = Duration.ofSeconds(1);
    private static final Duration ESPERA_MAXIMA_COPIA = Duration.ofMinutes(5);

    private final BlobServiceClient servicio;

    public AlmacenAdjuntosBlob(BlobServiceClient servicio) {
        this.servicio = Objects.requireNonNull(servicio, "servicio");
    }

    BlobClient blob(String contenedor, String ruta) {
        return servicio.getBlobContainerClient(contenedor).getBlobClient(ruta);
    }

    @Override
    public InputStream abrir(String contenedor, String ruta) {
        return blob(contenedor, ruta).openInputStream(new BlobInputStreamOptions().setBlockSize(BLOQUE));
    }

    @Override
    public long tamano(String contenedor, String ruta) {
        return blob(contenedor, ruta).getProperties().getBlobSize();
    }

    @Override
    public boolean existe(String contenedor, String ruta) {
        return Boolean.TRUE.equals(blob(contenedor, ruta).exists());
    }

    @Override
    public void borrar(String contenedor, String ruta) {
        blob(contenedor, ruta).deleteIfExists();
    }

    /**
     * Subida por bloques desde el stream. {@code maxSingleUploadSize} = un bloque para que el SDK no junte en
     * memoria todo el archivo intentando un Put Blob de una sola vez (por defecto llega a 256 MiB).
     * Sin condiciones: sobrescribe, porque un reintento de ingesta debe poder reescribir el mismo blob.
     */
    @Override
    public void escribir(String contenedor, String ruta, InputStream contenido, long tamano, String contentType) {
        ParallelTransferOptions transferencia = new ParallelTransferOptions()
                .setBlockSizeLong((long) BLOQUE)
                .setMaxSingleUploadSizeLong((long) BLOQUE)
                .setMaxConcurrency(CONCURRENCIA_SUBIDA);
        BlobParallelUploadOptions opciones = new BlobParallelUploadOptions(contenido, tamano)
                .setParallelTransferOptions(transferencia)
                .setHeaders(new BlobHttpHeaders().setContentType(
                        contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType));
        blob(contenedor, ruta).uploadWithResponse(opciones, null, Context.NONE);
    }

    /**
     * Idempotente ante una caída entre la copia y el borrado: si el origen ya no está y el destino sí, se da por
     * movido. Si la copia no termina en SUCCESS, el origen NO se borra.
     */
    @Override
    public void mover(String contenedorOrigen, String rutaOrigen, String contenedorDestino, String rutaDestino) {
        BlobClient origen = blob(contenedorOrigen, rutaOrigen);
        BlobClient destino = blob(contenedorDestino, rutaDestino);

        if (!Boolean.TRUE.equals(origen.exists())) {
            if (Boolean.TRUE.equals(destino.exists())) {
                LOG.fine(() -> "Mover: el origen ya no existe y el destino sí; se da por movido " + rutaDestino);
                return;
            }
            throw new IllegalStateException("No existe el blob a mover: " + contenedorOrigen + "/" + rutaOrigen);
        }

        SyncPoller<BlobCopyInfo, Void> sondeo = destino.beginCopy(
                new BlobBeginCopyOptions(origen.getBlobUrl()).setPollInterval(INTERVALO_SONDEO_COPIA));
        PollResponse<BlobCopyInfo> fin;
        try {
            fin = sondeo.waitForCompletion(ESPERA_MAXIMA_COPIA);
        } catch (RuntimeException e) {
            abortar(sondeo, rutaDestino);
            throw new IllegalStateException("La copia a " + contenedorDestino + "/" + rutaDestino
                    + " no terminó en " + ESPERA_MAXIMA_COPIA, e);
        }
        if (!copiaExitosa(fin)) {
            BlobCopyInfo info = fin == null ? null : fin.getValue();
            throw new IllegalStateException("La copia a " + contenedorDestino + "/" + rutaDestino + " terminó en "
                    + (info == null ? "estado desconocido" : info.getCopyStatus()));
        }
        origen.deleteIfExists();
    }

    static boolean copiaExitosa(PollResponse<BlobCopyInfo> respuesta) {
        return respuesta != null
                && respuesta.getStatus() == LongRunningOperationStatus.SUCCESSFULLY_COMPLETED
                && respuesta.getValue() != null
                && respuesta.getValue().getCopyStatus() == CopyStatusType.SUCCESS;
    }

    /** Una copia colgada bloquea el destino (409 PendingCopyOperation en el reintento): se aborta si se puede. */
    private static void abortar(SyncPoller<BlobCopyInfo, Void> sondeo, String rutaDestino) {
        try {
            sondeo.cancelOperation();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "No se pudo abortar la copia pendiente hacia " + rutaDestino, e);
        }
    }

    @Override
    public void ponerInmutable(String contenedor, String ruta, Instant hasta, boolean bloquear) {
        blob(contenedor, ruta).setImmutabilityPolicy(politicaInmutabilidad(hasta, bloquear));
    }

    /** Bloqueada solo se extiende, nunca se acorta ni se borra (Learn): por eso el modo lo decide la config. */
    static BlobImmutabilityPolicy politicaInmutabilidad(Instant hasta, boolean bloquear) {
        Objects.requireNonNull(hasta, "hasta");
        return new BlobImmutabilityPolicy()
                .setExpiryTime(OffsetDateTime.ofInstant(hasta, ZoneOffset.UTC))
                .setPolicyMode(bloquear ? BlobImmutabilityPolicyMode.LOCKED : BlobImmutabilityPolicyMode.UNLOCKED);
    }

    @Override
    public Optional<String> resultadoEscaneo(String contenedor, String ruta) {
        Map<String, String> etiquetas = blob(contenedor, ruta).getTags();
        return Optional.ofNullable(etiquetas == null ? null : etiquetas.get(ETIQUETA_ESCANEO));
    }
}
