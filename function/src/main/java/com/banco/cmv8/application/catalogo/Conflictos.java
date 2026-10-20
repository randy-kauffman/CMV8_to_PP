package com.banco.cmv8.application.catalogo;

import com.banco.cmv8.domain.catalogo.Conflicto;
import com.banco.cmv8.domain.port.CatalogoTipos;
import com.banco.cmv8.domain.port.RegistroIngestas;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Registro deduplicado de conflictos (ADR-003 §4.4): mil archivos del mismo conflicto son UNA fila en Dataverse.
 * La deduplicación por clave vive en la Azure Table; Dataverse recibe una escritura por conflicto nuevo y
 * una actualización del contador por adjunto detenido.
 */
public final class Conflictos {

    private final CatalogoTipos catalogo;
    private final RegistroIngestas registros;

    public Conflictos(CatalogoTipos catalogo, RegistroIngestas registros) {
        this.catalogo = catalogo;
        this.registros = registros;
    }

    /** Registra el conflicto si es nuevo y devuelve su id en Dataverse. */
    public UUID registrar(Conflicto conflicto, Logger log) {
        return registrar(conflicto, 0, log);
    }

    /** Igual que {@link #registrar(Conflicto, Logger)} pero sumando un adjunto detenido al contador. */
    public UUID registrarConDetenido(Conflicto conflicto, Logger log) {
        return registrar(conflicto, 1, log);
    }

    private UUID registrar(Conflicto conflicto, int detenidosNuevos, Logger log) {
        String clave = conflicto.clave();
        Optional<UUID> existente = registros.conflictoRegistrado(clave);
        if (existente.isPresent()) {
            if (detenidosNuevos > 0) {
                int total = (int) registros.listarPorConflicto(clave).size() + detenidosNuevos;
                catalogo.actualizarAdjuntosDetenidos(existente.get(), total);
            }
            return existente.get();
        }
        UUID id = catalogo.registrarConflicto(conflicto, detenidosNuevos);
        registros.registrarConflicto(clave, id);
        log.warning(() -> "Conflicto de catálogo registrado. clase=" + conflicto.clase() + " severidad="
                + conflicto.severidad() + " tipo=" + conflicto.tipoDocumentoId()
                + conflicto.propiedad().map(p -> " propiedad=" + p).orElse("") + " conflictoId=" + id);
        return id;
    }
}
