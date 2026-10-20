package com.banco.cmv8.application.catalogo;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.GregorianCalendar;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversorCmisTest {

    private static Instant instante(Optional<Object> v) {
        return ((GregorianCalendar) v.orElseThrow()).toZonedDateTime().toInstant();
    }

    @Test
    void nuloEsVacio() {
        assertTrue(ConversorCmis.convertir(null, "string").isEmpty());
    }

    @Test
    void textoPorDefectoRecortaEspacios() {
        assertEquals(Optional.of("abc"), ConversorCmis.convertir("  abc ", "string"));
        assertEquals(Optional.of("abc"), ConversorCmis.convertir("abc", null));
        assertEquals(Optional.of("12"), ConversorCmis.convertir(12, "id"));
    }

    @Test
    void enteroDesdeNumeroOTexto() {
        assertEquals(Optional.of(BigInteger.valueOf(42)), ConversorCmis.convertir(42, "integer"));
        assertEquals(Optional.of(BigInteger.valueOf(42)), ConversorCmis.convertir(" 42 ", "Integer"));
        assertTrue(ConversorCmis.convertir("4x", "integer").isEmpty());
    }

    @Test
    void decimalDesdeNumeroOTexto() {
        assertEquals(Optional.of(new BigDecimal("10.5")), ConversorCmis.convertir("10.5", "decimal"));
        assertEquals(Optional.of(new BigDecimal("2.25")), ConversorCmis.convertir(new BigDecimal("2.25"), "decimal"));
        assertEquals(Optional.of(BigDecimal.valueOf(1.5)), ConversorCmis.convertir(1.5d, "decimal"));
        assertTrue(ConversorCmis.convertir("diez", "decimal").isEmpty());
    }

    @Test
    void booleanoAceptaVariantes() {
        for (String s : new String[]{"true", "1", "si", "Sí", "YES"}) {
            assertEquals(Optional.of(Boolean.TRUE), ConversorCmis.convertir(s, "boolean"), s);
        }
        for (String s : new String[]{"false", "0", "No"}) {
            assertEquals(Optional.of(Boolean.FALSE), ConversorCmis.convertir(s, "boolean"), s);
        }
        assertEquals(Optional.of(Boolean.TRUE), ConversorCmis.convertir(Boolean.TRUE, "boolean"));
        assertTrue(ConversorCmis.convertir("quizás", "boolean").isEmpty());
    }

    @Test
    void fechaDesdeIsoConZonaSoloFechaInstantUOffsetDateTime() {
        assertEquals(Instant.parse("2026-10-19T15:30:00Z"),
                instante(ConversorCmis.convertir("2026-10-19T09:30:00-06:00", "datetime")));
        assertEquals(Instant.parse("2026-10-19T00:00:00Z"), instante(ConversorCmis.convertir("2026-10-19", "datetime")));
        Instant i = Instant.parse("2026-01-02T03:04:05Z");
        assertEquals(i, instante(ConversorCmis.convertir(i, "datetime")));
        assertEquals(i, instante(ConversorCmis.convertir(OffsetDateTime.parse("2026-01-02T03:04:05Z"), "datetime")));
    }

    @Test
    void fechaInvalidaEsVacio() {
        assertTrue(ConversorCmis.convertir("19/10/2026", "datetime").isEmpty());
    }
}
