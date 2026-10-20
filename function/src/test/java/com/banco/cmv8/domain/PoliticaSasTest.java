package com.banco.cmv8.domain;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoliticaSasTest {

    private static final Instant AHORA = Instant.parse("2026-10-13T12:00:00Z");
    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private final PoliticaSas politica = new PoliticaSas(1000, Clock.fixed(AHORA, ZoneOffset.UTC));

    @Test
    void conservaCaracteresPermitidos() {
        assertEquals("Factura_2026-10.v2.pdf", politica.sanearNombre("Factura_2026-10.v2.pdf"));
    }

    @Test
    void reemplazaCaracteresNoPermitidos() {
        assertEquals("mi_video__final_.mp4", politica.sanearNombre("mi video (final).mp4"));
    }

    @Test
    void conservaLetrasAcentuadas() {
        assertEquals("canción_ñandú.mp3", politica.sanearNombre("canción ñandú.mp3"));
    }

    @Test
    void rechazaPathTraversal() {
        assertThrows(ValidacionException.class, () -> politica.sanearNombre("../../etc/passwd"));
        assertThrows(ValidacionException.class, () -> politica.sanearNombre("..\\windows"));
        assertThrows(ValidacionException.class, () -> politica.sanearNombre("carpeta/archivo.pdf"));
        assertThrows(ValidacionException.class, () -> politica.sanearNombre(".."));
    }

    @Test
    void rechazaNombreVacioOSinCaracteresValidos() {
        assertThrows(ValidacionException.class, () -> politica.sanearNombre(null));
        assertThrows(ValidacionException.class, () -> politica.sanearNombre("   "));
        assertThrows(ValidacionException.class, () -> politica.sanearNombre("###"));
    }

    @Test
    void noPermiteNombreOcultoQueEmpiezaConPunto() {
        assertEquals("_bashrc", politica.sanearNombre(".bashrc"));
    }

    @Test
    void truncaA200ConservandoExtension() {
        String largo = "a".repeat(300) + ".pdf";
        String saneado = politica.sanearNombre(largo);
        assertEquals(PoliticaSas.LARGO_MAXIMO_NOMBRE, saneado.length());
        assertTrue(saneado.endsWith(".pdf"));
    }

    @Test
    void construyeRutaConTablaIdYNombreSaneado() {
        ClaveAdjunto clave = new ClaveAdjunto("cred_adjuntocredito", ID);
        assertEquals("cred_adjuntocredito/" + ID + "/mi_archivo.pdf", politica.construirRuta(clave, "mi archivo.pdf"));
    }

    @Test
    void validaTamano() {
        politica.validarTamano(1L);
        politica.validarTamano(1000L);
        assertThrows(ValidacionException.class, () -> politica.validarTamano(0L));
        assertThrows(ValidacionException.class, () -> politica.validarTamano(-5L));
        assertThrows(ValidacionException.class, () -> politica.validarTamano(null));
        assertThrows(ValidacionException.class, () -> politica.validarTamano(1001L));
    }

    @Test
    void limitePorDefectoEsUnGB() {
        PoliticaSas porDefecto = new PoliticaSas();
        porDefecto.validarTamano(1L << 30);
        assertThrows(ValidacionException.class, () -> porDefecto.validarTamano((1L << 30) + 1));
    }

    @Test
    void calculaExpiracionEInicio() {
        assertEquals(OffsetDateTime.parse("2026-10-13T12:15:00Z"), politica.calcularExpiracion(Duration.ofMinutes(15)));
        assertEquals(OffsetDateTime.parse("2026-10-13T11:55:00Z"), politica.calcularInicio());
    }

    @Test
    void acotaLaVigenciaAUnaHora() {
        assertEquals(OffsetDateTime.parse("2026-10-13T13:00:00Z"), politica.calcularExpiracion(Duration.ofHours(5)));
    }

    @Test
    void rechazaVigenciaNoPositiva() {
        assertThrows(IllegalArgumentException.class, () -> politica.calcularExpiracion(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> politica.calcularExpiracion(Duration.ofMinutes(-1)));
    }
}
