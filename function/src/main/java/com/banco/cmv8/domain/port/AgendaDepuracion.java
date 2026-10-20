package com.banco.cmv8.domain.port;

import com.banco.cmv8.domain.ClaveAdjunto;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Azure Table {@code depuracion} (ADR-003 §4.5): PartitionKey = día de vencimiento (yyyy-MM-dd),
 * RowKey = tabla|id. Azure Table solo indexa las claves, así que "qué vence hoy" es una consulta puntual
 * y no un barrido. Guarda además un puntero al último día procesado para no perder días si el timer no corrió.
 */
public interface AgendaDepuracion {

    void programar(LocalDate dia, ClaveAdjunto clave);

    void cancelar(LocalDate dia, ClaveAdjunto clave);

    List<ClaveAdjunto> pendientes(LocalDate dia);

    Optional<LocalDate> ultimoDiaProcesado();

    void marcarDiaProcesado(LocalDate dia);
}
