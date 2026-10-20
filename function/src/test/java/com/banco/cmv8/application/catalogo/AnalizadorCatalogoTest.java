package com.banco.cmv8.application.catalogo;

import com.banco.cmv8.application.catalogo.AnalizadorCatalogo.Hallazgo;
import com.banco.cmv8.application.catalogo.AnalizadorCatalogo.Nivel;
import com.banco.cmv8.domain.catalogo.Conflicto;
import com.banco.cmv8.domain.catalogo.DefinicionTipoCmis;
import com.banco.cmv8.domain.catalogo.PropiedadCm;
import com.banco.cmv8.domain.catalogo.TipoDocumento;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.banco.cmv8.Datos.CARPETA;
import static com.banco.cmv8.Datos.TIPO_CMIS;
import static com.banco.cmv8.Datos.definicion;
import static com.banco.cmv8.Datos.propiedad;
import static com.banco.cmv8.Datos.tipoLocal;
import static com.banco.cmv8.Datos.viva;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalizadorCatalogoTest {

    private static final String P = "clb:Numero";
    private final UUID tipoId = UUID.randomUUID();

    private TipoDocumento tipo(boolean revisado, TipoDocumento.Destino destino, TipoDocumento.Momento momento,
                               Integer dias, boolean inmutable, PropiedadCm... props) {
        return new TipoDocumento(tipoId, "PT95", "PT95 - Crédito", TipoDocumento.OrigenTipo.DESCUBIERTO, TIPO_CMIS,
                "Credito", true, true, CARPETA, false, destino, momento, dias, inmutable, revisado, false, Arrays.asList(props));
    }

    private TipoDocumento tipo(PropiedadCm... props) {
        return tipo(true, TipoDocumento.Destino.CONTENT_MANAGER, TipoDocumento.Momento.AL_SUBIR, null, false, props);
    }

    private static List<Hallazgo> analizar(TipoDocumento tipo, DefinicionTipoCmis def) {
        return AnalizadorCatalogo.analizar(tipo, Optional.of(def), Optional.of(true), Set.of("cliente"));
    }

    private static Hallazgo unico(List<Hallazgo> h) {
        assertEquals(1, h.size(), h.toString());
        return h.get(0);
    }

    private static void criticoConConflicto(Hallazgo h, Conflicto.Clase clase) {
        assertEquals(Nivel.CRITICAL, h.nivel());
        assertEquals(clase, h.conflicto().orElseThrow().clase());
        assertTrue(h.conflicto().get().bloquea());
    }

    private static PropiedadCm usada(PropiedadCm.OrigenValor origen, String nombreLogico, String consulta, String literal,
                                     String patron) {
        return propiedad(P, true, origen, nombreLogico, consulta, literal, patron);
    }

    private static DefinicionTipoCmis conP() {
        return definicion(viva(P, "string", true, 10));
    }

    @Test
    void tipoBienConfiguradoNoTieneHallazgos() {
        assertTrue(analizar(tipo(usada(PropiedadCm.OrigenValor.COLUMNA, "numero", null, null, "^\\d+$")), conP()).isEmpty());
    }

    @Test
    void tipoLocalSeIgnora() {
        assertTrue(AnalizadorCatalogo.analizar(tipoLocal(tipoId, 10), Optional.empty(), Optional.of(false), Set.of()).isEmpty());
    }

    @Test
    void tipoAusenteEsCriticoYCortaElAnalisis() {
        var h = unico(AnalizadorCatalogo.analizar(tipo(false, null, null, null, true), Optional.empty(), Optional.of(false), Set.of()));
        criticoConConflicto(h, Conflicto.Clase.TIPO_AUSENTE);
    }

    @Test
    void tipoNoRevisadoEsWarning() {
        var h = unico(analizar(tipo(false, null, null, null, false), definicion()));
        assertEquals(Nivel.WARNING, h.nivel());
        assertTrue(h.conflicto().isEmpty());
    }

    @Test
    void carpetaInexistenteEsCritica() {
        var h = unico(AnalizadorCatalogo.analizar(tipo(), Optional.of(definicion()), Optional.of(false), Set.of()));
        criticoConConflicto(h, Conflicto.Clase.CARPETA_INEXISTENTE);
    }

    @Test
    void carpetaNoVerificadaNoGeneraHallazgo() {
        assertTrue(AnalizadorCatalogo.analizar(tipo(), Optional.of(definicion()), Optional.empty(), Set.of()).isEmpty());
    }

    @Test
    void inmutableSinDiasEsCriticoSinConflicto() {
        var h = unico(analizar(tipo(true, null, null, null, true), definicion()));
        assertEquals(Nivel.CRITICAL, h.nivel());
        assertTrue(h.conflicto().isEmpty());
    }

    @Test
    void soloBlobDiferidoEsInfo() {
        var h = unico(analizar(tipo(true, TipoDocumento.Destino.SOLO_BLOB, TipoDocumento.Momento.DIFERIDO, null, false),
                definicion()));
        assertEquals(Nivel.INFO, h.nivel());
    }

    @Test
    void propiedadUsadaQueContentManagerNoPublicaEsCritica() {
        var h = unico(analizar(tipo(usada(PropiedadCm.OrigenValor.LITERAL, null, null, "x", null)), definicion()));
        criticoConConflicto(h, Conflicto.Clase.CATALOGO_DESACTUALIZADO);
        assertEquals(P, h.propiedad());
    }

    @Test
    void propiedadUsadaSinOrigenEsCritica() {
        criticoConConflicto(unico(analizar(tipo(usada(null, null, null, null, null)), conP())),
                Conflicto.Clase.OBLIGATORIA_SIN_ORIGEN);
    }

    @Test
    void columnaSinNombreLogicoEsCritica() {
        criticoConConflicto(unico(analizar(tipo(usada(PropiedadCm.OrigenValor.COLUMNA, " ", null, null, null)), conP())),
                Conflicto.Clase.OBLIGATORIA_SIN_ORIGEN);
    }

    @Test
    void consultaMalFormadaEsCritica() {
        criticoConConflicto(unico(analizar(tipo(usada(PropiedadCm.OrigenValor.CONSULTA, null, "cliente.", null, null)), conP())),
                Conflicto.Clase.OBLIGATORIA_SIN_ORIGEN);
        criticoConConflicto(unico(analizar(tipo(usada(PropiedadCm.OrigenValor.CONSULTA, null, null, null, null)), conP())),
                Conflicto.Clase.OBLIGATORIA_SIN_ORIGEN);
    }

    @Test
    void consultaNoDeclaradaEsCriticaYDeclaradaNo() {
        criticoConConflicto(unico(analizar(tipo(usada(PropiedadCm.OrigenValor.CONSULTA, null, "fantasma.X", null, null)), conP())),
                Conflicto.Clase.OBLIGATORIA_SIN_ORIGEN);
        assertTrue(analizar(tipo(usada(PropiedadCm.OrigenValor.CONSULTA, null, "Cliente.NOMBRE", null, null)), conP()).isEmpty());
    }

    @Test
    void sistemaDesconocidoEsCritico() {
        criticoConConflicto(unico(analizar(tipo(usada(PropiedadCm.OrigenValor.SISTEMA, "usuario", null, null, null)), conP())),
                Conflicto.Clase.OBLIGATORIA_SIN_ORIGEN);
    }

    @Test
    void literalVacioEsWarning() {
        assertEquals(Nivel.WARNING, unico(analizar(tipo(usada(PropiedadCm.OrigenValor.LITERAL, null, null, " ", null)), conP())).nivel());
    }

    @Test
    void literalMasLargoQueElMaximoEsWarning() {
        var h = unico(analizar(tipo(usada(PropiedadCm.OrigenValor.LITERAL, null, null, "12345678901", null)), conP()));
        assertEquals(Nivel.WARNING, h.nivel());
        assertTrue(h.mensaje().contains("10"));
    }

    @Test
    void patronInvalidoEsWarning() {
        assertEquals(Nivel.WARNING,
                unico(analizar(tipo(usada(PropiedadCm.OrigenValor.COLUMNA, "numero", null, null, "[abc")), conP())).nivel());
    }

    @Test
    void omitidaQueContentManagerExigeEsCritica() {
        PropiedadCm omitida = propiedad(P, true, null, null, null, null, null).conDecision(PropiedadCm.Decision.OMITIR);
        criticoConConflicto(unico(analizar(tipo(omitida), conP())), Conflicto.Clase.CATALOGO_DESACTUALIZADO);
    }

    @Test
    void catalogoMasEstrictoEsInfoConConflictoDeAviso() {
        var h = unico(analizar(tipo(usada(PropiedadCm.OrigenValor.COLUMNA, "numero", null, null, null)),
                definicion(viva(P, "string", false, null))));
        assertEquals(Nivel.INFO, h.nivel());
        assertEquals(Conflicto.Severidad.AVISO, h.conflicto().orElseThrow().severidad());
    }

    @Test
    void propiedadQueContentManagerExigeYElCatalogoNoConoceEsCritica() {
        criticoConConflicto(unico(analizar(tipo(), definicion(viva("clb:Nueva", "string", true, null)))),
                Conflicto.Clase.CATALOGO_DESACTUALIZADO);
    }

    @Test
    void propiedadNuevaOpcionalEsInfo() {
        var h = unico(analizar(tipo(), definicion(viva("clb:Nueva", "string", false, null))));
        assertEquals(Nivel.INFO, h.nivel());
        assertTrue(h.conflicto().isEmpty());
    }
}
