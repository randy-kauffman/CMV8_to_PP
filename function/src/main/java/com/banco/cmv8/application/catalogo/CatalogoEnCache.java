package com.banco.cmv8.application.catalogo;

import com.banco.cmv8.domain.catalogo.TipoDocumento;
import com.banco.cmv8.domain.port.CatalogoTipos;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Caché en memoria del catálogo de tipos (ADR-003 §6): el camino caliente lo lee por cada archivo y el catálogo
 * cambia cada mucho. Sin este caché, cada archivo sumaría requests a Dataverse. Las operaciones de
 * administración lo invalidan al escribir.
 */
public final class CatalogoEnCache {

    private final CatalogoTipos catalogo;
    private final Duration vigencia;
    private final Clock reloj;
    private volatile Map<UUID, TipoDocumento> tipos;
    private volatile Instant cargadoEn;

    public CatalogoEnCache(CatalogoTipos catalogo, Duration vigencia, Clock reloj) {
        this.catalogo = catalogo;
        this.vigencia = vigencia;
        this.reloj = reloj;
    }

    /** Margen mínimo entre recargas por fallo de búsqueda, para que un id inexistente no dispare una lectura por archivo. */
    private static final Duration MARGEN_RECARGA = Duration.ofSeconds(30);

    public Optional<TipoDocumento> tipo(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        TipoDocumento t = vigente().get(id);
        if (t == null && cargadoEn != null && cargadoEn.plus(MARGEN_RECARGA).isBefore(reloj.instant())) {
            // Un tipo recién creado o puesto en uso no debería esperar al vencimiento del caché: una recarga acotada.
            invalidar();
            t = vigente().get(id);
        }
        return Optional.ofNullable(t);
    }

    public List<TipoDocumento> tipos() {
        return List.copyOf(vigente().values());
    }

    public List<TipoDocumento> tiposEnUso() {
        return vigente().values().stream().filter(TipoDocumento::enUso).toList();
    }

    /** Tras DescubrirTipos, ActualizarTipos o resolver un conflicto. */
    public void invalidar() {
        tipos = null;
        cargadoEn = null;
    }

    private Map<UUID, TipoDocumento> vigente() {
        Map<UUID, TipoDocumento> actual = tipos;
        Instant desde = cargadoEn;
        if (actual == null || desde == null || desde.plus(vigencia).isBefore(reloj.instant())) {
            synchronized (this) {
                actual = tipos;
                desde = cargadoEn;
                if (actual == null || desde == null || desde.plus(vigencia).isBefore(reloj.instant())) {
                    actual = catalogo.listar().stream()
                            .collect(Collectors.toUnmodifiableMap(TipoDocumento::id, Function.identity(), (a, b) -> a));
                    tipos = actual;
                    cargadoEn = reloj.instant();
                }
            }
        }
        return actual;
    }
}
