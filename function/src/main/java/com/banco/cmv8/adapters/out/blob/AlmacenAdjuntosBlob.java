package com.banco.cmv8.adapters.out.blob;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.options.BlobInputStreamOptions;
import com.banco.cmv8.domain.port.AlmacenAdjuntos;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

/** Lectura en streaming y borrado de blobs del contenedor de ingesta con la identidad administrada. */
public class AlmacenAdjuntosBlob implements AlmacenAdjuntos {

    /** Etiqueta de índice que escribe Defender for Storage (blobScanResultsOptions = BlobIndexTags). */
    public static final String ETIQUETA_ESCANEO = "Malware Scanning scan result";

    /** Tamaño de cada lectura por rango: memoria acotada sin importar el tamaño del blob (S4b). */
    private static final int BLOQUE_LECTURA = 4 * 1024 * 1024;

    private final BlobContainerClient contenedor;

    public AlmacenAdjuntosBlob(BlobContainerClient contenedor) {
        this.contenedor = contenedor;
    }

    private BlobClient blob(String rutaBlob) {
        return contenedor.getBlobClient(rutaBlob);
    }

    @Override
    public InputStream abrir(String rutaBlob) {
        return blob(rutaBlob).openInputStream(new BlobInputStreamOptions().setBlockSize(BLOQUE_LECTURA));
    }

    @Override
    public long tamano(String rutaBlob) {
        return blob(rutaBlob).getProperties().getBlobSize();
    }

    @Override
    public boolean existe(String rutaBlob) {
        return Boolean.TRUE.equals(blob(rutaBlob).exists());
    }

    @Override
    public void borrar(String rutaBlob) {
        blob(rutaBlob).deleteIfExists();
    }

    @Override
    public Optional<String> resultadoEscaneo(String rutaBlob) {
        Map<String, String> etiquetas = blob(rutaBlob).getTags();
        return Optional.ofNullable(etiquetas == null ? null : etiquetas.get(ETIQUETA_ESCANEO));
    }
}
