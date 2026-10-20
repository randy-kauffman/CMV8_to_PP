package com.banco.cmv8.application.catalogo;

import com.banco.cmv8.domain.ClaveAdjunto;
import com.banco.cmv8.domain.catalogo.Conflicto;
import com.banco.cmv8.domain.catalogo.DefinicionTipoCmis;
import com.banco.cmv8.domain.catalogo.PropiedadCm;
import com.banco.cmv8.domain.catalogo.TipoDocumento;
import com.banco.cmv8.domain.port.ResolutorAs400;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Resuelve las propiedades que van a Content Manager para un adjunto, dirigido por el catálogo (ADR-003 §4.2),
 * y separa lo que encuentra en dos listas con dueños distintos:
 * <ul>
 *   <li><b>conflictos de catálogo</b>: CM cambió y el catálogo no, columna inexistente, consulta inexistente,
 *       propiedad en uso sin origen → el adjunto queda Detenida y actúa el administrador;</li>
 *   <li><b>observaciones de datos</b>: a ESTE archivo le falta un valor, no cumple el patrón, es demasiado largo
 *       o el AS400 no lo reconoce → el adjunto queda Observada y actúa el usuario.</li>
 * </ul>
 * Solo bloquea lo que CM rechazaría; un catálogo más estricto que CM es aviso.
 */
public final class ResolverPropiedades {

    public record Resultado(Map<String, Object> propiedades, List<Conflicto> conflictos, List<String> observaciones) {
        public boolean bloqueada() {
            return conflictos.stream().anyMatch(Conflicto::bloquea);
        }
        public boolean observada() {
            return !observaciones.isEmpty();
        }
        public List<Conflicto> avisos() {
            return conflictos.stream().filter(c -> !c.bloquea()).toList();
        }
    }

    /** Valores de origen "sistema" disponibles para cualquier tipo. */
    public static final Set<String> SISTEMA = Set.of("id", "tabla", "nombrearchivo", "contenttype", "tamanobytes");

    private final ResolutorAs400 as400;

    public ResolverPropiedades(ResolutorAs400 as400) {
        this.as400 = as400;
    }

