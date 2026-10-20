package com.banco.cmv8.domain.catalogo;

import java.util.UUID;

/** Fila de {@code cmv8_conflicto} tal como está en Dataverse (para resolver y reanudar). */
public record ConflictoRegistrado(UUID id, String clave, UUID tipoDocumentoId, Conflicto.Clase clase, boolean abierto) {
}
