package com.banco.cmv8.adapters.out.tabla;

import com.azure.core.credential.TokenCredential;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.ListEntitiesOptions;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableEntityUpdateMode;
import com.azure.data.tables.models.TableServiceException;
import com.banco.cmv8.domain.ClaveAdjunto;
import com.banco.cmv8.domain.port.AgendaDepuracion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Azure Table {@code depuracion} (ADR-003 §4.5). PartitionKey = día de vencimiento ({@code yyyy-MM-dd}),
 * RowKey = {@code tabla|id}: "qué vence tal día" es una consulta por partición y no un barrido. El puntero al
 * último día procesado vive en la partición {@link #PARTICION_CONTROL}, que nunca tiene forma de fecha.
 */
public class AgendaDepuracionTabla implements AgendaDepuracion {

    private static final Logger LOG = Logger.getLogger(AgendaDepuracionTabla.class.getName());

    public static final String PARTICION_CONTROL = "control";
    static final String FILA_ULTIMO_DIA = "ultimoDia";
    static final String COL_DIA = "dia";

    /** Separador de tabla e id en el RowKey; no puede aparecer en un nombre lógico ni en un GUID. */
    static final char SEPARADOR = '|';

    private final TableClient tabla;

    public AgendaDepuracionTabla(TableClient tabla) {
        this.tabla = Objects.requireNonNull(tabla, "tabla");
    }

    /** Construye el cliente con la credencial del proceso (identidad administrada en Azure). */
    public static AgendaDepuracionTabla crear(String endpoint, String nombreTabla, TokenCredential credencial) {
        return new AgendaDepuracionTabla(RegistroIngestasTabla.clienteTabla(endpoint, nombreTabla, credencial));
    }

    static String rowKey(ClaveAdjunto clave) {
        return clave.tabla() + SEPARADOR + clave.id();
    }

    @Override
    public void programar(LocalDate dia, ClaveAdjunto clave) {
        tabla.upsertEntityWithResponse(new TableEntity(dia.toString(), rowKey(clave)), TableEntityUpdateMode.REPLACE,
                null, null);
    }

    /** Idempotente: cancelar algo que no estaba agendado no es un error. */
    @Override
    public void cancelar(LocalDate dia, ClaveAdjunto clave) {
        try {
            tabla.deleteEntity(dia.toString(), rowKey(clave));
        } catch (TableServiceException e) {
            if (!RegistroIngestasTabla.esNoEncontrado(e)) {
                throw e;
            }
        }
    }

    @Override
    public List<ClaveAdjunto> pendientes(LocalDate dia) {
        ListEntitiesOptions opciones = new ListEntitiesOptions()
                .setFilter("PartitionKey eq " + RegistroIngestasTabla.literal(dia.toString()));
        List<ClaveAdjunto> resultado = new ArrayList<>();
        for (TableEntity e : tabla.listEntities(opciones, null, null)) {
            claveDe(e.getRowKey()).ifPresentOrElse(resultado::add,
                    () -> LOG.warning(() -> "Entrada de agenda ilegible, se omite: " + e.getPartitionKey() + "/"
                            + e.getRowKey()));
        }
        return resultado;
    }

    /** Lee {@code tabla|id}; vacío si el RowKey no tiene esa forma (fila escrita a mano o de otra versión). */
    static Optional<ClaveAdjunto> claveDe(String rowKey) {
        if (rowKey == null) {
            return Optional.empty();
        }
        int separador = rowKey.indexOf(SEPARADOR);
        if (separador <= 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(ClaveAdjunto.de(rowKey.substring(0, separador), rowKey.substring(separador + 1)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<LocalDate> ultimoDiaProcesado() {
        try {
            String dia = RegistroIngestasTabla.texto(tabla.getEntity(PARTICION_CONTROL, FILA_ULTIMO_DIA), COL_DIA);
            return dia == null ? Optional.empty() : Optional.of(LocalDate.parse(dia));
        } catch (TableServiceException e) {
            if (RegistroIngestasTabla.esNoEncontrado(e)) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public void marcarDiaProcesado(LocalDate dia) {
        TableEntity e = new TableEntity(PARTICION_CONTROL, FILA_ULTIMO_DIA).addProperty(COL_DIA, dia.toString());
        tabla.upsertEntityWithResponse(e, TableEntityUpdateMode.REPLACE, null, null);
    }
}
