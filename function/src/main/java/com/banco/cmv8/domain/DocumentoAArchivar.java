package com.banco.cmv8.domain;

import java.util.Map;
import java.util.Objects;

/**
 * Lo que el worker le pide a Content Manager (ADR-003 §4.2): tipo, carpeta, nombre y las propiedades ya
 * resueltas y convertidas al tipo de dato que CM declara. {@code propiedades} no incluye {@code cmis:name}
 * ni {@code cmis:objectTypeId}: los pone el adaptador.
 */
public record DocumentoAArchivar(
        String tipoCmisId,
        String carpeta,
        String nombre,
        String mimeType,
        boolean versionable,
        Map<String, Object> propiedades) {

    public DocumentoAArchivar {
        Objects.requireNonNull(tipoCmisId, "tipoCmisId");
        Objects.requireNonNull(carpeta, "carpeta");
        Objects.requireNonNull(nombre, "nombre");
        propiedades = propiedades == null ? Map.of() : Map.copyOf(propiedades);
    }
}
