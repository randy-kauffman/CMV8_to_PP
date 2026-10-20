package com.banco.cmv8.application.catalogo;

import com.banco.cmv8.domain.catalogo.DefinicionTipoCmis;
import com.banco.cmv8.domain.catalogo.PropiedadCm;
import com.banco.cmv8.domain.catalogo.TipoDocumento;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reglas puras portadas de CMCourier (ADR-003 §6): cómo se deriva el ID corto y la carpeta, qué decisión se
 * sugiere por propiedad y qué cambió entre lo guardado y lo que el servidor publica ahora.
 */
public final class ReglasDescubrimiento {

    /** {@code "PT95 - Escritura Hipotecaria"} → {@code PT95}. */
    private static final Pattern PREFIJO_NOMBRE = Pattern.compile("^(\\S+) - ");

    private final Optional<String> propiedadIdCorto;
    private final String carpetaPlantilla;

    public ReglasDescubrimiento(Optional<String> propiedadIdCorto, String carpetaPlantilla) {
        this.propiedadIdCorto = propiedadIdCorto;
        this.carpetaPlantilla = carpetaPlantilla;
    }

    /**
     * ID corto: primero el default de la propiedad convencional; si no, el prefijo {@code "<código> - "} del
     * nombre visible; si no hay ninguno, vacío y el tipo queda sin código (el administrador decide).
     */
    public Optional<String> codigoCorto(DefinicionTipoCmis tipo) {
        Optional<String> porPropiedad = propiedadIdCorto
                .flatMap(tipo::propiedadPorNombre)
                .map(DefinicionTipoCmis.Propiedad::valorPorDefecto)
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim);
        if (porPropiedad.isPresent()) {
            return porPropiedad;
        }
        if (tipo.nombreVisible() == null) {
            return Optional.empty();
        }
        Matcher m = PREFIJO_NOMBRE.matcher(tipo.nombreVisible());
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    public String carpetaDerivada(DefinicionTipoCmis tipo) {
        String local = tipo.localName() == null || tipo.localName().isBlank() ? tipo.tipoId() : tipo.localName();
        return carpetaPlantilla.replace("{localName}", local);
    }

    /** Convierte una propiedad publicada en una fila nueva del catálogo con la decisión sugerida. */
    public PropiedadCm propiedadNueva(DefinicionTipoCmis.Propiedad p) {
        return new PropiedadCm(null, p.id(), PropiedadCm.nombreCanonico(p.id()), p.tipoDato(), p.obligatoria(),
                p.valorPorDefecto(), p.largoMaximo(), p.listaValores(), p.heredada(),
                PropiedadCm.decisionSugerida(p.obligatoria(), p.valorPorDefecto()), null, null, null, null, null, null);
    }

    /** Actualiza solo lo descubierto de una propiedad existente; la decisión y el origen son del humano. */
    public PropiedadCm propiedadActualizada(PropiedadCm actual, DefinicionTipoCmis.Propiedad p) {
        return new PropiedadCm(actual.id(), p.id(), PropiedadCm.nombreCanonico(p.id()), p.tipoDato(), p.obligatoria(),
                p.valorPorDefecto(), p.largoMaximo(), p.listaValores(), p.heredada(), actual.decision(), actual.origen(),
                actual.nombreLogico(), actual.consulta(), actual.literal(), actual.patron(), actual.momentoValidacion());
    }

    /**
     * Lista legible de cambios entre lo guardado y lo publicado: {@code + id (obligatoria)}, {@code - id},
     * {@code ~ id largo 20 → 30}, {@code ~ nombre}, {@code ~ versionable}. Vacía si no cambió nada.
     */
    public List<String> cambios(TipoDocumento guardado, DefinicionTipoCmis vivo) {
        List<String> cambios = new ArrayList<>();
        if (!Objects.equals(limpio(guardado.nombre()), limpio(vivo.nombreVisible()))) {
            cambios.add("~ nombre: " + guardado.nombre() + " → " + vivo.nombreVisible());
        }
        if (!Objects.equals(limpio(guardado.localName()), limpio(vivo.localName()))) {
            cambios.add("~ localName: " + guardado.localName() + " → " + vivo.localName());
        }
        if (guardado.versionable() != vivo.versionable()) {
            cambios.add("~ versionable: " + guardado.versionable() + " → " + vivo.versionable());
        }
        for (DefinicionTipoCmis.Propiedad p : vivo.propiedades()) {
            Optional<PropiedadCm> local = guardado.propiedades().stream()
                    .filter(x -> x.propiedadId().equals(p.id())).findFirst();
            if (local.isEmpty()) {
                cambios.add("+ " + p.id() + (p.obligatoria() ? " (obligatoria)" : ""));
                continue;
            }
            PropiedadCm l = local.get();
            if (l.obligatoria() != p.obligatoria()) {
                cambios.add("~ " + p.id() + " obligatoria: " + l.obligatoria() + " → " + p.obligatoria());
            }
            if (!Objects.equals(l.largoMaximo(), p.largoMaximo())) {
                cambios.add("~ " + p.id() + " largo: " + l.largoMaximo() + " → " + p.largoMaximo());
            }
            if (!Objects.equals(limpio(l.tipoDato()), limpio(p.tipoDato()))) {
                cambios.add("~ " + p.id() + " tipo: " + l.tipoDato() + " → " + p.tipoDato());
            }
            if (!Objects.equals(limpio(l.valorPorDefecto()), limpio(p.valorPorDefecto()))) {
                cambios.add("~ " + p.id() + " default: " + l.valorPorDefecto() + " → " + p.valorPorDefecto());
            }
        }
        for (PropiedadCm l : guardado.propiedades()) {
            if (vivo.propiedad(l.propiedadId()).isEmpty()) {
                cambios.add("- " + l.propiedadId() + (l.seUsa() ? " (en uso)" : ""));
            }
        }
        return cambios;
    }

    /**
     * ID corto compartido por varios tipos (dato real del servidor): gana el que cumple la convención
     * {@code "<código> - "} en el nombre visible; si ninguno o varios, el primero que publicó el servidor.
     */
    public static boolean esGanador(String codigo, DefinicionTipoCmis candidato, List<DefinicionTipoCmis> grupo) {
        List<DefinicionTipoCmis> conConvencion = grupo.stream()
                .filter(t -> t.nombreVisible() != null && t.nombreVisible().startsWith(codigo + " - ")).toList();
        DefinicionTipoCmis ganador = conConvencion.size() == 1 ? conConvencion.get(0) : grupo.get(0);
        return ganador.tipoId().equals(candidato.tipoId());
    }

    private static String limpio(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
