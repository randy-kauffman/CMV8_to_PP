package com.banco.cmv8.application.catalogo;

import com.banco.cmv8.domain.catalogo.Conflicto;
import com.banco.cmv8.domain.catalogo.DefinicionTipoCmis;
import com.banco.cmv8.domain.catalogo.PropiedadCm;
import com.banco.cmv8.domain.catalogo.TipoDocumento;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Análisis estructural de un tipo contra lo que Content Manager publica, sin valores de ningún archivo
 * (ADR-003 §4.4): es el "check" de CMCourier. Lo usan ValidarTipo (para humanos, con tres severidades) y
 * VigilarCatalogo (registra los bloqueantes y los avisos). Lo que depende de un archivo concreto lo hace
 * {@link ResolverPropiedades}.
 */
public final class AnalizadorCatalogo {

    public enum Nivel { CRITICAL, WARNING, INFO }

    public record Hallazgo(Nivel nivel, String tipo, String propiedad, String mensaje, Optional<Conflicto> conflicto) {
        @Override
        public String toString() {
            return nivel + " [" + tipo + (propiedad == null ? "" : "/" + propiedad) + "] " + mensaje;
        }
    }

    private AnalizadorCatalogo() {
    }

    /**
     * @param viva       definición viva; vacía si el servidor ya no publica el tipo
     * @param carpetaOk  si la carpeta del tipo existe en el servidor (vacío = no se verificó)
     * @param consultas  consultas con nombre declaradas en la configuración del AS400
     */
    public static List<Hallazgo> analizar(TipoDocumento tipo, Optional<DefinicionTipoCmis> viva,
                                          Optional<Boolean> carpetaOk, Set<String> consultas) {
        List<Hallazgo> h = new ArrayList<>();
        String codigo = tipo.codigo() == null ? tipo.nombre() : tipo.codigo();
        if (!tipo.tieneItemType()) {
            return h;
        }
        if (viva.isEmpty()) {
            h.add(critico(codigo, null, "Content Manager ya no publica el tipo " + tipo.tipoCmisId(),
                    Conflicto.bloqueante(Conflicto.Clase.TIPO_AUSENTE, tipo.id(), null,
                            "Content Manager ya no publica el tipo " + tipo.tipoCmisId())));
            return h;
        }
        DefinicionTipoCmis def = viva.get();
        if (!tipo.revisado()) {
            h.add(new Hallazgo(Nivel.WARNING, codigo, null, "El tipo no fue revisado por el administrador", Optional.empty()));
        }
        if (carpetaOk.isPresent() && !carpetaOk.get()) {
            h.add(critico(codigo, null, "La carpeta " + tipo.carpeta() + " no existe en Content Manager",
                    Conflicto.bloqueante(Conflicto.Clase.CARPETA_INEXISTENTE, tipo.id(), null,
                            "La carpeta " + tipo.carpeta() + " no existe en Content Manager")));
        }
        if (tipo.inmutable() && tipo.diasRetencion() == null) {
            h.add(new Hallazgo(Nivel.CRITICAL, codigo, null, "El tipo es inmutable y no tiene días de retención", Optional.empty()));
        }
        if (tipo.destino() == TipoDocumento.Destino.SOLO_BLOB && tipo.momento() == TipoDocumento.Momento.DIFERIDO) {
            h.add(new Hallazgo(Nivel.INFO, codigo, null, "Momento diferido no aplica a un tipo Solo Blob", Optional.empty()));
        }
        Set<String> enUso = new java.util.HashSet<>();
        for (PropiedadCm p : tipo.propiedades()) {
            Optional<DefinicionTipoCmis.Propiedad> enCm = def.propiedad(p.propiedadId());
            if (p.seUsa()) {
                enUso.add(p.propiedadId());
                if (enCm.isEmpty()) {
                    h.add(critico(codigo, p.propiedadId(), "Se usa y Content Manager ya no la publica",
                            Conflicto.bloqueante(Conflicto.Clase.CATALOGO_DESACTUALIZADO, tipo.id(), p.propiedadId(),
                                    "El catálogo usa la propiedad " + p.propiedadId() + " y Content Manager ya no la publica")));
                    continue;
                }
                if (p.origen() == null) {
                    h.add(critico(codigo, p.propiedadId(), "Se usa y no tiene origen del valor",
                            Conflicto.bloqueante(Conflicto.Clase.OBLIGATORIA_SIN_ORIGEN, tipo.id(), p.propiedadId(),
                                    "La propiedad " + p.nombre() + " está marcada Usar y no tiene origen del valor")));
                } else {
                    origen(h, tipo, codigo, p, consultas);
                }
                if (p.patron() != null && !p.patron().isBlank()) {
                    try {
                        Pattern.compile(p.patron());
                    } catch (PatternSyntaxException e) {
                        h.add(new Hallazgo(Nivel.WARNING, codigo, p.propiedadId(), "El patrón no es una expresión regular válida", Optional.empty()));
                    }
                }
                if (p.origen() == PropiedadCm.OrigenValor.LITERAL && p.literal() != null && enCm.get().largoMaximo() != null
                        && p.literal().length() > enCm.get().largoMaximo()) {
                    h.add(new Hallazgo(Nivel.WARNING, codigo, p.propiedadId(), "El literal supera el largo máximo "
                            + enCm.get().largoMaximo(), Optional.empty()));
                }
            } else if (enCm.isPresent() && enCm.get().obligatoriaSinDefault()) {
                h.add(critico(codigo, p.propiedadId(), "Content Manager la exige (obligatoria sin default) y está en Omitir",
                        Conflicto.bloqueante(Conflicto.Clase.CATALOGO_DESACTUALIZADO, tipo.id(), p.propiedadId(),
                                "Content Manager exige la propiedad " + p.propiedadId() + " y el catálogo no la envía")));
            }
            if (enCm.isPresent() && p.obligatoria() && !enCm.get().obligatoria()) {
                h.add(new Hallazgo(Nivel.INFO, codigo, p.propiedadId(), "El catálogo la marca obligatoria y Content Manager ya no",
                        Optional.of(Conflicto.aviso(Conflicto.Clase.CATALOGO_DESACTUALIZADO, tipo.id(), p.propiedadId(),
                                "El catálogo marca obligatoria " + p.nombre() + " y Content Manager ya no"))));
            }
        }
        for (DefinicionTipoCmis.Propiedad v : def.propiedades()) {
            boolean conocida = tipo.propiedades().stream().anyMatch(p -> p.propiedadId().equals(v.id()));
            if (!conocida) {
                if (v.obligatoriaSinDefault()) {
                    h.add(critico(codigo, v.id(), "Content Manager la exige y el catálogo no la conoce: corré ActualizarTipos",
                            Conflicto.bloqueante(Conflicto.Clase.CATALOGO_DESACTUALIZADO, tipo.id(), v.id(),
                                    "Content Manager exige la propiedad " + v.id() + " y el catálogo no la conoce")));
                } else {
                    h.add(new Hallazgo(Nivel.INFO, codigo, v.id(), "Propiedad nueva en Content Manager, no está en el catálogo", Optional.empty()));
                }
            }
        }
        return h;
    }

