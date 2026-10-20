package com.banco.cmv8.domain.catalogo;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Tipo de documento del catálogo (ADR-003 §1.2). Los descubiertos vienen de Content Manager y tienen ItemType;
 * los locales los crea el administrador y nunca van a Content Manager.
 *
 * @param codigo        ID corto (derivado al descubrir; editable)
 * @param carpetaManual la carpeta la fijó un humano y ActualizarTipos no la pisa
 * @param diasRetencion nulo = permanente
 */
public record TipoDocumento(
        UUID id,
        String codigo,
        String nombre,
        OrigenTipo origen,
        String tipoCmisId,
        String localName,
        boolean versionable,
        boolean enUso,
        String carpeta,
        boolean carpetaManual,
        Destino destino,
        Momento momento,
        Integer diasRetencion,
        boolean inmutable,
        boolean revisado,
        boolean ausenteEnServidor,
        List<PropiedadCm> propiedades) {

    public enum OrigenTipo { DESCUBIERTO(1), LOCAL(2); public final int codigo; OrigenTipo(int c) { codigo = c; } }
    public enum Destino { CONTENT_MANAGER(1), SOLO_BLOB(2); public final int codigo; Destino(int c) { codigo = c; } }
    public enum Momento { AL_SUBIR(1), DIFERIDO(2); public final int codigo; Momento(int c) { codigo = c; } }

    public TipoDocumento {
        Objects.requireNonNull(origen, "origen");
        destino = destino == null ? (origen == OrigenTipo.LOCAL ? Destino.SOLO_BLOB : Destino.CONTENT_MANAGER) : destino;
        momento = momento == null ? Momento.AL_SUBIR : momento;
        propiedades = propiedades == null ? List.of() : List.copyOf(propiedades);
    }

    /** Solo se valida contra Content Manager cuando el tipo tiene ItemType (precisión del usuario, M6). */
    public boolean tieneItemType() {
        return origen == OrigenTipo.DESCUBIERTO && tipoCmisId != null && !tipoCmisId.isBlank();
    }

    public boolean vaAContentManager() {
        return destino == Destino.CONTENT_MANAGER && tieneItemType();
    }

    public boolean esDiferido() {
        return vaAContentManager() && momento == Momento.DIFERIDO;
    }

    /** Tras archivar, ¿el blob se queda en almacenados? Solo si hay retención (días o permanente con inmutable). */
    public boolean retieneTrasArchivar() {
        return diasRetencion != null || inmutable;
    }

    public Optional<Integer> retencion() {
        return Optional.ofNullable(diasRetencion);
    }

    public List<PropiedadCm> propiedadesEnUso() {
        return propiedades.stream().filter(p -> p.decision() == PropiedadCm.Decision.USAR).toList();
    }

    public TipoDocumento conPropiedades(List<PropiedadCm> nuevas) {
        return new TipoDocumento(id, codigo, nombre, origen, tipoCmisId, localName, versionable, enUso, carpeta,
                carpetaManual, destino, momento, diasRetencion, inmutable, revisado, ausenteEnServidor, nuevas);
    }
}
