package com.banco.cmv8.application.catalogo;

import com.banco.cmv8.domain.catalogo.TipoDocumento;
import com.banco.cmv8.domain.port.ArchivoDocumental;
import com.banco.cmv8.domain.port.ResolutorAs400;

import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Timer VigilarCatalogo (ADR-003 §4.4): no esperar al primer archivo. Cada hora compara la definición viva de
 * cada tipo en uso contra el catálogo y registra conflictos nuevos (deduplicados), que disparan la alerta.
 * Detecta, no decide: nunca corre ActualizarTipos ni cambia una decisión.
 */
public class VigilarCatalogo {

    private final CatalogoEnCache cache;
    private final ArchivoDocumental archivo;
    private final ResolutorAs400 as400;
    private final Conflictos conflictos;

    public VigilarCatalogo(CatalogoEnCache cache, ArchivoDocumental archivo, ResolutorAs400 as400, Conflictos conflictos) {
        this.cache = cache;
        this.archivo = archivo;
        this.as400 = as400;
        this.conflictos = conflictos;
    }

    public int ejecutar(Logger log) {
        cache.invalidar();
        int registrados = 0;
        int tipos = 0;
        for (TipoDocumento tipo : cache.tiposEnUso()) {
            if (!tipo.tieneItemType()) {
                continue;
            }
            tipos++;
            try {
                Optional<Boolean> carpetaOk = tipo.carpeta() == null || tipo.carpeta().isBlank()
                        ? Optional.empty() : Optional.of(archivo.existeCarpeta(tipo.carpeta()));
                for (AnalizadorCatalogo.Hallazgo h : AnalizadorCatalogo.analizar(tipo,
                        archivo.definicionTipo(tipo.tipoCmisId()), carpetaOk, as400.consultasDeclaradas())) {
                    if (h.conflicto().isPresent()) {
                        conflictos.registrar(h.conflicto().get(), log);
                        registrados++;
                    }
                }
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "No se pudo vigilar el tipo " + tipo.codigo() + "; se reintenta en la próxima corrida", e);
            }
        }
        final int t = tipos;
        final int r = registrados;
        log.info(() -> "VigilarCatalogo: tipos=" + t + " conflictos evaluados=" + r);
        return registrados;
    }
}
