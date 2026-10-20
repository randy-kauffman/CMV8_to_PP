package com.banco.cmv8.adapters.out.blob;

import com.azure.core.util.Context;
import com.azure.core.util.polling.LongRunningOperationStatus;
import com.azure.core.util.polling.PollResponse;
import com.azure.core.util.polling.SyncPoller;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobCopyInfo;
import com.azure.storage.blob.models.BlobImmutabilityPolicy;
import com.azure.storage.blob.models.BlobImmutabilityPolicyMode;
import com.azure.storage.blob.models.CopyStatusType;
import com.azure.storage.blob.options.BlobBeginCopyOptions;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlmacenAdjuntosBlobTest {

    private static final String INGESTA = "cmv8-ingesta";
    private static final String ALMACENADOS = "cmv8-almacenados";
    private static final String RUTA = "cred_adjunto/2b1f6f0e-6f39-4c8e-9a43-0d6c1f6f2a11/extracto.pdf";
    private static final String URL_ORIGEN = "https://cta.blob.core.windows.net/" + INGESTA + "/" + RUTA;

    private final BlobServiceClient servicio = mock(BlobServiceClient.class);
    private final BlobContainerClient ingesta = mock(BlobContainerClient.class);
    private final BlobContainerClient almacenados = mock(BlobContainerClient.class);
    private final BlobClient origen = mock(BlobClient.class);
    private final BlobClient destino = mock(BlobClient.class);
    private final AlmacenAdjuntosBlob almacen = new AlmacenAdjuntosBlob(servicio);

    AlmacenAdjuntosBlobTest() {
        when(servicio.getBlobContainerClient(INGESTA)).thenReturn(ingesta);
        when(servicio.getBlobContainerClient(ALMACENADOS)).thenReturn(almacenados);
        when(ingesta.getBlobClient(RUTA)).thenReturn(origen);
        when(almacenados.getBlobClient(RUTA)).thenReturn(destino);
        when(origen.getBlobUrl()).thenReturn(URL_ORIGEN);
    }

    @Test
    void cadaOperacionUsaElContenedorPedido() {
        when(destino.exists()).thenReturn(true);
        when(origen.getTags()).thenReturn(Map.of(AlmacenAdjuntosBlob.ETIQUETA_ESCANEO, "No threats found"));

        assertTrue(almacen.existe(ALMACENADOS, RUTA));
        assertEquals("No threats found", almacen.resultadoEscaneo(INGESTA, RUTA).orElseThrow());
        almacen.borrar(ALMACENADOS, RUTA);

        verify(destino).deleteIfExists();
        verify(origen, never()).deleteIfExists();
    }

    @Test
    void sinEtiquetaDeEscaneoDevuelveVacio() {
        when(origen.getTags()).thenReturn(null);

        assertFalse(almacen.resultadoEscaneo(INGESTA, RUTA).isPresent());
    }

    @Test
    void escribirSubePorBloquesConContentType() {
        ByteArrayInputStream contenido = new ByteArrayInputStream(new byte[]{1, 2, 3});

        almacen.escribir(INGESTA, RUTA, contenido, 3, "application/pdf");

        ArgumentCaptor<BlobParallelUploadOptions> opciones = ArgumentCaptor.forClass(BlobParallelUploadOptions.class);
        verify(origen).uploadWithResponse(opciones.capture(), isNull(), eq(Context.NONE));
        assertEquals("application/pdf", opciones.getValue().getHeaders().getContentType());
        assertEquals(3L, opciones.getValue().getOptionalLength());
        assertEquals(4L * 1024 * 1024, opciones.getValue().getParallelTransferOptions().getMaxSingleUploadSizeLong());
    }

    @Test
    void escribirSinContentTypeUsaOctetStream() {
        almacen.escribir(INGESTA, RUTA, new ByteArrayInputStream(new byte[0]), 0, " ");

        ArgumentCaptor<BlobParallelUploadOptions> opciones = ArgumentCaptor.forClass(BlobParallelUploadOptions.class);
        verify(origen).uploadWithResponse(opciones.capture(), isNull(), eq(Context.NONE));
        assertEquals("application/octet-stream", opciones.getValue().getHeaders().getContentType());
    }

    @Test
    void moverCopiaDesdeLaUrlDelOrigenYDespuesBorraElOrigen() {
        when(origen.exists()).thenReturn(true);
        SyncPoller<BlobCopyInfo, Void> sondeo = sondeoQueTermina(LongRunningOperationStatus.SUCCESSFULLY_COMPLETED,
                CopyStatusType.SUCCESS);

        almacen.mover(INGESTA, RUTA, ALMACENADOS, RUTA);

        ArgumentCaptor<BlobBeginCopyOptions> opciones = ArgumentCaptor.forClass(BlobBeginCopyOptions.class);
        verify(destino).beginCopy(opciones.capture());
        assertEquals(URL_ORIGEN, opciones.getValue().getSourceUrl());
        verify(sondeo).waitForCompletion(any(Duration.class));
        verify(origen).deleteIfExists();
    }

    @Test
    void moverNoBorraElOrigenSiLaCopiaFalla() {
        when(origen.exists()).thenReturn(true);
        sondeoQueTermina(LongRunningOperationStatus.FAILED, CopyStatusType.FAILED);

        assertThrows(IllegalStateException.class, () -> almacen.mover(INGESTA, RUTA, ALMACENADOS, RUTA));
        verify(origen, never()).deleteIfExists();
    }

    @Test
    void moverAbortaYNoBorraSiLaCopiaNoTermina() {
        when(origen.exists()).thenReturn(true);
        @SuppressWarnings("unchecked")
        SyncPoller<BlobCopyInfo, Void> sondeo = mock(SyncPoller.class);
        when(destino.beginCopy(any(BlobBeginCopyOptions.class))).thenReturn(sondeo);
        when(sondeo.waitForCompletion(any(Duration.class))).thenThrow(new RuntimeException("timeout"));

        assertThrows(IllegalStateException.class, () -> almacen.mover(INGESTA, RUTA, ALMACENADOS, RUTA));
        verify(sondeo).cancelOperation();
        verify(origen, never()).deleteIfExists();
    }

    @Test
    void moverEsIdempotenteSiYaSeMovio() {
        when(origen.exists()).thenReturn(false);
        when(destino.exists()).thenReturn(true);

        almacen.mover(INGESTA, RUTA, ALMACENADOS, RUTA);

        verify(destino, never()).beginCopy(any(BlobBeginCopyOptions.class));
    }

    @Test
    void moverSinOrigenNiDestinoFalla() {
        when(origen.exists()).thenReturn(false);
        when(destino.exists()).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> almacen.mover(INGESTA, RUTA, ALMACENADOS, RUTA));
    }

    @Test
    void inmutableBloqueadaConVencimientoEnUtc() {
        Instant hasta = Instant.parse("2033-10-19T00:00:00Z");

        almacen.ponerInmutable(ALMACENADOS, RUTA, hasta, true);

        ArgumentCaptor<BlobImmutabilityPolicy> politica = ArgumentCaptor.forClass(BlobImmutabilityPolicy.class);
        verify(destino).setImmutabilityPolicy(politica.capture());
        assertEquals(BlobImmutabilityPolicyMode.LOCKED, politica.getValue().getPolicyMode());
        assertEquals(OffsetDateTime.of(2033, 10, 19, 0, 0, 0, 0, ZoneOffset.UTC), politica.getValue().getExpiryTime());
    }

    @Test
    void inmutableSinBloquearEsUnlocked() {
        BlobImmutabilityPolicy politica = AlmacenAdjuntosBlob.politicaInmutabilidad(Instant.parse("2030-01-01T00:00:00Z"), false);

        assertEquals(BlobImmutabilityPolicyMode.UNLOCKED, politica.getPolicyMode());
        assertEquals(ZoneOffset.UTC, politica.getExpiryTime().getOffset());
    }

    @Test
    void copiaExitosaExigeOperacionCompletaYEstadoSuccess() {
        assertTrue(AlmacenAdjuntosBlob.copiaExitosa(respuesta(LongRunningOperationStatus.SUCCESSFULLY_COMPLETED, CopyStatusType.SUCCESS)));
        assertFalse(AlmacenAdjuntosBlob.copiaExitosa(respuesta(LongRunningOperationStatus.SUCCESSFULLY_COMPLETED, CopyStatusType.ABORTED)));
        assertFalse(AlmacenAdjuntosBlob.copiaExitosa(respuesta(LongRunningOperationStatus.IN_PROGRESS, CopyStatusType.PENDING)));
        assertFalse(AlmacenAdjuntosBlob.copiaExitosa(null));
    }

    private SyncPoller<BlobCopyInfo, Void> sondeoQueTermina(LongRunningOperationStatus estado, CopyStatusType copia) {
        @SuppressWarnings("unchecked")
        SyncPoller<BlobCopyInfo, Void> sondeo = mock(SyncPoller.class);
        when(destino.beginCopy(any(BlobBeginCopyOptions.class))).thenReturn(sondeo);
        when(sondeo.waitForCompletion(any(Duration.class))).thenReturn(respuesta(estado, copia));
        return sondeo;
    }

    private static PollResponse<BlobCopyInfo> respuesta(LongRunningOperationStatus estado, CopyStatusType copia) {
        return new PollResponse<>(estado, new BlobCopyInfo(URL_ORIGEN, "copia-1", copia, "etag", OffsetDateTime.now(), null));
    }
}
