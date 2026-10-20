package com.banco.cmv8.adapters.out.as400;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsultasAs400Test {

    @Test
    void reescribeParametrosConNombreEnOrdenIncluidoRepetidos() {
        ConsultasAs400 consultas = ConsultasAs400.parsear(
                "{\"cuenta\": \"SELECT NOMBRE FROM LIB.CTA WHERE CTA = :cuenta AND SUC = :sucursal OR ALT = :cuenta\"}",
                Optional.empty());

        ConsultasAs400.Consulta c = consultas.consulta("cuenta").orElseThrow();
        assertEquals("SELECT NOMBRE FROM LIB.CTA WHERE CTA = ? AND SUC = ? OR ALT = ?", c.sql());
        assertEquals(List.of("cuenta", "sucursal", "cuenta"), c.parametros());
        assertEquals(Set.of("cuenta"), consultas.nombres());
    }

    @Test
    void noTocaLoQueEstaEntreComillas() {
        ConsultasAs400.Consulta c = ConsultasAs400.reescribir("x",
                "SELECT 'a:b', \"COL:X\" FROM T WHERE A = :uno AND B = 'it''s :no'");

        assertEquals("SELECT 'a:b', \"COL:X\" FROM T WHERE A = ? AND B = 'it''s :no'", c.sql());
        assertEquals(List.of("uno"), c.parametros());
    }

    @Test
    void variasConsultasConservanElOrdenDeclarado() {
        ConsultasAs400 consultas = ConsultasAs400.parsear(
                "{\"cliente\": \"SELECT 1 FROM T WHERE ID = :idCliente\", \"oficina\": \"SELECT 2 FROM O WHERE O = :oficina\"}",
                Optional.of("SELECT * FROM V1 WHERE ID = ?"));

        assertEquals(List.of("cliente", "oficina"), List.copyOf(consultas.nombres()));
        // Con JSON, la consulta v1 se ignora.
        assertEquals("SELECT 1 FROM T WHERE ID = ?", consultas.consulta("cliente").orElseThrow().sql());
    }

    @Test
    void jsonVacioUsaLaConsultaV1ComoCliente() {
        ConsultasAs400 consultas = ConsultasAs400.parsear("  ", Optional.of("SELECT * FROM LIB.CLI WHERE ID = ?"));

        ConsultasAs400.Consulta c = consultas.consulta(ConsultasAs400.CONSULTA_V1).orElseThrow();
        assertEquals("SELECT * FROM LIB.CLI WHERE ID = ?", c.sql());
        assertEquals(List.of(ConsultasAs400.PARAMETRO_V1), c.parametros());
    }

    @Test
    void sinJsonNiV1NoHayConsultas() {
        assertTrue(ConsultasAs400.parsear("", Optional.empty()).nombres().isEmpty());
        assertTrue(ConsultasAs400.parsear(null, null).nombres().isEmpty());
    }

    @Test
    void consultaV1ConMasDeUnMarcadorSeRechaza() {
        assertThrows(IllegalArgumentException.class,
                () -> ConsultasAs400.parsear("", Optional.of("SELECT * FROM T WHERE A = ? AND B = ?")));
    }

    @Test
    void nombreInvalidoSeRechaza() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ConsultasAs400.parsear("{\"Cliente-X\": \"SELECT 1 FROM T WHERE A = :a\"}", Optional.empty()));
        assertTrue(e.getMessage().contains("Cliente-X"));
        assertThrows(IllegalArgumentException.class,
                () -> ConsultasAs400.parsear("{\"1cliente\": \"SELECT 1\"}", Optional.empty()));
    }

    @Test
    void marcadorPosicionalEnJsonSeRechaza() {
        assertThrows(IllegalArgumentException.class,
                () -> ConsultasAs400.parsear("{\"x\": \"SELECT 1 FROM T WHERE A = ?\"}", Optional.empty()));
    }

    @Test
    void jsonQueNoEsObjetoOValorNoTextualSeRechaza() {
        assertThrows(IllegalArgumentException.class, () -> ConsultasAs400.parsear("[1]", Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> ConsultasAs400.parsear("{\"x\": 3}", Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> ConsultasAs400.parsear("{no es json", Optional.empty()));
    }

    @Test
    void parametroFaltanteONuloSeRechazaConSuNombre() {
        ConsultasAs400.Consulta c = ConsultasAs400.reescribir("cuenta", "SELECT 1 FROM T WHERE A = :cuenta AND B = :sucursal");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ResolutorAs400Jt400.valoresEnOrden(c, java.util.Map.of("cuenta", "123")));
        assertTrue(e.getMessage().contains("sucursal"));

        java.util.Map<String, Object> conNulo = new java.util.HashMap<>();
        conNulo.put("cuenta", "123");
        conNulo.put("sucursal", null);
        assertThrows(IllegalArgumentException.class, () -> ResolutorAs400Jt400.valoresEnOrden(c, conNulo));
    }

    @Test
    void valoresEnElOrdenDeLosMarcadores() {
        ConsultasAs400.Consulta c = ConsultasAs400.reescribir("x", "SELECT 1 FROM T WHERE A = :b AND B = :a AND C = :b");

        assertEquals(List.of("vb", 7, "vb"), ResolutorAs400Jt400.valoresEnOrden(c, java.util.Map.of("a", 7, "b", "vb")));
    }
}
