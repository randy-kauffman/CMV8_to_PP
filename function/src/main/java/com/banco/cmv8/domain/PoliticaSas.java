package com.banco.cmv8.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Reglas puras del broker de SAS: ruta del blob, saneo del nombre de archivo, límite de tamaño y vigencia.
 * Sin dependencias de Azure. La ruta es {@code <tabla>/<id>/<archivo>} (ADR-003 §1.1).
 */
public final class PoliticaSas {

    /** 1 GB. */
    public static final long LIMITE_POR_DEFECTO_BYTES = 1L << 30;
    /** Igual a sasPolicy.sasExpirationPeriod de la cuenta de adjuntos (infra/main.bicep). */
    public static final Duration VIGENCIA_MAXIMA = Duration.ofHours(1);
    /** Margen hacia atrás por desfase de relojes entre la Function, el navegador y Storage. */
    public static final Duration MARGEN_RELOJ = Duration.ofMinutes(5);
    public static final int LARGO_MAXIMO_NOMBRE = 200;
    private static final int LARGO_MAXIMO_EXTENSION = 20;

    private final long limiteBytes;
    private final Clock reloj;

    public PoliticaSas(long limiteBytes, Clock reloj) {
        if (limiteBytes <= 0) {
            throw new IllegalArgumentException("El límite de tamaño debe ser positivo");
        }
        this.limiteBytes = limiteBytes;
        this.reloj = reloj;
    }

    public PoliticaSas() {
        this(LIMITE_POR_DEFECTO_BYTES, Clock.systemUTC());
    }

    public long limiteBytes() {
        return limiteBytes;
    }

    /**
     * Deja solo letras, dígitos, '.', '-' y '_'; el resto se reemplaza por '_'.
     * Rechaza separadores de ruta y secuencias '..' (path traversal). Máximo 200 caracteres,
     * conservando la extensión si cabe.
     */
    public String sanearNombre(String nombreArchivo) {
        if (nombreArchivo == null || nombreArchivo.isBlank()) {
            throw new ValidacionException("El nombre de archivo es obligatorio");
        }
        String nombre = nombreArchivo.trim();
        if (nombre.contains("/") || nombre.contains("\\") || nombre.contains("..") || nombre.indexOf('\0') >= 0) {
            throw new ValidacionException("El nombre de archivo no puede contener rutas ni '..'");
        }
        StringBuilder sb = new StringBuilder(nombre.length());
        nombre.codePoints().forEach(cp -> {
            if (Character.isLetterOrDigit(cp) || cp == '.' || cp == '-' || cp == '_') {
                sb.appendCodePoint(cp);
            } else {
                sb.append('_');
            }
        });
        String saneado = sb.toString();
        if (saneado.chars().allMatch(c -> c == '.' || c == '_')) {
            throw new ValidacionException("El nombre de archivo no tiene caracteres válidos");
        }
        if (saneado.startsWith(".")) {
            saneado = "_" + saneado.substring(1);
        }
        return truncar(saneado);
    }

    private static String truncar(String nombre) {
        if (nombre.length() <= LARGO_MAXIMO_NOMBRE) {
            return nombre;
        }
        int punto = nombre.lastIndexOf('.');
        if (punto > 0 && nombre.length() - punto <= LARGO_MAXIMO_EXTENSION) {
            String extension = nombre.substring(punto);
            return nombre.substring(0, LARGO_MAXIMO_NOMBRE - extension.length()) + extension;
        }
        return nombre.substring(0, LARGO_MAXIMO_NOMBRE);
    }

    /** Ruta relativa al contenedor: {@code <tabla>/<id>/<nombreArchivoSaneado>}. */
    public String construirRuta(ClaveAdjunto clave, String nombreArchivo) {
        if (clave == null) {
            throw new ValidacionException("La clave del adjunto es obligatoria");
        }
        return clave.ruta(sanearNombre(nombreArchivo));
    }

    public void validarTamano(Long tamanoBytes) {
        if (tamanoBytes == null || tamanoBytes <= 0) {
            throw new ValidacionException("El tamaño del archivo debe ser mayor que cero");
        }
        if (tamanoBytes > limiteBytes) {
            throw new ValidacionException("El archivo supera el máximo permitido de " + limiteBytes + " bytes");
        }
    }

    /** Vencimiento de la SAS: ahora + vigencia, acotada a {@link #VIGENCIA_MAXIMA}. */
    public OffsetDateTime calcularExpiracion(Duration vigencia) {
        if (vigencia == null || vigencia.isZero() || vigencia.isNegative()) {
            throw new IllegalArgumentException("La vigencia de la SAS debe ser positiva");
        }
        Duration efectiva = vigencia.compareTo(VIGENCIA_MAXIMA) > 0 ? VIGENCIA_MAXIMA : vigencia;
        return ahora().plus(efectiva);
    }

    /** Inicio de validez de la SAS (con margen por desfase de relojes). */
    public OffsetDateTime calcularInicio() {
        return ahora().minus(MARGEN_RELOJ);
    }

    private OffsetDateTime ahora() {
        return OffsetDateTime.now(reloj).withOffsetSameInstant(ZoneOffset.UTC);
    }
}
