package com.banco.cmv8.adapters.out.blob;

import com.azure.core.http.rest.Response;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.UserDelegationKey;
import com.azure.storage.blob.options.BlobGetUserDelegationKeyOptions;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import com.banco.cmv8.domain.ContextoLlamador;
import com.banco.cmv8.domain.PoliticaSas;
import com.banco.cmv8.domain.SasEmitida;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmisorSasBlobTest {

    private static final String ENDPOINT = "https://cta.blob.core.windows.net";
    private static final String ID = "2b1f6f0e-6f39-4c8e-9a43-0d6c1f6f2a11";
    private static final String RUTA = "cred_adjunto/" + ID + "/extracto.pdf";

    private final BlobServiceClient servicio = mock(BlobServiceClient.class);
    private final BlobContainerClient contenedor = mock(BlobContainerClient.class);
    private final BlobClient blob = mock(BlobClient.class);
    private final PoliticaSas politica = new PoliticaSas(100L * 1024 * 1024,
            Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC));

    @SuppressWarnings("unchecked")
    private EmisorSasBlob emisor(boolean ligada) {
        UserDelegationKey clave = new UserDelegationKey().setSignedExpiry(OffsetDateTime.parse("2026-10-19T14:00:00Z"));
        Response<UserDelegationKey> respuesta = mock(Response.class);
        when(respuesta.getValue()).thenReturn(clave);
        when(servicio.getUserDelegationKeyWithResponse(any(BlobGetUserDelegationKeyOptions.class), any(), any()))
                .thenReturn(respuesta);
        when(servicio.getBlobContainerClient("cmv8-almacenados")).thenReturn(contenedor);
        when(contenedor.getBlobClient(RUTA)).thenReturn(blob);
        when(blob.generateUserDelegationSas(any(BlobServiceSasSignatureValues.class), eq(clave))).thenReturn("sv=x&sig=y");
        return new EmisorSasBlob(servicio, ENDPOINT, politica, ligada);
    }

    @Test
    void lecturaFirmaElBlobDelContenedorPedido() {
        SasEmitida sas = emisor(false).emitirLectura("cmv8-almacenados", RUTA, Duration.ofMinutes(10),
                ContextoLlamador.anonimo());

        assertEquals(ENDPOINT + "/cmv8-almacenados/" + RUTA + "?sv=x&sig=y", sas.url());
        ArgumentCaptor<BlobServiceSasSignatureValues> valores = ArgumentCaptor.forClass(BlobServiceSasSignatureValues.class);
        verify(blob).generateUserDelegationSas(valores.capture(), any(UserDelegationKey.class));
        assertEquals("r", valores.getValue().getPermissions());
        assertEquals(ID, valores.getValue().getCorrelationId());
        assertNull(valores.getValue().getDelegatedUserObjectId());
    }

    @Test
    void subidaLigadaAlUsuarioPermiteCrearYEscribir() {
        ContextoLlamador llamador = new ContextoLlamador(Optional.of("oid-1"), Optional.of("tid-1"), Optional.empty(),
                Optional.empty());

        emisor(true).emitirSubida("cmv8-almacenados", RUTA, Duration.ofMinutes(10), llamador);

        ArgumentCaptor<BlobServiceSasSignatureValues> valores = ArgumentCaptor.forClass(BlobServiceSasSignatureValues.class);
        verify(blob).generateUserDelegationSas(valores.capture(), any(UserDelegationKey.class));
        assertEquals("cw", valores.getValue().getPermissions());
        assertEquals("oid-1", valores.getValue().getDelegatedUserObjectId());
    }

    @Test
    void urlDelBlobConOSinBarraFinal() {
        assertEquals("https://c/x/a/b.pdf", EmisorSasBlob.urlBlob("https://c", "x", "a/b.pdf"));
        assertEquals("https://c/x/a/b.pdf", EmisorSasBlob.urlBlob("https://c/", "x", "a/b.pdf"));
    }
}
