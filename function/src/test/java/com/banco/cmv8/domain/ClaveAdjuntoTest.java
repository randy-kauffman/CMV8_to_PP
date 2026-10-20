package com.banco.cmv8.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaveAdjuntoTest {

    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private final ClaveAdjunto clave = new ClaveAdjunto("cred_adjuntocredito", ID);

    @ParameterizedTest
    @ValueSource(strings = {"cred_adjuntocredito", "ab", "a1_b2", "x_"})
    void aceptaNombresLogicosValidos(String tabla) {
        assertEquals(tabla, new ClaveAdjunto(tabla, ID).tabla());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a", "Cred_adjunto", "1cred", "_cred", "cred-adjunto", "cred_adj;drop", "cred adj",
            "cred_adjunto?$top=1"})
    void rechazaNombresDeTablaInvalidos(String tabla) {
        assertThrows(ValidacionException.class, () -> new ClaveAdjunto(tabla, ID));
    }

    @Test
    void rechazaTablaNulaYTablaDeMasDe64Caracteres() {
        assertThrows(ValidacionException.class, () -> new ClaveAdjunto(null, ID));
        assertThrows(ValidacionException.class, () -> new ClaveAdjunto("a" + "b".repeat(64), ID));
        assertEquals(64, new ClaveAdjunto("a" + "b".repeat(63), ID).tabla().length());
    }

    @Test
    void deValidaQueElIdSeaUnGuid() {
        assertEquals(clave, ClaveAdjunto.de("cred_adjuntocredito", " " + ID + " "));
        assertThrows(ValidacionException.class, () -> ClaveAdjunto.de("cred_adjuntocredito", "no-es-guid"));
        assertThrows(ValidacionException.class, () -> ClaveAdjunto.de("cred_adjuntocredito", " "));
        assertThrows(ValidacionException.class, () -> ClaveAdjunto.de("cred_adjuntocredito", null));
    }

    @Test
    void prefijoSeDerivaDelNombreDeLaTabla() {
        assertEquals("cred_", clave.prefijo());
        assertEquals("ab_", new ClaveAdjunto("ab_c_d", ID).prefijo());
    }

    @Test
    void tablaSinGuionBajoNoTienePrefijo() {
        assertThrows(ValidacionException.class, () -> new ClaveAdjunto("adjunto", ID).prefijo());
    }

    @Test
    void columnaAnteponeElPrefijo() {
        assertEquals("cred_rutablob", clave.columna(Adjunto.COL_RUTA));
        assertEquals("cred_numerocredito", clave.columna("numerocredito"));
    }

    @Test
    void rutaEsTablaIdArchivo() {
        assertEquals("cred_adjuntocredito/" + ID + "/", clave.prefijoRuta());
        assertEquals("cred_adjuntocredito/" + ID + "/contrato.pdf", clave.ruta("contrato.pdf"));
    }

    @Test
    void deRutaLeeTablaEIdDeUnaRutaValida() {
        assertEquals(Optional.of(clave), ClaveAdjunto.deRuta("cred_adjuntocredito/" + ID + "/contrato.pdf"));
        assertEquals(Optional.of(clave), ClaveAdjunto.deRuta("cred_adjuntocredito/" + ID + "/sub/archivo.pdf"));
    }

    @Test
    void deRutaDevuelveVacioSiLaRutaEsInvalida() {
        assertTrue(ClaveAdjunto.deRuta(null).isEmpty());
        assertTrue(ClaveAdjunto.deRuta("cred_adjuntocredito/" + ID).isEmpty());
        assertTrue(ClaveAdjunto.deRuta("cred_adjuntocredito/" + ID + "/").isEmpty());
        assertTrue(ClaveAdjunto.deRuta("cred_adjuntocredito/no-guid/contrato.pdf").isEmpty());
        assertTrue(ClaveAdjunto.deRuta("CRED/" + ID + "/contrato.pdf").isEmpty());
        assertTrue(ClaveAdjunto.deRuta("solicitudes/2026/contrato.pdf").isEmpty());
    }

    @Test
    void deRutaRechazaPuntoPunto() {
        assertTrue(ClaveAdjunto.deRuta("cred_adjuntocredito/" + ID + "/../otro.pdf").isEmpty());
        assertTrue(ClaveAdjunto.deRuta("cred_adjuntocredito/" + ID + "/..").isEmpty());
    }

    @Test
    void esSuRutaSoloParaRutasBajoSuPrefijo() {
        assertTrue(clave.esSuRuta(clave.ruta("contrato.pdf")));
        assertFalse(clave.esSuRuta(clave.prefijoRuta()));
        assertFalse(clave.esSuRuta(null));
        assertFalse(clave.esSuRuta(clave.prefijoRuta() + "../" + UUID.randomUUID() + "/x.pdf"));
        assertFalse(clave.esSuRuta("cred_adjuntocredito/" + UUID.randomUUID() + "/contrato.pdf"));
        assertFalse(clave.esSuRuta("cred_otratabla/" + ID + "/contrato.pdf"));
    }
}
