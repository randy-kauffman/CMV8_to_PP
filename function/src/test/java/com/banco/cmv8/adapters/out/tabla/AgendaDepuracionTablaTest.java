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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgendaDepuracionTablaTest {

    private static final ClaveAdjunto CLAVE =
            new ClaveAdjunto("cred_adjuntocredito", UUID.fromString("11111111-2222-3333-4444-555555555555"));
    private static final LocalDate DIA = LocalDate.parse("2026-10-19");

    private final TableClient cliente = mock(TableClient.class);
    private final AgendaDepuracionTabla agenda = new AgendaDepuracionTabla(cliente);

    /** Se arma ANTES del when(...) que la usa: stubbear otro mock a mitad de un stubbing rompe Mockito. */
    private static TableServiceException noEncontrado() {
        HttpResponse respuesta = mock(HttpResponse.class);
        when(respuesta.getStatusCode()).thenReturn(404);
        return new TableServiceException("no existe", respuesta);
    }

    @Test
    void programarUsaDiaYTablaBarraId() {
        agenda.programar(DIA, CLAVE);

        ArgumentCaptor<TableEntity> entidad = ArgumentCaptor.forClass(TableEntity.class);
        verify(cliente).upsertEntityWithResponse(entidad.capture(), eq(TableEntityUpdateMode.REPLACE), any(), any());
        assertEquals("2026-10-19", entidad.getValue().getPartitionKey());
        assertEquals("cred_adjuntocredito|11111111-2222-3333-4444-555555555555", entidad.getValue().getRowKey());
    }

    @Test
    void pendientesConsultaLaParticionDelDiaYOmiteFilasIlegibles() {
        List<TableEntity> filas = List.of(
                new TableEntity("2026-10-19", AgendaDepuracionTabla.rowKey(CLAVE)),
                new TableEntity("2026-10-19", "sin-separador"));
        Supplier<PagedResponse<TableEntity>> primera =
                () -> new PagedResponseBase<Void, TableEntity>(null, 200, null, filas, null, null);
        when(cliente.listEntities(any(ListEntitiesOptions.class), any(), any())).thenReturn(new PagedIterable<>(primera));

        List<ClaveAdjunto> pendientes = agenda.pendientes(DIA);

        ArgumentCaptor<ListEntitiesOptions> opciones = ArgumentCaptor.forClass(ListEntitiesOptions.class);
        verify(cliente).listEntities(opciones.capture(), any(), any());
        assertEquals("PartitionKey eq '2026-10-19'", opciones.getValue().getFilter());
        assertEquals(List.of(CLAVE), pendientes);
    }

    @Test
    void cancelarIgnoraNoEncontrado() {
        String rowKey = AgendaDepuracionTabla.rowKey(CLAVE);
        TableServiceException e404 = noEncontrado();
        doThrow(e404).when(cliente).deleteEntity("2026-10-19", rowKey);

        agenda.cancelar(DIA, CLAVE);

        verify(cliente).deleteEntity("2026-10-19", rowKey);
    }

    @Test
    void punteroDelUltimoDiaProcesado() {
        TableServiceException e404 = noEncontrado();
        when(cliente.getEntity("control", "ultimoDia")).thenThrow(e404);
        assertEquals(Optional.empty(), agenda.ultimoDiaProcesado());

        agenda.marcarDiaProcesado(DIA);

        ArgumentCaptor<TableEntity> entidad = ArgumentCaptor.forClass(TableEntity.class);
        verify(cliente).upsertEntityWithResponse(entidad.capture(), eq(TableEntityUpdateMode.REPLACE), any(), any());
        assertEquals("control", entidad.getValue().getPartitionKey());
        assertEquals("ultimoDia", entidad.getValue().getRowKey());
        assertEquals("2026-10-19", entidad.getValue().getProperty("dia"));
    }

    @Test
    void leeElUltimoDiaProcesado() {
        when(cliente.getEntity("control", "ultimoDia"))
                .thenReturn(new TableEntity("control", "ultimoDia").addProperty("dia", "2026-10-18"));

        assertEquals(Optional.of(LocalDate.parse("2026-10-18")), agenda.ultimoDiaProcesado());
    }
}