    /**
     * @param columnasTabla nombres lógicos completos de las columnas de la tabla de adjuntos (metadata cacheada),
     *                      para distinguir "la columna no existe" (catálogo) de "la columna está vacía" (datos)
     * @param viva          definición viva del tipo; vacía si CM ya no lo publica
     */
    public Resultado resolver(TipoDocumento tipo, ClaveAdjunto clave, Map<String, Object> valores,
                              String nombreArchivo, String contentType, Long tamanoBytes,
                              Set<String> columnasTabla, Optional<DefinicionTipoCmis> viva) {
        List<Conflicto> conflictos = new ArrayList<>();
        List<String> observaciones = new ArrayList<>();
        Map<String, Object> propiedades = new LinkedHashMap<>();

        if (viva.isEmpty()) {
            conflictos.add(Conflicto.bloqueante(Conflicto.Clase.TIPO_AUSENTE, tipo.id(), null,
                    "Content Manager ya no publica el tipo " + tipo.tipoCmisId()));
            return new Resultado(propiedades, conflictos, observaciones);
        }
        DefinicionTipoCmis definicion = viva.get();
        Map<String, Object> sistema = Map.of(
                "id", clave.id().toString(), "tabla", clave.tabla(),
                "nombrearchivo", nombreArchivo == null ? "" : nombreArchivo,
                "contenttype", contentType == null ? "" : contentType,
                "tamanobytes", tamanoBytes == null ? 0L : tamanoBytes);
        Map<String, Optional<Map<String, Object>>> consultas = new HashMap<>();

        for (PropiedadCm p : tipo.propiedadesEnUso()) {
            Optional<DefinicionTipoCmis.Propiedad> enCm = definicion.propiedad(p.propiedadId());
            if (enCm.isEmpty()) {
                conflictos.add(Conflicto.bloqueante(Conflicto.Clase.CATALOGO_DESACTUALIZADO, tipo.id(), p.propiedadId(),
                        "El catálogo usa la propiedad " + p.propiedadId() + " y Content Manager ya no la publica"));
                continue;
            }
            if (p.origen() == null) {
                conflictos.add(Conflicto.bloqueante(Conflicto.Clase.OBLIGATORIA_SIN_ORIGEN, tipo.id(), p.propiedadId(),
                        "La propiedad " + p.nombre() + " está marcada Usar y no tiene origen del valor"));
                continue;
            }
            Optional<Object> crudo = valorCrudo(p, tipo, clave, valores, sistema, columnasTabla, consultas, conflictos,
                    observaciones);
            if (crudo.isEmpty()) {
                if (enCm.get().obligatoriaSinDefault() || p.obligatoria()) {
                    observaciones.add("Falta el valor de " + p.nombre());
                }
                continue;
            }
            String texto = ConversorCmis.texto(crudo.get());
            if (!cumplePatron(p, texto)) {
                observaciones.add("El valor de " + p.nombre() + " no cumple el formato esperado");
                continue;
            }
            Integer largo = enCm.get().largoMaximo();
            if (largo != null && largo > 0 && texto.length() > largo) {
                observaciones.add("El valor de " + p.nombre() + " supera el largo máximo de " + largo);
                continue;
            }
            Optional<Object> convertido = ConversorCmis.convertir(crudo.get(), enCm.get().tipoDato());
            if (convertido.isEmpty()) {
                observaciones.add("El valor de " + p.nombre() + " no es un " + enCm.get().tipoDato() + " válido");
                continue;
            }
            propiedades.put(p.propiedadId(), convertido.get());
        }

        // Lo que CM exige y el catálogo no manda: conflicto de catálogo, porque el usuario no puede resolverlo.
        Set<String> enUso = tipo.propiedadesEnUso().stream().map(PropiedadCm::propiedadId).collect(java.util.stream.Collectors.toSet());
        for (DefinicionTipoCmis.Propiedad viva1 : definicion.propiedades()) {
            if (viva1.obligatoriaSinDefault() && !enUso.contains(viva1.id())) {
                conflictos.add(Conflicto.bloqueante(Conflicto.Clase.CATALOGO_DESACTUALIZADO, tipo.id(), viva1.id(),
                        "Content Manager exige la propiedad " + viva1.id() + " y el catálogo no la envía"));
            }
        }
        // Catálogo más estricto que CM: aviso, no bloquea.
        for (PropiedadCm p : tipo.propiedades()) {
            definicion.propiedad(p.propiedadId()).ifPresent(v -> {
                if (p.obligatoria() && !v.obligatoria()) {
                    conflictos.add(Conflicto.aviso(Conflicto.Clase.CATALOGO_DESACTUALIZADO, tipo.id(), p.propiedadId(),
                            "El catálogo marca obligatoria " + p.nombre() + " y Content Manager ya no"));
                }
            });
        }
        return new Resultado(propiedades, conflictos, observaciones);
    }

    private Optional<Object> valorCrudo(PropiedadCm p, TipoDocumento tipo, ClaveAdjunto clave, Map<String, Object> valores,
                                        Map<String, Object> sistema, Set<String> columnasTabla,
                                        Map<String, Optional<Map<String, Object>>> consultas,
                                        List<Conflicto> conflictos, List<String> observaciones) {
        switch (p.origen()) {
            case LITERAL -> {
                return Optional.ofNullable(p.literal()).filter(s -> !s.isBlank());
            }
            case SISTEMA -> {
                String nombre = p.nombreLogico() == null ? "" : p.nombreLogico().toLowerCase(Locale.ROOT);
                if (!SISTEMA.contains(nombre)) {
                    conflictos.add(Conflicto.bloqueante(Conflicto.Clase.OBLIGATORIA_SIN_ORIGEN, tipo.id(), p.propiedadId(),
                            "Origen sistema desconocido: " + nombre + " (válidos: " + SISTEMA + ")"));
                    return Optional.empty();
                }
                return Optional.ofNullable(sistema.get(nombre));
            }
            case COLUMNA -> {
                if (p.nombreLogico() == null || p.nombreLogico().isBlank()) {
                    conflictos.add(Conflicto.bloqueante(Conflicto.Clase.OBLIGATORIA_SIN_ORIGEN, tipo.id(), p.propiedadId(),
                            "La propiedad " + p.nombre() + " tiene origen columna sin nombre lógico"));
                    return Optional.empty();
                }
                String columna = clave.columna(p.nombreLogico().toLowerCase(Locale.ROOT));
                if (!columnasTabla.isEmpty() && !columnasTabla.contains(columna)) {
                    conflictos.add(Conflicto.bloqueante(Conflicto.Clase.COLUMNA_INEXISTENTE, tipo.id(), p.propiedadId(),
                            "La tabla " + clave.tabla() + " no tiene la columna " + columna + " que pide " + p.nombre()));
                    return Optional.empty();
                }
                // Una lookup viene como _<columna>_value (GUID del registro referido).
                Object v = valores.containsKey(columna) ? valores.get(columna) : valores.get("_" + columna + "_value");
                return Optional.ofNullable(v).filter(x -> !(x instanceof String s && s.isBlank()));
            }
            case CONSULTA -> {
                if (p.consulta() == null || !p.consulta().contains(".")) {
                    conflictos.add(Conflicto.bloqueante(Conflicto.Clase.OBLIGATORIA_SIN_ORIGEN, tipo.id(), p.propiedadId(),
                            "La propiedad " + p.nombre() + " tiene origen consulta sin 'consulta.columna'"));
                    return Optional.empty();
                }
                int punto = p.consulta().indexOf('.');
                String nombreConsulta = p.consulta().substring(0, punto).trim().toLowerCase(Locale.ROOT);
                String columnaResultado = p.consulta().substring(punto + 1).trim().toUpperCase(Locale.ROOT);
                if (!as400.consultasDeclaradas().contains(nombreConsulta)) {
                    conflictos.add(Conflicto.bloqueante(Conflicto.Clase.OBLIGATORIA_SIN_ORIGEN, tipo.id(), p.propiedadId(),
                            "La consulta " + nombreConsulta + " no está declarada en la configuración del AS400"));
                    return Optional.empty();
                }
                Optional<Map<String, Object>> fila = consultas.computeIfAbsent(nombreConsulta,
                        n -> ejecutar(n, clave, valores, observaciones));
                return fila.flatMap(f -> Optional.ofNullable(f.get(columnaResultado)));
            }
            default -> {
                return Optional.empty();
            }
        }
    }

