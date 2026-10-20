package com.banco.cmv8.adapters.out.dataverse;

import com.banco.cmv8.application.AccesoDenegadoException;
import com.banco.cmv8.application.NoAutenticadoException;
import com.banco.cmv8.application.SolicitudNoEncontradaException;
import com.banco.cmv8.config.Json;
import com.banco.cmv8.domain.Adjunto;
import com.banco.cmv8.domain.ClaveAdjunto;
import com.banco.cmv8.domain.ContextoLlamador;
import com.banco.cmv8.domain.EstadoIngesta;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RepositorioAdjuntosDataverseTest {

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TIPO = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String OID = "22222222-2222-2222-2222-222222222222";
    private static final String TABLA = "cred_adjuntocredito";
    private static final String FILA_URL = "cred_adjuntocreditos(" + ID + ")";
    private static final String RUTA = TABLA + "/" + ID + "/dni.pdf";

    /** Fila tal como la devuelve Dataverse con return=representation: reservadas, lookup y columnas del maker. */
    private static final String FILA = """
            {
              "@odata.context": "https://org.crm.dynamics.com/api/data/v9.2/$metadata#cred_adjuntocreditos/$entity",
              "@odata.etag": "W/\\"12345\\"",
              "cred_adjuntocreditoid": "11111111-1111-1111-1111-111111111111",
              "cred_nombrearchivo": "dni.pdf",
              "cred_tamanobytes": 2048,
              "cred_contenttype": "application/pdf",
              "cred_estadoingesta": 1,
              "cred_rutablob": "cred_adjuntocredito/11111111-1111-1111-1111-111111111111/dni.pdf",
              "cred_cmisobjectid": null,
              "_cred_tipodocumento_value": "33333333-3333-3333-3333-333333333333",
              "cred_mensajeerror": null,
              "cred_numerocliente": "00012345",
              "cred_monto": 1500.5,
              "cred_urgente": true,
              "modifiedon": "2026-10-19T12:00:00Z"
            }
            """;

    private final ClienteDataverse cliente = mock(ClienteDataverse.class);
    private final RepositorioAdjuntosDataverse repo = new RepositorioAdjuntosDataverse(cliente);
    private final ClaveAdjunto clave = new ClaveAdjunto(TABLA, ID);

    @BeforeEach
    void metadata() {
        when(cliente.get(eq("EntityDefinitions(LogicalName='" + TABLA + "')?$select=EntitySetName"), anyMap()))
                .thenReturn(new ClienteDataverse.Respuesta(200, "{\"EntitySetName\":\"cred_adjuntocreditos\"}"));
    }

    private static ContextoLlamador llamador(String oid) {
        return new ContextoLlamador(Optional.ofNullable(oid), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private void respuestaPatchSuplantado(int estado, String cuerpo) {
        when(cliente.enviar(eq("PATCH"), eq(FILA_URL), anyString(), anyMap()))
                .thenReturn(new ClienteDataverse.Respuesta(estado, cuerpo));
    }

    @Test
    void registrarRutaArmaElPatchSuplantadoYMapeaLaFila() throws Exception {
        respuestaPatchSuplantado(200, FILA);

        Adjunto a = repo.registrarRutaSuplantando(clave, RUTA, llamador(OID));

        ArgumentCaptor<String> cuerpo = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, String>> encabezados = ArgumentCaptor.forClass(Map.class);
        verify(cliente).enviar(eq("PATCH"), eq(FILA_URL), cuerpo.capture(), encabezados.capture());
        JsonNode enviado = Json.MAPPER.readTree(cuerpo.getValue());
        assertEquals(1, enviado.size());
        assertEquals(RUTA, enviado.get("cred_rutablob").asText());
        assertEquals("*", encabezados.getValue().get("If-Match"));
        assertEquals(OID, encabezados.getValue().get("CallerObjectId"));
        assertEquals("return=representation", encabezados.getValue().get("Prefer"));

        assertEquals(clave, a.clave());
        assertEquals("dni.pdf", a.nombreArchivo());
        assertEquals(2048L, a.tamanoBytes());
        assertEquals("application/pdf", a.contentType());
        assertEquals(EstadoIngesta.CREADA, a.estado());
        assertEquals(RUTA, a.rutaBlob());
        assertNull(a.cmisObjectId());
        assertEquals(TIPO, a.tipoDocumentoId());
        assertNull(a.mensajeError());
        assertEquals(Instant.parse("2026-10-19T12:00:00Z"), a.modificadoEn());
        // Columnas arbitrarias del maker, con su tipo; anotaciones y nulos fuera.
        assertEquals(Optional.of("00012345"), a.valor("numerocliente"));
        assertEquals(1500.5, a.valores().get("cred_monto"));
        assertEquals(true, a.valores().get("cred_urgente"));
        assertEquals(2048L, a.valores().get("cred_tamanobytes"));
        assertEquals(TIPO.toString(), a.valores().get("_cred_tipodocumento_value"));
        assertFalse(a.valores().containsKey("@odata.etag"));
        assertFalse(a.valores().containsKey("cred_cmisobjectid"));
    }

    @Test
    void registrarRuta403EsAccesoDenegado() {
        respuestaPatchSuplantado(403, "{\"error\":{\"code\":\"0x80040220\"}}");
        assertThrows(AccesoDenegadoException.class,
                () -> repo.registrarRutaSuplantando(clave, RUTA, llamador(OID)));
    }

    @Test
    void registrarRuta404EsNoEncontrada() {
        respuestaPatchSuplantado(404, "{\"error\":{}}");
        assertThrows(SolicitudNoEncontradaException.class,
                () -> repo.registrarRutaSuplantando(clave, RUTA, llamador(OID)));
    }

    @Test
    void registrarRutaSinOidNoLlamaADataverse() {
        assertThrows(NoAutenticadoException.class,
                () -> repo.registrarRutaSuplantando(clave, RUTA, llamador(null)));
        verify(cliente, never()).enviar(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void obtenerSuplantandoPideLaFilaSinSelectY404EsVacio() {
        when(cliente.get(eq(FILA_URL), anyMap())).thenReturn(new ClienteDataverse.Respuesta(404, ""));
        assertTrue(repo.obtenerSuplantando(clave, llamador(OID)).isEmpty());
        verify(cliente).get(eq(FILA_URL), eq(Map.of("CallerObjectId", OID)));
    }

    @Test
    void actualizarAnteponeElPrefijoYEscribeElCodigoDelEstado() throws Exception {
        when(cliente.patch(anyString(), anyString())).thenReturn(new ClienteDataverse.Respuesta(204, ""));
        Map<String, Object> cambios = new HashMap<>();
        cambios.put(Adjunto.COL_ESTADO, EstadoIngesta.ARCHIVADO);
        cambios.put(Adjunto.COL_CMIS, "obj-1");
        cambios.put(Adjunto.COL_MENSAJE, null);

        repo.actualizar(clave, cambios);

        ArgumentCaptor<String> cuerpo = ArgumentCaptor.forClass(String.class);
        verify(cliente).patch(eq(FILA_URL), cuerpo.capture());
        JsonNode enviado = Json.MAPPER.readTree(cuerpo.getValue());
        assertEquals(3, enviado.size());
        assertEquals(4, enviado.get("cred_estadoingesta").asInt());
        assertEquals("obj-1", enviado.get("cred_cmisobjectid").asText());
        assertTrue(enviado.get("cred_mensajeerror").isNull());
    }

    @Test
    void actualizarRecortaElMensajeDeError() throws Exception {
        when(cliente.patch(anyString(), anyString())).thenReturn(new ClienteDataverse.Respuesta(204, ""));

        repo.actualizar(clave, Map.of(Adjunto.COL_MENSAJE, "x".repeat(5000)));

        ArgumentCaptor<String> cuerpo = ArgumentCaptor.forClass(String.class);
        verify(cliente).patch(eq(FILA_URL), cuerpo.capture());
        assertEquals(4000, Json.MAPPER.readTree(cuerpo.getValue()).get("cred_mensajeerror").asText().length());
    }

    @Test
    void elEntitySetSeResuelveUnaSolaVezPorTabla() {
        when(cliente.get(eq(FILA_URL), anyMap())).thenReturn(new ClienteDataverse.Respuesta(200, FILA));
        repo.obtener(clave);
        repo.obtener(clave);
        verify(cliente, times(1))
                .get(eq("EntityDefinitions(LogicalName='" + TABLA + "')?$select=EntitySetName"), anyMap());
    }
}
