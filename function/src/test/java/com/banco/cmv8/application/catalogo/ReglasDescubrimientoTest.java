package com.banco.cmv8.application.catalogo;

import com.banco.cmv8.domain.catalogo.DefinicionTipoCmis;
import com.banco.cmv8.domain.catalogo.PropiedadCm;
import com.banco.cmv8.domain.catalogo.TipoDocumento;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReglasDescubrimientoTest {

    private final ReglasDescubrimiento reglas = new ReglasDescubrimiento(Optional.of("IdCorto"), "/Raiz/{localName}");

    private static DefinicionTipoCmis.Propiedad prop(String id, boolean obligatoria, String porDefecto, Integer largo) {
        return new DefinicionTipoCmis.Propiedad(id, id, "string", obligatoria, porDefecto, largo, null, false);
    }

    private static DefinicionTipoCmis def(String id, String nombre, String localName, boolean versionable,
                                          DefinicionTipoCmis.Propiedad... props) {
        return new DefinicionTipoCmis(id, localName, nombre, versionable, "cmis:document", List.of(props));
    }

    private static TipoDocumento guardado(String nombre, String localName, boolean versionable, PropiedadCm... props) {
        return new TipoDocumento(UUID.randomUUID(), "PT95", nombre, TipoDocumento.OrigenTipo.DESCUBIERTO, "clb:T", localName,
                versionable, true, "/Raiz/x", false, null, null, null, false, true, false, List.of(props));
    }

    private PropiedadCm local(DefinicionTipoCmis.Propiedad p) {
        return reglas.propiedadNueva(p);
    }

    @Test
    void codigoCortoSaleDelDefaultDeLaPropiedadConvencional() {
        var t = def("clb:T", "PT96 - Otro nombre", "T", true, prop("clb:IdCorto", false, " PT95 ", null));
        assertEquals(Optional.of("PT95"), reglas.codigoCorto(t));
    }

    @Test
    void codigoCortoSaleDelPrefijoDelNombreSiLaPropiedadNoTieneDefault() {
        var t = def("clb:T", "PT96 - Escritura", "T", true, prop("clb:IdCorto", false, " ", null));
        assertEquals(Optional.of("PT96"), reglas.codigoCorto(t));
        assertEquals(Optional.of("PT96"), new ReglasDescubrimiento(Optional.empty(), "/{localName}").codigoCorto(t));
    }

    @Test
    void sinPropiedadNiPrefijoNoHayCodigo() {
        assertTrue(reglas.codigoCorto(def("clb:T", "Escritura hipotecaria", "T", true)).isEmpty());
        assertTrue(reglas.codigoCorto(def("clb:T", null, "T", true)).isEmpty());
    }

    @Test
    void carpetaDerivadaUsaElLocalNameOElId() {
        assertEquals("/Raiz/Escritura", reglas.carpetaDerivada(def("clb:T", "x", "Escritura", true)));
        assertEquals("/Raiz/clb:T", reglas.carpetaDerivada(def("clb:T", "x", " ", true)));
    }

    @Test
    void decisionSugeridaEsUsarSoloSiEsObligatoriaSinDefault() {
        PropiedadCm usar = reglas.propiedadNueva(prop("clb:A", true, null, 20));
        assertEquals(PropiedadCm.Decision.USAR, usar.decision());
        assertEquals("A", usar.nombre());
        assertEquals(20, usar.largoMaximo());
        assertEquals(PropiedadCm.Decision.OMITIR, reglas.propiedadNueva(prop("clb:B", true, "X", null)).decision());
        assertEquals(PropiedadCm.Decision.OMITIR, reglas.propiedadNueva(prop("clb:C", false, null, null)).decision());
    }

    @Test
    void propiedadActualizadaConservaLoHumano() {
        PropiedadCm actual = new PropiedadCm(UUID.randomUUID(), "clb:A", "A", "string", false, null, 10, null, false,
                PropiedadCm.Decision.USAR, PropiedadCm.OrigenValor.COLUMNA, "numero", null, null, "^\\d+$",
                PropiedadCm.MomentoValidacion.ANTES_DE_SUBIR);
        PropiedadCm nueva = reglas.propiedadActualizada(actual, prop("clb:A", true, "D", 30));
        assertEquals(actual.id(), nueva.id());
        assertEquals(PropiedadCm.Decision.USAR, nueva.decision());
        assertEquals(PropiedadCm.OrigenValor.COLUMNA, nueva.origen());
        assertEquals("numero", nueva.nombreLogico());
        assertEquals("^\\d+$", nueva.patron());
        assertEquals(PropiedadCm.MomentoValidacion.ANTES_DE_SUBIR, nueva.momentoValidacion());
        assertTrue(nueva.obligatoria());
        assertEquals(30, nueva.largoMaximo());
        assertEquals("D", nueva.valorPorDefecto());
    }

    @Test
    void sinCambiosDevuelveListaVacia() {
        var a = prop("clb:A", true, null, 20);
        assertTrue(reglas.cambios(guardado("Crédito ", "T", true, local(a)), def("clb:T", "crédito", "t", true, a)).isEmpty());
    }

    @Test
    void cambiosListaAgregadasQuitadasYModificadas() {
        var a = prop("clb:A", false, null, 20);
        PropiedadCm quitada = reglas.propiedadNueva(prop("clb:Quitada", true, null, null));
        var vivo = def("clb:T", "Nuevo nombre", "T2", false,
                new DefinicionTipoCmis.Propiedad("clb:A", "clb:A", "integer", true, "0", 30, null, false),
                prop("clb:Agregada", true, null, null));

        List<String> cambios = reglas.cambios(guardado("Viejo", "T", true, local(a), quitada), vivo);

        assertTrue(cambios.contains("~ nombre: Viejo → Nuevo nombre"), cambios.toString());
        assertTrue(cambios.contains("~ localName: T → T2"));
        assertTrue(cambios.contains("~ versionable: true → false"));
        assertTrue(cambios.contains("~ clb:A obligatoria: false → true"));
        assertTrue(cambios.contains("~ clb:A largo: 20 → 30"));
        assertTrue(cambios.contains("~ clb:A tipo: string → integer"));
        assertTrue(cambios.contains("~ clb:A default: null → 0"));
        assertTrue(cambios.contains("+ clb:Agregada (obligatoria)"));
        assertTrue(cambios.contains("- clb:Quitada (en uso)"));
        assertEquals(9, cambios.size());
    }

    @Test
    void esGanadorElQueCumpleLaConvencionDelNombre() {
        var primero = def("clb:A", "Escritura", "A", true);
        var convencion = def("clb:B", "PT95 - Escritura", "B", true);
        List<DefinicionTipoCmis> grupo = List.of(primero, convencion);
        assertTrue(ReglasDescubrimiento.esGanador("PT95", convencion, grupo));
        assertFalse(ReglasDescubrimiento.esGanador("PT95", primero, grupo));
    }

    @Test
    void sinConvencionOConVariasGanaElPrimeroPublicado() {
        var a = def("clb:A", "Escritura", "A", true);
        var b = def("clb:B", "Otra", "B", true);
        assertTrue(ReglasDescubrimiento.esGanador("PT95", a, List.of(a, b)));
        assertFalse(ReglasDescubrimiento.esGanador("PT95", b, List.of(a, b)));
        var c = def("clb:C", "PT95 - Uno", "C", true);
        var d = def("clb:D", "PT95 - Dos", "D", true);
        assertTrue(ReglasDescubrimiento.esGanador("PT95", c, List.of(c, d)));
        assertFalse(ReglasDescubrimiento.esGanador("PT95", d, List.of(c, d)));
    }
}
