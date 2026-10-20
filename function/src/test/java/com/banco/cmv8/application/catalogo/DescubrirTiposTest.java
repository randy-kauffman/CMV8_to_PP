package com.banco.cmv8.application.catalogo;

import com.banco.cmv8.application.AccesoDenegadoException;
import com.banco.cmv8.domain.ContextoLlamador;
import com.banco.cmv8.domain.catalogo.DefinicionTipoCmis;
import com.banco.cmv8.domain.catalogo.PropiedadCm;
import com.banco.cmv8.domain.catalogo.TipoDocumento;
import com.banco.cmv8.domain.port.ArchivoDocumental;
import com.banco.cmv8.domain.port.CatalogoTipos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.banco.cmv8.Datos.LOG;
import static com.banco.cmv8.Datos.llamador;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DescubrirTiposTest {

    @Mock
    private ArchivoDocumental archivo;
    @Mock
    private CatalogoTipos catalogo;
    @Mock
    private CatalogoEnCache cache;

    private final ContextoLlamador llamador = llamador();
    private final ReglasDescubrimiento reglas = new ReglasDescubrimiento(Optional.empty(), "/Raiz/{localName}");

    private DescubrirTipos servicio() {
        return new DescubrirTipos(archivo, catalogo, cache, reglas);
    }

    private static DefinicionTipoCmis.Propiedad prop(String id, boolean obligatoria, Integer largo) {
        return new DefinicionTipoCmis.Propiedad(id, id, "string", obligatoria, null, largo, null, false);
    }

    private static DefinicionTipoCmis def(String id, String nombre, String localName, DefinicionTipoCmis.Propiedad... props) {
        return new DefinicionTipoCmis(id, localName, nombre, true, "cmis:document", List.of(props));
    }

    /** Lo que quedó guardado tras un descubrimiento anterior de {@code vivo}, con decisiones humanas. */
    private TipoDocumento guardadoDe(DefinicionTipoCmis vivo, boolean enUso, String carpeta, boolean carpetaManual,
                                     List<PropiedadCm> props) {
        return new TipoDocumento(UUID.randomUUID(), "PT95", vivo.nombreVisible(), TipoDocumento.OrigenTipo.DESCUBIERTO,
                vivo.tipoId(), vivo.localName(), vivo.versionable(), enUso, carpeta, carpetaManual,
                TipoDocumento.Destino.SOLO_BLOB, TipoDocumento.Momento.AL_SUBIR, 30, false, true, false, props);
    }

    private List<PropiedadCm> decididas(DefinicionTipoCmis vivo) {
        return vivo.propiedades().stream().map(reglas::propiedadNueva)
                .map(p -> p.conDecision(PropiedadCm.Decision.OMITIR)).toList();
    }

    @Test
    void sinRolDeAdministradorEsAccesoDenegado() {
        when(catalogo.puedeAdministrar(llamador)).thenReturn(false);
        assertThrows(AccesoDenegadoException.class, () -> servicio().ejecutar(false, llamador, LOG));
        verifyNoInteractions(archivo, cache);
        verify(catalogo, never()).guardarTipoDescubierto(any(), any(), anyBoolean());
    }

    @Test
    void tipoNuevoSeCreaConCodigoCarpetaDerivadaYDecisionSugerida() {
        when(catalogo.puedeAdministrar(llamador)).thenReturn(true);
        var vivo = def("clb:Credito", "PT95 - Crédito", "Credito", prop("clb:Numero", true, 20), prop("clb:Nota", false, null));
        when(archivo.descubrirTipos()).thenReturn(List.of(vivo));
        when(catalogo.listar()).thenReturn(List.of());
        UUID id = UUID.randomUUID();
        when(catalogo.guardarTipoDescubierto(any(), eq(List.of()), eq(true))).thenReturn(id);

        var resumen = servicio().ejecutar(false, llamador, LOG);

        ArgumentCaptor<TipoDocumento> tipo = ArgumentCaptor.forClass(TipoDocumento.class);
        verify(catalogo).guardarTipoDescubierto(tipo.capture(), eq(List.of()), eq(true));
        assertEquals("PT95", tipo.getValue().codigo());
        assertEquals("/Raiz/Credito", tipo.getValue().carpeta());
        assertFalse(tipo.getValue().enUso());
        assertFalse(tipo.getValue().revisado());
        assertEquals("clb:Credito", tipo.getValue().tipoCmisId());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PropiedadCm>> props = ArgumentCaptor.forClass(List.class);
        verify(catalogo).guardarPropiedadesDescubiertas(eq(id), props.capture());
        assertEquals(PropiedadCm.Decision.USAR, props.getValue().get(0).decision());
        assertEquals(PropiedadCm.Decision.OMITIR, props.getValue().get(1).decision());
        assertEquals(1, resumen.nuevos());
        verify(cache).invalidar();
    }

    @Test
    void tipoConCambiosVuelveANoRevisadoConservandoLasDecisiones() {
        when(catalogo.puedeAdministrar(llamador)).thenReturn(true);
        var antes = def("clb:Credito", "PT95 - Crédito", "Credito", prop("clb:Numero", true, 20));
        TipoDocumento guardado = guardadoDe(antes, true, "/Raiz/Credito", false, decididas(antes));
        var ahora = def("clb:Credito", "PT95 - Crédito", "Credito", prop("clb:Numero", true, 30));
        when(archivo.descubrirTipos()).thenReturn(List.of(ahora));
        when(catalogo.listar()).thenReturn(List.of(guardado));

        var resumen = servicio().ejecutar(false, llamador, LOG);

        ArgumentCaptor<TipoDocumento> tipo = ArgumentCaptor.forClass(TipoDocumento.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> cambios = ArgumentCaptor.forClass(List.class);
        verify(catalogo).guardarTipoDescubierto(tipo.capture(), cambios.capture(), eq(false));
        assertFalse(tipo.getValue().revisado());
        assertTrue(tipo.getValue().enUso());
        assertEquals(TipoDocumento.Destino.SOLO_BLOB, tipo.getValue().destino());
        assertEquals(30, tipo.getValue().diasRetencion());
        assertEquals(List.of("~ clb:Numero largo: 20 → 30"), cambios.getValue());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PropiedadCm>> props = ArgumentCaptor.forClass(List.class);
        verify(catalogo).guardarPropiedadesDescubiertas(eq(guardado.id()), props.capture());
        assertEquals(PropiedadCm.Decision.OMITIR, props.getValue().get(0).decision());
        assertEquals(30, props.getValue().get(0).largoMaximo());
        assertEquals(1, resumen.actualizados());
    }

    @Test
    void tipoSinCambiosNoSeEscribe() {
        when(catalogo.puedeAdministrar(llamador)).thenReturn(true);
        var vivo = def("clb:Credito", "PT95 - Crédito", "Credito", prop("clb:Numero", true, 20));
        when(archivo.descubrirTipos()).thenReturn(List.of(vivo));
        when(catalogo.listar()).thenReturn(List.of(guardadoDe(vivo, true, "/Raiz/Credito", false, decididas(vivo))));

        var resumen = servicio().ejecutar(false, llamador, LOG);

        assertEquals(1, resumen.sinCambios());
        verify(catalogo, never()).guardarTipoDescubierto(any(), any(), anyBoolean());
        verify(catalogo, never()).guardarPropiedadesDescubiertas(any(), any());
    }

    @Test
    void tipoQueElServidorDejoDePublicarSeMarcaAusenteSinBorrarse() {
        when(catalogo.puedeAdministrar(llamador)).thenReturn(true);
        var viejo = def("clb:Viejo", "Viejo", "Viejo");
        TipoDocumento guardado = guardadoDe(viejo, true, "/Raiz/Viejo", false, List.of());
        when(archivo.descubrirTipos()).thenReturn(List.of());
        when(catalogo.listar()).thenReturn(List.of(guardado));

        var resumen = servicio().ejecutar(false, llamador, LOG);

        ArgumentCaptor<TipoDocumento> tipo = ArgumentCaptor.forClass(TipoDocumento.class);
        verify(catalogo).guardarTipoDescubierto(tipo.capture(), eq(List.of("el servidor ya no publica este tipo")), eq(false));
        assertTrue(tipo.getValue().ausenteEnServidor());
        assertEquals(guardado.id(), tipo.getValue().id());
        assertTrue(tipo.getValue().enUso());
        assertEquals(1, resumen.ausentes());
    }

    @Test
    void carpetaManualNoSePisa() {
        when(catalogo.puedeAdministrar(llamador)).thenReturn(true);
        var antes = def("clb:Credito", "PT95 - Crédito", "Credito");
        var ahora = def("clb:Credito", "PT95 - Crédito renombrado", "Credito");
        when(archivo.descubrirTipos()).thenReturn(List.of(ahora));
        when(catalogo.listar()).thenReturn(List.of(guardadoDe(antes, true, "/Manual/Creditos", true, List.of())));

        servicio().ejecutar(false, llamador, LOG);

        ArgumentCaptor<TipoDocumento> tipo = ArgumentCaptor.forClass(TipoDocumento.class);
        verify(catalogo).guardarTipoDescubierto(tipo.capture(), any(), eq(false));
        assertEquals("/Manual/Creditos", tipo.getValue().carpeta());
        assertTrue(tipo.getValue().carpetaManual());
        assertEquals("PT95 - Crédito renombrado", tipo.getValue().nombre());
    }

    @Test
    void carpetaDerivadaSeRecalculaSiNoEsManual() {
        when(catalogo.puedeAdministrar(llamador)).thenReturn(true);
        var antes = def("clb:Credito", "PT95 - Crédito", "Credito");
        var ahora = def("clb:Credito", "PT95 - Crédito", "CreditoNuevo");
        when(archivo.descubrirTipos()).thenReturn(List.of(ahora));
        when(catalogo.listar()).thenReturn(List.of(guardadoDe(antes, true, "/Raiz/Credito", false, List.of())));

        servicio().ejecutar(false, llamador, LOG);

        ArgumentCaptor<TipoDocumento> tipo = ArgumentCaptor.forClass(TipoDocumento.class);
        verify(catalogo).guardarTipoDescubierto(tipo.capture(), any(), eq(false));
        assertEquals("/Raiz/CreditoNuevo", tipo.getValue().carpeta());
    }

    @Test
    void soloEnUsoProcesaUnicamenteLosTiposEnUsoYNoMarcaAusentes() {
        when(catalogo.puedeAdministrar(llamador)).thenReturn(true);
        var enUsoAntes = def("clb:A", "A", "A");
        var enUsoAhora = def("clb:A", "A cambiado", "A");
        var sinUso = def("clb:B", "B", "B");
        var nuevo = def("clb:C", "C", "C");
        var ausente = def("clb:D", "D", "D");
        when(archivo.descubrirTipos()).thenReturn(List.of(enUsoAhora, def("clb:B", "B cambiado", "B"), nuevo));
        when(catalogo.listar()).thenReturn(List.of(guardadoDe(enUsoAntes, true, "/Raiz/A", false, List.of()),
                guardadoDe(sinUso, false, "/Raiz/B", false, List.of()), guardadoDe(ausente, true, "/Raiz/D", false, List.of())));

        var resumen = servicio().ejecutar(true, llamador, LOG);

        ArgumentCaptor<TipoDocumento> tipo = ArgumentCaptor.forClass(TipoDocumento.class);
        verify(catalogo).guardarTipoDescubierto(tipo.capture(), any(), anyBoolean());
        assertEquals("clb:A", tipo.getValue().tipoCmisId());
        assertEquals(1, resumen.actualizados());
        assertEquals(0, resumen.nuevos());
        assertEquals(0, resumen.ausentes());
    }

    @Test
    void codigoCompartidoQuedaParaElQueCumpleLaConvencion() {
        when(catalogo.puedeAdministrar(llamador)).thenReturn(true);
        var reglasConPropiedad = new ReglasDescubrimiento(Optional.of("IdCorto"), "/Raiz/{localName}");
        var sinConvencion = new DefinicionTipoCmis("clb:A", "A", "Escritura", true, "cmis:document",
                List.of(new DefinicionTipoCmis.Propiedad("clb:IdCorto", "IdCorto", "string", false, "PT95", null, null, false)));
        var conConvencion = def("clb:B", "PT95 - Escritura", "B");
        when(archivo.descubrirTipos()).thenReturn(List.of(sinConvencion, conConvencion));
        when(catalogo.listar()).thenReturn(List.of());
        when(catalogo.guardarTipoDescubierto(any(), any(), eq(true))).thenReturn(UUID.randomUUID());

        var resumen = new DescubrirTipos(archivo, catalogo, cache, reglasConPropiedad).ejecutar(false, llamador, LOG);

        ArgumentCaptor<TipoDocumento> tipos = ArgumentCaptor.forClass(TipoDocumento.class);
        verify(catalogo, org.mockito.Mockito.times(2)).guardarTipoDescubierto(tipos.capture(), any(), eq(true));
        assertEquals(null, tipos.getAllValues().get(0).codigo());
        assertEquals("PT95", tipos.getAllValues().get(1).codigo());
        assertEquals(1, resumen.avisos().size());
    }
}
