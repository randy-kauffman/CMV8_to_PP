package com.banco.cmv8.domain.port;

import com.banco.cmv8.domain.ClaveAdjunto;
import com.banco.cmv8.domain.EstadoIngesta;
import com.banco.cmv8.domain.RegistroIngesta;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Azure Table {@code ingesta} (ADR-003 §3): PartitionKey = tabla, RowKey = id. Verdad operativa. También
 * guarda la deduplicación de conflictos (PartitionKey {@code conflicto}, RowKey = clave).
 */
public interface RegistroIngestas {

    void guardar(RegistroIngesta registro);

    Optional<RegistroIngesta> obtener(ClaveAdjunto clave);

    /**
     * Registros en alguno de los estados dados cuya última marca de tiempo ({@code Timestamp} de la tabla)
     * es más vieja que {@code antiguedad}. Para el Watchdog: Subido sin procesar, Procesando colgado, SAS
     * emitida (Creada) y nunca subida.
     */
    List<RegistroIngesta> listarEstancados(Set<EstadoIngesta> estados, Duration antiguedad, int tope);

    /** Adjuntos en Detenida por un conflicto, para reencolarlos al resolverlo. */
    List<RegistroIngesta> listarPorConflicto(String conflictoClave);

    Optional<UUID> conflictoRegistrado(String conflictoClave);

    void registrarConflicto(String conflictoClave, UUID conflictoId);

    void borrarConflicto(String conflictoClave);
}
