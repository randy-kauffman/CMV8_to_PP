package com.banco.cmv8.adapters.in;

import java.net.URI;
import java.util.Optional;

/** Convierte la URL de un blob (blobUri / url de los eventos) en la ruta relativa al contenedor. */
public final class UbicacionBlob {

    private UbicacionBlob() {
    }

    /**
     * {@code https://cuenta.blob.core.windows.net/cmv8-ingesta/<id>/<archivo>} → {@code <id>/<archivo>}.
     * Vacío si la URL no es de ese contenedor o no se puede interpretar.
     */
    public static Optional<String> rutaRelativa(String urlBlob, String contenedor) {
        if (urlBlob == null || urlBlob.isBlank() || contenedor == null || contenedor.isBlank()) {
            return Optional.empty();
        }
        String ruta;
        try {
            ruta = URI.create(urlBlob.trim()).getPath(); // decodifica %xx
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (ruta == null) {
            return Optional.empty();
        }
        String prefijo = "/" + contenedor + "/";
        if (!ruta.startsWith(prefijo) || ruta.length() == prefijo.length()) {
            return Optional.empty();
        }
        return Optional.of(ruta.substring(prefijo.length()));
    }
}
