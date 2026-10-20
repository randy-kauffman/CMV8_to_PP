package com.banco.cmv8.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Identidad de un adjunto: tabla lógica de Dataverse + id de la fila (ADR-003 §1.1). La tabla la manda el
 * cliente y se valida con una expresión regular para que no pueda inyectar nada en la URL de OData; la lista
 * de permitidas son los privilegios de Dataverse. La ruta del blob es {@code <tabla>/<id>/<archivo>}.
 */
public record ClaveAdjunto(String tabla, UUID id) {

    /** Nombre lógico de Dataverse: minúsculas, dígitos y guion bajo, 2 a 64 caracteres. */
    public static final Pattern TABLA_VALIDA = Pattern.compile("^[a-z][a-z0-9_]{1,63}$");

    public ClaveAdjunto {
        Objects.requireNonNull(id, "id");
        if (tabla == null || !TABLA_VALIDA.matcher(tabla).matches()) {
            throw new ValidacionException("Nombre de tabla inválido");
        }
    }

    public static ClaveAdjunto de(String tabla, String id) {
        if (id == null || id.isBlank()) {
            throw new ValidacionException("El id del adjunto es obligatorio");
        }
        try {
            return new ClaveAdjunto(tabla, UUID.fromString(id.trim()));
        } catch (IllegalArgumentException e) {
            throw new ValidacionException("El id del adjunto no es un GUID");
        }
    }

    /**
     * Prefijo del publisher derivado del nombre de la tabla ({@code cred_adjuntocredito} → {@code cred_}).
     * Regla de la guía: las columnas reservadas se crean en la misma solución que la tabla.
     */
    public String prefijo() {
        int guion = tabla.indexOf('_');
        if (guion <= 0) {
            throw new ValidacionException("La tabla no tiene prefijo de publisher");
        }
        return tabla.substring(0, guion + 1);
    }

    /** Nombre lógico completo de una columna reservada o convenida: prefijo + nombre lógico. */
    public String columna(String nombreLogico) {
        return prefijo() + nombreLogico;
    }

    /** Prefijo de todas las rutas de blob de este adjunto. */
    public String prefijoRuta() {
        return tabla + "/" + id + "/";
    }

    public String ruta(String nombreArchivoSaneado) {
        return prefijoRuta() + nombreArchivoSaneado;
    }

    /** Lee tabla e id del comienzo de una ruta {@code <tabla>/<id>/<archivo>}. */
    public static Optional<ClaveAdjunto> deRuta(String ruta) {
        if (ruta == null || ruta.contains("..")) {
            return Optional.empty();
        }
        String[] partes = ruta.split("/", 3);
        if (partes.length < 3 || partes[2].isEmpty() || !TABLA_VALIDA.matcher(partes[0]).matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ClaveAdjunto(partes[0], UUID.fromString(partes[1])));
        } catch (IllegalArgumentException | ValidacionException e) {
            return Optional.empty();
        }
    }

    /** true si la ruta está bajo el prefijo del adjunto y no intenta salir de él. */
    public boolean esSuRuta(String ruta) {
        return ruta != null && !ruta.contains("..") && ruta.startsWith(prefijoRuta())
                && ruta.length() > prefijoRuta().length();
    }

    @Override
    public String toString() {
        return tabla + "/" + id;
    }
}
