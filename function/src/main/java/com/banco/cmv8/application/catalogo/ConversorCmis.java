package com.banco.cmv8.application.catalogo;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.Optional;

/**
 * Convierte el valor resuelto de una propiedad al tipo Java que OpenCMIS espera para el tipo de dato que
 * Content Manager declara (ADR-003 §4.2). Hoy la v1 mandaba todo como texto; la definición viva dice qué es.
 */
public final class ConversorCmis {

    private ConversorCmis() {
    }

    /**
     * @return el valor convertido, o vacío si no se puede interpretar (observación de datos, no error técnico)
     */
    public static Optional<Object> convertir(Object valor, String tipoDato) {
        if (valor == null) {
            return Optional.empty();
        }
        String tipo = tipoDato == null ? "string" : tipoDato.toLowerCase(Locale.ROOT);
        try {
            return Optional.of(switch (tipo) {
                case "integer" -> valor instanceof Number n ? BigInteger.valueOf(n.longValue())
                        : new BigInteger(texto(valor));
                case "decimal" -> valor instanceof BigDecimal d ? d
                        : valor instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : new BigDecimal(texto(valor));
                case "boolean" -> valor instanceof Boolean b ? b : interpretarBooleano(texto(valor));
                case "datetime" -> calendario(valor);
                default -> texto(valor);
            });
        } catch (IllegalArgumentException | DateTimeParseException e) {
            return Optional.empty();
        }
    }

    public static String texto(Object valor) {
        return valor instanceof String s ? s.trim() : String.valueOf(valor);
    }

    private static Boolean interpretarBooleano(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "true", "1", "si", "sí", "yes" -> Boolean.TRUE;
            case "false", "0", "no" -> Boolean.FALSE;
            default -> throw new IllegalArgumentException("No es booleano: " + s);
        };
    }

    private static GregorianCalendar calendario(Object valor) {
        Instant instante;
        if (valor instanceof Instant i) {
            instante = i;
        } else if (valor instanceof OffsetDateTime o) {
            instante = o.toInstant();
        } else {
            String s = texto(valor);
            try {
                instante = OffsetDateTime.parse(s).toInstant();
            } catch (DateTimeParseException e) {
                // Dataverse entrega fechas sin hora como yyyy-MM-dd
                instante = LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant();
            }
        }
        return GregorianCalendar.from(instante.atZone(ZoneOffset.UTC));
    }
}
