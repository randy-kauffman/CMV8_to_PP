package com.banco.cmv8.domain.catalogo;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Propiedad escribible de un tipo descubierto (ADR-003 §1.2). Lo descubierto lo escribe la Function desde la
 * definición CMIS; lo humano es la decisión usar/omitir y de dónde sale el valor.
 *
 * @param propiedadId  id de wire completo, con prefijo, tal como lo exige Content Manager
 * @param nombre       nombre canónico (id de wire sin prefijo): la clave que usa el maker
 * @param nombreLogico para origen COLUMNA: nombre sin prefijo en la tabla de adjuntos; para SISTEMA: id,
 *                     nombrearchivo, contenttype
 * @param consulta     para origen CONSULTA: {@code nombreConsulta.columna} (el SQL vive en Azure)
 */
public record PropiedadCm(
        UUID id,
        String propiedadId,
        String nombre,
        String tipoDato,
        boolean obligatoria,
        String valorPorDefecto,
        Integer largoMaximo,
        List<String> listaValores,
        boolean heredada,
        Decision decision,
        OrigenValor origen,
        String nombreLogico,
        String consulta,
        String literal,
        String patron,
        MomentoValidacion momentoValidacion) {

    public enum Decision { USAR(1), OMITIR(2); public final int codigo; Decision(int c) { codigo = c; } }
    public enum OrigenValor { COLUMNA(1), CONSULTA(2), SISTEMA(3), LITERAL(4); public final int codigo; OrigenValor(int c) { codigo = c; } }
    public enum MomentoValidacion { AL_ARCHIVAR(1), ANTES_DE_SUBIR(2); public final int codigo; MomentoValidacion(int c) { codigo = c; } }

    public PropiedadCm {
        Objects.requireNonNull(propiedadId, "propiedadId");
        listaValores = listaValores == null ? List.of() : List.copyOf(listaValores);
        decision = decision == null ? Decision.OMITIR : decision;
        momentoValidacion = momentoValidacion == null ? MomentoValidacion.AL_ARCHIVAR : momentoValidacion;
    }

    /** Regla de CMCourier: usar si es obligatoria sin default; si no, omitir (gana el default del servidor). */
    public static Decision decisionSugerida(boolean obligatoria, String valorPorDefecto) {
        return obligatoria && (valorPorDefecto == null || valorPorDefecto.isBlank()) ? Decision.USAR : Decision.OMITIR;
    }

    /** Nombre canónico: el id de wire sin su prefijo hasta el último '.' o ':' ({@code clb.X} → {@code X}). */
    public static String nombreCanonico(String propiedadId) {
        int corte = Math.max(propiedadId.lastIndexOf('.'), propiedadId.lastIndexOf(':'));
        return corte >= 0 ? propiedadId.substring(corte + 1) : propiedadId;
    }

    public boolean seUsa() {
        return decision == Decision.USAR;
    }

    /** Una propiedad que se usa sin origen declarado es el CRITICAL número uno del check. */
    public boolean usaSinOrigen() {
        return seUsa() && origen == null;
    }

    public PropiedadCm conDecision(Decision nueva) {
        return new PropiedadCm(id, propiedadId, nombre, tipoDato, obligatoria, valorPorDefecto, largoMaximo, listaValores,
                heredada, nueva, origen, nombreLogico, consulta, literal, patron, momentoValidacion);
    }
}
