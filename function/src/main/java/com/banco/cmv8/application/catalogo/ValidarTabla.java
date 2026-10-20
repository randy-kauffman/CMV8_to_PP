package com.banco.cmv8.application.catalogo;

import com.banco.cmv8.application.SolicitudNoEncontradaException;
import com.banco.cmv8.domain.Adjunto;
import com.banco.cmv8.domain.ClaveAdjunto;
import com.banco.cmv8.domain.ContextoLlamador;
import com.banco.cmv8.domain.catalogo.PropiedadCm;
import com.banco.cmv8.domain.catalogo.TipoDocumento;
import com.banco.cmv8.domain.port.RepositorioAdjuntos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Operación ValidarTabla (ADR-003 §1.1): herramienta del maker antes de publicar. Verifica que la tabla tenga
 * las columnas reservadas con el prefijo derivado y las columnas de origen "columna" de los tipos en uso (o del
 * tipo indicado). Una request de metadata, fuera del flujo. La autorización es la lectura suplantada de la tabla:
 * quien no puede ver la tabla no puede validarla.
 */
public class ValidarTabla {

    public record Informe(String tabla, String prefijo, List<String> faltantes, List<String> avisos) {
        public boolean ok() {
            return faltantes.isEmpty();
        }
    }

    private final RepositorioAdjuntos repositorio;
    private final CatalogoEnCache cache;

    public ValidarTabla(RepositorioAdjuntos repositorio, CatalogoEnCache cache) {
        this.repositorio = repositorio;
        this.cache = cache;
    }

    public Informe ejecutar(String tabla, Optional<UUID> tipoId, ContextoLlamador llamador, Logger log) {
        ClaveAdjunto sonda = new ClaveAdjunto(tabla, UUID.randomUUID());
        if (llamador.objectId().isEmpty()) {
            throw new com.banco.cmv8.application.NoAutenticadoException("ValidarTabla requiere la identidad del llamador");
        }
        Set<String> columnas = repositorio.columnas(tabla);
        if (columnas.isEmpty()) {
            throw new SolicitudNoEncontradaException("La tabla " + tabla + " no existe o no tiene columnas visibles");
        }
        List<String> faltantes = new ArrayList<>();
        List<String> avisos = new ArrayList<>();
        for (String reservada : List.of(Adjunto.COL_ESTADO, Adjunto.COL_TIPO, Adjunto.COL_RUTA, Adjunto.COL_CMIS,
                Adjunto.COL_MENSAJE, Adjunto.COL_NOMBRE_ARCHIVO, Adjunto.COL_TAMANO, Adjunto.COL_CONTENT_TYPE)) {
            if (!columnas.contains(sonda.columna(reservada))) {
                faltantes.add(sonda.columna(reservada) + " (columna reservada)");
            }
        }
        List<TipoDocumento> tipos = tipoId.map(id -> cache.tipo(id).map(List::of).orElse(List.of()))
                .orElseGet(cache::tiposEnUso);
        for (TipoDocumento tipo : tipos) {
            for (PropiedadCm p : tipo.propiedadesEnUso()) {
                if (p.origen() == PropiedadCm.OrigenValor.COLUMNA && p.nombreLogico() != null && !p.nombreLogico().isBlank()) {
                    String columna = sonda.columna(p.nombreLogico().toLowerCase(Locale.ROOT));
                    if (!columnas.contains(columna)) {
                        faltantes.add(columna + " (la pide " + p.nombre() + " del tipo " + tipo.codigo() + ")");
                    }
                }
            }
            if (!tipo.revisado()) {
                avisos.add("El tipo " + tipo.codigo() + " no fue revisado por el administrador");
            }
        }
        Informe informe = new Informe(tabla, sonda.prefijo(), faltantes, avisos);
        log.info(() -> "ValidarTabla: tabla=" + tabla + " faltantes=" + faltantes.size() + " avisos=" + avisos.size()
                + " llamador=" + llamador);
        return informe;
    }
}