    /**
     * Los parámetros de la consulta se llaman como columnas lógicas de la tabla de adjuntos: {@code :idcliente}
     * se toma de {@code <prefijo>idcliente}. Si falta un parámetro o el AS400 no devuelve fila, es un dato del
     * archivo que falta o es inválido: observación, no conflicto.
     */
    private Optional<Map<String, Object>> ejecutar(String consulta, ClaveAdjunto clave, Map<String, Object> valores,
                                                   List<String> observaciones) {
        Map<String, Object> parametros = new HashMap<>();
        for (Map.Entry<String, Object> e : valores.entrySet()) {
            if (e.getKey().startsWith(clave.prefijo()) && e.getValue() != null) {
                parametros.put(e.getKey().substring(clave.prefijo().length()), e.getValue());
            }
        }
        try {
            Optional<Map<String, Object>> fila = as400.consultar(consulta, parametros);
            if (fila.isEmpty()) {
                observaciones.add("El AS400 no encontró datos con la consulta " + consulta);
            }
            return fila;
        } catch (IllegalArgumentException e) {
            observaciones.add("Falta un dato para la consulta " + consulta + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Validación de existencia ANTES de subir (ADR-003 §4.2, M8): solo las propiedades de origen consulta cuyo
     * momento es "antes de subir". El usuario se entera antes de mover 100 MB. Devuelve observaciones de datos;
     * un problema de catálogo (consulta no declarada) se ignora acá y lo reporta el worker o ValidarTipo.
     */
    public List<String> validarAntesDeSubir(TipoDocumento tipo, ClaveAdjunto clave, Map<String, Object> valores) {
        List<String> observaciones = new ArrayList<>();
        Map<String, Optional<Map<String, Object>>> consultas = new HashMap<>();
        for (PropiedadCm p : tipo.propiedadesEnUso()) {
            if (p.origen() != PropiedadCm.OrigenValor.CONSULTA
                    || p.momentoValidacion() != PropiedadCm.MomentoValidacion.ANTES_DE_SUBIR
                    || p.consulta() == null || !p.consulta().contains(".")) {
                continue;
            }
            String nombreConsulta = p.consulta().substring(0, p.consulta().indexOf('.')).trim().toLowerCase(Locale.ROOT);
            if (!as400.consultasDeclaradas().contains(nombreConsulta)) {
                continue;
            }
            consultas.computeIfAbsent(nombreConsulta, n -> ejecutar(n, clave, valores, observaciones));
        }
        return observaciones;
    }

    private static boolean cumplePatron(PropiedadCm p, String texto) {
        if (p.patron() == null || p.patron().isBlank()) {
            return true;
        }
        try {
            return Pattern.compile(p.patron()).matcher(texto).matches();
        } catch (PatternSyntaxException e) {
            // Un patrón mal escrito en el catálogo no puede castigar al usuario: se ignora y queda en el log del check.
            return true;
        }
    }
}
