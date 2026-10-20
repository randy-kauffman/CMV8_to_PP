package com.banco.cmv8.adapters.out.dataverse;

import com.banco.cmv8.config.Json;
import com.banco.cmv8.domain.catalogo.PropiedadCm;
import com.banco.cmv8.domain.catalogo.TipoDocumento;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatalogoTiposDataverseTest {

    private static final UUID TIPO = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID PROP_EXISTENTE = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID PROP_NUEVA = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    /** Respuesta de listar con $expand de la relación 1:N tipo → propiedades. */
    private static final String TIPOS = """
            {
              "@odata.context": "https://org.crm.dynamics.com/api/data/v9.2/$metadata#cmv8_tipodocumentos",
              "value": [
                {
                  "@odata.etag": "W/\\"1\\"",
                  "cmv8_tipodocumentoid": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                  "cmv8_nombre": "Contrato de crédito",
                  "cmv8_codigo": "CTR",
                  "cmv8_origentipo": 1,
                  "cmv8_tipocmisid": "clb.Contrato",
                  "cmv8_localname": "Contrato",
                  "cmv8_versionable": true,
                  "cmv8_ausenteenservidor": false,
                  "cmv8_revisado": true,
                  "cmv8_cambios": null,
                  "cmv8_descubiertoen": "2026-10-18T10:00:00Z",
                  "cmv8_enuso": true,
                  "cmv8_carpeta": "/Creditos/Contratos",
                  "cmv8_origencarpeta": 2,
                  "cmv8_destino": 1,
                  "cmv8_momento": 2,
                  "cmv8_diasretencion": null,
                  "cmv8_inmutable": false,
                  "cmv8_tipodocumento_propiedadcm": [
                    {
                      "cmv8_propiedadcmid": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                      "cmv8_nombre": "NumeroCliente",
                      "cmv8_propiedadid": "clb.NumeroCliente",
                      "cmv8_nombrevisible": "Número de cliente",
                      "cmv8_tipodato": "string",
                      "cmv8_obligatoria": true,
                      "cmv8_valorpordefecto": null,
                      "cmv8_largomaximo": 20,
                      "cmv8_listavalores": null,
                      "cmv8_heredada": false,
                      "cmv8_decision": 1,
                      "cmv8_origen": 1,
                      "cmv8_nombrelogico": "numerocliente",
                      "cmv8_consulta": null,
                      "cmv8_literal": null,
                      "cmv8_patron": "^[0-9]{8}$",
                      "cmv8_momentovalidacion": 1
                    },
                    {
                      "cmv8_propiedadcmid": "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee",
                      "cmv8_nombre": "Moneda",
                      "cmv8_propiedadid": "clb.Moneda",
                      "cmv8_tipodato": "string",
                      "cmv8_obligatoria": false,
                      "cmv8_valorpordefecto": "ARS",
                      "cmv8_largomaximo": null,
                      "cmv8_listavalores": "ARS\\nUSD\\nEUR",
                      "cmv8_heredada": true,
                      "cmv8_decision": 2,
                      "cmv8_origen": 4,
                      "cmv8_literal": "USD",
                      "cmv8_momentovalidacion": 2
                    }
                  ]
                }
              ]
            }
            """;

    private final ClienteDataverse cliente = mock(ClienteDataverse.class);
    private final CatalogoTiposDataverse catalogo = new CatalogoTiposDataverse(cliente,
            Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    void metadata() {
        conjunto("cmv8_tipodocumento", "cmv8_tipodocumentos");
        conjunto("cmv8_propiedadcm", "cmv8_propiedadcms");
        conjunto("cmv8_conflicto", "cmv8_conflictos");
    }

    private void conjunto(String tabla, String conjunto) {
        when(cliente.get(eq("EntityDefinitions(LogicalName='" + tabla + "')?$select=EntitySetName"), anyMap()))
                .thenReturn(new ClienteDataverse.Respuesta(200, "{\"EntitySetName\":\"" + conjunto + "\"}"));
    }

    @Test
    void listarMapeaUnTipoConSusDosPropiedades() {
        when(cliente.get(startsWith("cmv8_tipodocumentos?"), anyMap()))
                .thenReturn(new ClienteDataverse.Respuesta(200, TIPOS));

        List<TipoDocumento> tipos = catalogo.listar();

        verify(cliente).get(contains("$expand=cmv8_tipodocumento_propiedadcm($select="), anyMap());

        assertEquals(1, tipos.size());
        TipoDocumento t = tipos.get(0);
        assertEquals(TIPO, t.id());
        assertEquals("CTR", t.codigo());
        assertEquals("Contrato de crédito", t.nombre());
        assertEquals(TipoDocumento.OrigenTipo.DESCUBIERTO, t.origen());
        assertEquals("clb.Contrato", t.tipoCmisId());
        assertEquals("Contrato", t.localName());
        assertTrue(t.versionable());
        assertTrue(t.enUso());
        assertEquals("/Creditos/Contratos", t.carpeta());
        assertTrue(t.carpetaManual());
        assertEquals(TipoDocumento.Destino.CONTENT_MANAGER, t.destino());
        assertEquals(TipoDocumento.Momento.DIFERIDO, t.momento());
        assertNull(t.diasRetencion());
        assertFalse(t.inmutable());
        assertTrue(t.revisado());
        assertFalse(t.ausenteEnServidor());
        assertEquals(2, t.propiedades().size());

        PropiedadCm numero = t.propiedades().get(0);
        assertEquals("clb.NumeroCliente", numero.propiedadId());
        assertEquals("NumeroCliente", numero.nombre());
        assertTrue(numero.obligatoria());
        assertEquals(20, numero.largoMaximo());
        assertEquals(List.of(), numero.listaValores());
        assertEquals(PropiedadCm.Decision.USAR, numero.decision());
        assertEquals(PropiedadCm.OrigenValor.COLUMNA, numero.origen());
        assertEquals("numerocliente", numero.nombreLogico());
        assertEquals("^[0-9]{8}$", numero.patron());
        assertEquals(PropiedadCm.MomentoValidacion.AL_ARCHIVAR, numero.momentoValidacion());

        PropiedadCm moneda = t.propiedades().get(1);
        assertEquals(PropiedadCm.Decision.OMITIR, moneda.decision());
        assertEquals(PropiedadCm.OrigenValor.LITERAL, moneda.origen());
        assertEquals("USD", moneda.literal());
        assertEquals("ARS", moneda.valorPorDefecto());
        assertNull(moneda.largoMaximo());
        assertEquals(List.of("ARS", "USD", "EUR"), moneda.listaValores());
        assertTrue(moneda.heredada());
        assertEquals(PropiedadCm.MomentoValidacion.ANTES_DE_SUBIR, moneda.momentoValidacion());
    }

    @Test
    void guardarPropiedadesHacePatchSiExisteYPostSiNo() throws Exception {
        when(cliente.get(contains("clb.Uno"), anyMap())).thenReturn(new ClienteDataverse.Respuesta(200,
                "{\"value\":[{\"cmv8_propiedadcmid\":\"" + PROP_EXISTENTE + "\"}]}"));
        when(cliente.get(contains("clb.Dos"), anyMap()))
                .thenReturn(new ClienteDataverse.Respuesta(200, "{\"value\":[]}"));
        when(cliente.patch(anyString(), anyString())).thenReturn(new ClienteDataverse.Respuesta(204, ""));
        when(cliente.crear(anyString(), anyString())).thenReturn(new ClienteDataverse.Respuesta(201,
                "{\"cmv8_propiedadcmid\":\"" + PROP_NUEVA + "\"}"));

        PropiedadCm uno = new PropiedadCm(null, "clb.Uno", "Uno", "string", true, null, 20, List.of(), false,
                PropiedadCm.Decision.USAR, null, null, null, null, null, null);
        PropiedadCm dos = new PropiedadCm(null, "clb.Dos", "Dos", "integer", false, "0", null, List.of("0", "1"), true,
                PropiedadCm.Decision.USAR, null, null, null, null, null, null);

        catalogo.guardarPropiedadesDescubiertas(TIPO, List.of(uno, dos));

        ArgumentCaptor<String> cuerpoPatch = ArgumentCaptor.forClass(String.class);
        verify(cliente, times(1)).patch(eq("cmv8_propiedadcms(" + PROP_EXISTENTE + ")"), cuerpoPatch.capture());
        JsonNode patch = Json.MAPPER.readTree(cuerpoPatch.getValue());
        assertEquals("string", patch.get("cmv8_tipodato").asText());
        assertTrue(patch.get("cmv8_obligatoria").asBoolean());
        assertEquals(20, patch.get("cmv8_largomaximo").asInt());
        // Lo humano y la identidad no se tocan al actualizar.
        assertFalse(patch.has("cmv8_decision"));
        assertFalse(patch.has("cmv8_origen"));
        assertFalse(patch.has("cmv8_TipoDocumento@odata.bind"));

        ArgumentCaptor<String> cuerpoPost = ArgumentCaptor.forClass(String.class);
        verify(cliente, times(1)).crear(eq("cmv8_propiedadcms?$select=cmv8_propiedadcmid"), cuerpoPost.capture());
        JsonNode post = Json.MAPPER.readTree(cuerpoPost.getValue());
        assertEquals("clb.Dos", post.get("cmv8_propiedadid").asText());
        assertEquals("Dos", post.get("cmv8_nombre").asText());
        assertEquals(1, post.get("cmv8_decision").asInt());
        assertEquals(1, post.get("cmv8_momentovalidacion").asInt());
        assertEquals("0\n1", post.get("cmv8_listavalores").asText());
        assertTrue(post.get("cmv8_heredada").asBoolean());
        assertEquals("/cmv8_tipodocumentos(" + TIPO + ")", post.get("cmv8_TipoDocumento@odata.bind").asText());
    }
}