    private static void origen(List<Hallazgo> h, TipoDocumento tipo, String codigo, PropiedadCm p, Set<String> consultas) {
        switch (p.origen()) {
            case COLUMNA -> {
                if (p.nombreLogico() == null || p.nombreLogico().isBlank()) {
                    h.add(critico(codigo, p.propiedadId(), "Origen columna sin nombre lógico",
                            Conflicto.bloqueante(Conflicto.Clase.OBLIGATORIA_SIN_ORIGEN, tipo.id(), p.propiedadId(),
                                    "La propiedad " + p.nombre() + " tiene origen columna sin nombre lógico")));
                }
            }
            case CONSULTA -> {
                String c = p.consulta() == null ? "" : p.consulta();
                int punto = c.indexOf('.');
                String nombre = punto > 0 ? c.substring(0, punto).trim().toLowerCase(Locale.ROOT) : "";
                if (nombre.isEmpty() || punto == c.length() - 1) {
                    h.add(critico(codigo, p.propiedadId(), "Origen consulta debe ser 'consulta.columna'",
                            Conflicto.bloqueante(Conflicto.Clase.OBLIGATORIA_SIN_ORIGEN, tipo.id(), p.propiedadId(),
                                    "La propiedad " + p.nombre() + " tiene origen consulta sin 'consulta.columna'")));
                } else if (!consultas.contains(nombre)) {
                    h.add(critico(codigo, p.propiedadId(), "La consulta " + nombre + " no está declarada en AS400_CONSULTAS_JSON",
                            Conflicto.bloqueante(Conflicto.Clase.OBLIGATORIA_SIN_ORIGEN, tipo.id(), p.propiedadId(),
                                    "La consulta " + nombre + " no está declarada en la configuración del AS400")));
                }
            }
            case SISTEMA -> {
                String n = p.nombreLogico() == null ? "" : p.nombreLogico().toLowerCase(Locale.ROOT);
                if (!ResolverPropiedades.SISTEMA.contains(n)) {
                    h.add(critico(codigo, p.propiedadId(), "Origen sistema desconocido: " + n + " (válidos: " + ResolverPropiedades.SISTEMA + ")",
                            Conflicto.bloqueante(Conflicto.Clase.OBLIGATORIA_SIN_ORIGEN, tipo.id(), p.propiedadId(),
                                    "Origen sistema desconocido: " + n)));
                }
            }
            case LITERAL -> {
                if (p.literal() == null || p.literal().isBlank()) {
                    h.add(new Hallazgo(Nivel.WARNING, codigo, p.propiedadId(), "Origen literal sin valor", Optional.empty()));
                }
            }
        }
    }

    private static Hallazgo critico(String tipo, String propiedad, String mensaje, Conflicto conflicto) {
        return new Hallazgo(Nivel.CRITICAL, tipo, propiedad, mensaje, Optional.of(conflicto));
    }
}
