package com.banco.cmv8.domain.catalogo;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Lo que Content Manager publica de un tipo por CMIS ({@code typeDescendants} / {@code getTypeDefinition}).
 * Es la fuente de verdad del catálogo: no se tipea, se descubre (ADR-003 §1.2, patrón de CMCourier).
 *
 * @param propiedades solo las escribibles ({@code updatability} readwrite u oncreate); {@code cmis:name} y
 *                    {@code cmis:objectTypeId} se excluyen porque los pone el código
 */
public record DefinicionTipoCmis(
        String tipoId,
        String localName,
        String nombreVisible,
        boolean versionable,
        String tipoPadreId,
        List<Propiedad> propiedades) {

    public record Propiedad(
            String id,
            String nombreVisible,
            String tipoDato,
            boolean obligatoria,
            String valorPorDefecto,
            Integer largoMaximo,
            List<String> listaValores,
            boolean heredada) {

        public Propiedad {
            Objects.requireNonNull(id, "id");
            listaValores = listaValores == null ? List.of() : List.copyOf(listaValores);
        }

        public boolean obligatoriaSinDefault() {
            return obligatoria && (valorPorDefecto == null || valorPorDefecto.isBlank());
        }
    }

    public DefinicionTipoCmis {
        Objects.requireNonNull(tipoId, "tipoId");
        propiedades = propiedades == null ? List.of() : List.copyOf(propiedades);
    }

    public Optional<Propiedad> propiedad(String id) {
        return propiedades.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    /** Propiedad cuyo nombre canónico coincide (sin distinguir mayúsculas). */
    public Optional<Propiedad> propiedadPorNombre(String nombreCanonico) {
        return propiedades.stream()
                .filter(p -> PropiedadCm.nombreCanonico(p.id()).equalsIgnoreCase(nombreCanonico))
                .findFirst();
    }
}
