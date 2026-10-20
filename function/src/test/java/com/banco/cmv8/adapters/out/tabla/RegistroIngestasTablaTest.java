package com.banco.cmv8.adapters.out.tabla;

import com.azure.core.http.HttpResponse;
import com.azure.core.http.rest.PagedIterable;
import com.azure.core.http.rest.PagedResponse;
import com.azure.core.http.rest.PagedResponseBase;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.ListEntitiesOptions;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableEntityUpdateMode;
import com.azure.data.tables.models.TableServiceException;
import com.banco.cmv8.domain.ClaveAdjunto;
import com.banco.cmv8.domain.EstadoIngesta;
import com.banco.cmv8.domain.RegistroIngesta;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegistroIngestasTablaTest {

    private static final ClaveAdjunto CLAVE =
            new ClaveAdjunto("cred_adjuntocredito", UUID.fromString("11111111-2222-3333-4444-555555555555"));
    private static final Instant AHORA = Instant.parse("2026-10-19T12:00:30.500Z");

    private final TableClient cliente = mock(TableClient.class);
    private final RegistroIngestasTabla registro =
            new RegistroIngestasTabla(cliente, Clock.fixed(AHORA, ZoneOffset.UTC));

    private static RegistroIngesta minimo() {
        return new RegistroIngesta(CLAVE, EstadoIngesta.CREADA, null, "cmv8-ingesta", CLAVE.ruta("a.pdf"),
                null, null, null, null, null, null, 0, null, null, null, null, null, null);
    }

    private static RegistroIngesta completo() {
        return new RegistroIngesta(CLAVE, EstadoIngesta.DETENIDA,
                UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"), "cmv8-almacenados", CLAVE.ruta("a.pdf"),
                "oid-1", "a.pdf", 5_000_000_000L, "application/pdf",
                Map.of("cred_numerocliente", "0042", "cred_monto", 1500, "cred_activo", true),
                "idd_123;1.0", 3, "Falta la propiedad X", "tipo|prop|clase", LocalDate.parse("2027-01-31"),
                Instant.parse("2026-10-19T10:00:00.123456Z"), Instant.parse("2026-10-19T10:05:00Z"),
                Instant.parse("2026-10-19T10:06:00Z"));
    }

    /** Se arma ANTES del when(...) que la usa: stubbear otro mock a mitad de un stubbing rompe Mockito. */
    private static TableServiceException error(int estado) {
        HttpResponse respuesta = mock(HttpResponse.class);
        when(respuesta.getStatusCode()).thenReturn(estado);
        return new TableServiceException("error " + estado, respuesta);
    }

    private static PagedIterable<TableEntity> pagina(TableEntity... entidades) {
        Supplier<PagedResponse<TableEntity>> primera =
                () -> new PagedResponseBase<Void, TableEntity>(null, 200, null, List.of(entidades), null, null);
        return new PagedIterable<>(primera);
    }

    // ------------------------------------------------------------------ mapeo

    @Test
    void mapeoIdaYVueltaConTodosLosCampos() {
        RegistroIngesta original = completo();

        TableEntity e = RegistroIngestasTabla.aEntidad(original);

        assertEquals(CLAVE.tabla(), e.getPartitionKey());
        assertEquals(CLAVE.id().toString(), e.getRowKey());
        assertEquals(9, e.getProperty("estado"));
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", e.getProperty("tipoDocumentoId"));
        assertEquals(5_000_000_000L, e.getProperty("tamanoBytes"));
        assertEquals("2027-01-31", e.getProperty("fechaDepuracion"));
        assertEquals(OffsetDateTime.parse("2026-10-19T10:05:00Z"), e.getProperty("subidoEn"));
        assertTrue(e.getProperty("valoresJson") instanceof String);
        assertEquals(original, RegistroIngestasTabla.desdeEntidad(e));
    }

    @Test
    void mapeoIdaYVueltaConNulosOmiteLasColumnas() {
        RegistroIngesta original = minimo();

        TableEntity e = RegistroIngestasTabla.aEntidad(original);

        for (String columna : List.of("tipoDocumentoId", "solicitanteOid", "nombreArchivo", "tamanoBytes",
                "contentType", "valoresJson", "cmisObjectId", "mensaje", "conflictoClave", "fechaDepuracion",
                "sasEmitidaEn", "subidoEn", "procesadoEn")) {
            assertFalse(e.getProperties().containsKey(columna), columna);
        }
        RegistroIngesta leido = RegistroIngestasTabla.desdeEntidad(e);
        assertEquals(original, leido);
        assertTrue(leido.valores().isEmpty());
        assertNull(leido.fechaDepuracion());
    }

    @Test
    void leeEnterosYFechasEscritosConOtroTipoEdm() {
        TableEntity e = new TableEntity(CLAVE.tabla(), CLAVE.id().toString())
                .addProperty("estado", 2L)
                .addProperty("contenedor", "cmv8-ingesta")
                .addProperty("ruta", CLAVE.ruta("a.pdf"))
                .addProperty("tamanoBytes", 10)
                .addProperty("subidoEn", "2026-10-19T10:05:00Z");

        RegistroIngesta r = RegistroIngestasTabla.desdeEntidad(e);

        assertEquals(EstadoIngesta.SUBIDO, r.estado());
        assertEquals(10L, r.tamanoBytes());
        assertEquals(0, r.intentos());
        assertEquals(Instant.parse("2026-10-19T10:05:00Z"), r.subidoEn());
    }

    @Test
    void guardarHaceUpsertEnModoReplace() {
        registro.guardar(completo());

        ArgumentCaptor<TableEntity> entidad = ArgumentCaptor.forClass(TableEntity.class);
        verify(cliente).upsertEntityWithResponse(entidad.capture(), eq(TableEntityUpdateMode.REPLACE), any(), any());
        assertEquals(CLAVE.tabla(), entidad.getValue().getPartitionKey());
        assertEquals(completo(), RegistroIngestasTabla.desdeEntidad(entidad.getValue()));
    }

    // ------------------------------------------------------------------ obtener

    @Test
    void obtenerDevuelveElRegistro() {
        when(cliente.getEntity(CLAVE.tabla(), CLAVE.id().toString()))
                .thenReturn(RegistroIngestasTabla.aEntidad(completo()));

        assertEquals(Optional.of(completo()), registro.obtener(CLAVE));
    }

    @Test
    void obtenerConNoEncontradoDevuelveVacio() {
        TableServiceException e404 = error(404);
        when(cliente.getEntity(CLAVE.tabla(), CLAVE.id().toString())).thenThrow(e404);

        assertEquals(Optional.empty(), registro.obtener(CLAVE));
    }

    @Test
    void obtenerPropagaOtrosErrores() {
        TableServiceException e500 = error(500);
        when(cliente.getEntity(CLAVE.tabla(), CLAVE.id().toString())).thenThrow(e500);

        assertSame(e500, assertThrows(TableServiceException.class, () -> registro.obtener(CLAVE)));
    }

    // ------------------------------------------------------------------ consultas

    @Test
    void listarEstancadosArmaElFiltroODataYRespetaElTope() {
        TableEntity a = RegistroIngestasTabla.aEntidad(minimo());
        TableEntity b = RegistroIngestasTabla.aEntidad(new RegistroIngesta(
                new ClaveAdjunto("cred_otra", UUID.randomUUID()), EstadoIngesta.SUBIDO, null, "c", "r",
                null, null, null, null, null, null, 0, null, null, null, null, null, null));
        TableEntity conflicto = new TableEntity("conflicto", "x").addProperty("conflictoId", UUID.randomUUID().toString());
        when(cliente.listEntities(any(ListEntitiesOptions.class), any(), any())).thenReturn(pagina(conflicto, a, b));

        List<RegistroIngesta> estancados = registro.listarEstancados(
                EnumSet.of(EstadoIngesta.SUBIDO, EstadoIngesta.CREADA), Duration.ofMinutes(30), 1);

        ArgumentCaptor<ListEntitiesOptions> opciones = ArgumentCaptor.forClass(ListEntitiesOptions.class);
        verify(cliente).listEntities(opciones.capture(), any(), any());
        assertEquals("(estado eq 1 or estado eq 2) and Timestamp lt datetime'2026-10-19T11:30:30Z'"
                + " and PartitionKey ne 'conflicto'", opciones.getValue().getFilter());
        assertEquals(1, opciones.getValue().getTop());
        assertEquals(List.of(minimo()), estancados);
    }

    @Test
    void listarEstancadosSinEstadosNoConsulta() {
        assertTrue(registro.listarEstancados(EnumSet.noneOf(EstadoIngesta.class), Duration.ofMinutes(5), 10)
                .isEmpty());
        assertTrue(registro.listarEstancados(EnumSet.of(EstadoIngesta.SUBIDO), Duration.ofMinutes(5), 0).isEmpty());
    }

    @Test
    void listarPorConflictoEscapaComillasYOmiteFilasIlegibles() {
        TableEntity rota = new TableEntity(CLAVE.tabla(), "no-es-guid").addProperty("estado", 9);
        TableEntity buena = RegistroIngestasTabla.aEntidad(completo());
        when(cliente.listEntities(any(ListEntitiesOptions.class), any(), any())).thenReturn(pagina(rota, buena));

        List<RegistroIngesta> lista = registro.listarPorConflicto("tipo|o'brien|clase");

        ArgumentCaptor<ListEntitiesOptions> opciones = ArgumentCaptor.forClass(ListEntitiesOptions.class);
        verify(cliente).listEntities(opciones.capture(), any(), any());
        assertEquals("conflictoClave eq 'tipo|o''brien|clase'", opciones.getValue().getFilter());
        assertEquals(List.of(completo()), lista);
    }

    // ------------------------------------------------------------------ conflictos

    @Test
    void rowKeyDeConflictoEsReversibleYSinCaracteresProhibidos() {
        String clave = "tipo/prop#1?x\\y%z\u0001\u007F\u0085ñ|ok";

        String rowKey = RegistroIngestasTabla.codificarRowKey(clave);

        assertEquals("tipo%2Fprop%231%3Fx%5Cy%25z%01%7F%85ñ|ok", rowKey);
        assertTrue(rowKey.chars().noneMatch(c -> c == '/' || c == '\\' || c == '#' || c == '?' || c <= 0x1F
                || (c >= 0x7F && c <= 0x9F)));
        assertEquals(clave, RegistroIngestasTabla.decodificarRowKey(rowKey));
    }

    @Test
    void rowKeyDeConflictoNoMezclaClavesDistintas() {
        assertNotEquals(RegistroIngestasTabla.codificarRowKey("a/"), RegistroIngestasTabla.codificarRowKey("a%2F"));
        assertEquals("a%2F", RegistroIngestasTabla.decodificarRowKey(RegistroIngestasTabla.codificarRowKey("a%2F")));
        assertThrows(IllegalArgumentException.class, () -> RegistroIngestasTabla.decodificarRowKey("a%2"));
    }

    @Test
    void registrarConflictoEscribeLaFilaDeDeduplicacion() {
        UUID id = UUID.randomUUID();

        registro.registrarConflicto("tipo/prop", id);

        ArgumentCaptor<TableEntity> entidad = ArgumentCaptor.forClass(TableEntity.class);
        verify(cliente).upsertEntityWithResponse(entidad.capture(), eq(TableEntityUpdateMode.REPLACE), any(), any());
        assertEquals("conflicto", entidad.getValue().getPartitionKey());
        assertEquals("tipo%2Fprop", entidad.getValue().getRowKey());
        assertEquals(id.toString(), entidad.getValue().getProperty("conflictoId"));
    }

    @Test
    void conflictoRegistradoLeeElIdOVacioSiNoExiste() {
        UUID id = UUID.randomUUID();
        TableServiceException e404 = error(404);
        when(cliente.getEntity("conflicto", "tipo%2Fprop"))
                .thenReturn(new TableEntity("conflicto", "tipo%2Fprop").addProperty("conflictoId", id.toString()));
        when(cliente.getEntity("conflicto", "otra")).thenThrow(e404);

        assertEquals(Optional.of(id), registro.conflictoRegistrado("tipo/prop"));
        assertEquals(Optional.empty(), registro.conflictoRegistrado("otra"));
    }

    @Test
    void borrarConflictoIgnoraNoEncontrado() {
        TableServiceException e404 = error(404);
        doThrow(e404).when(cliente).deleteEntity("conflicto", "tipo%2Fprop");

        registro.borrarConflicto("tipo/prop");

        verify(cliente).deleteEntity("conflicto", "tipo%2Fprop");
    }
}
