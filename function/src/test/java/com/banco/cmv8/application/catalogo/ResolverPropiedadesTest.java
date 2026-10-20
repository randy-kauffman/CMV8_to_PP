package com.banco.cmv8.application.catalogo;

import com.banco.cmv8.domain.ClaveAdjunto;
import com.banco.cmv8.domain.catalogo.Conflicto;
import com.banco.cmv8.domain.catalogo.DefinicionTipoCmis;
import com.banco.cmv8.domain.catalogo.PropiedadCm;
import com.banco.cmv8.domain.catalogo.TipoDocumento;
import com.banco.cmv8.domain.port.ResolutorAs400;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigInteger;
import java.time.Instant;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.banco.cmv8.Datos.MIME;
import static com.banco.cmv8.Datos.NOMBRE;
import static com.banco.cmv8.Datos.definicion;
import static com.banco.cmv8.Datos.propiedad;
import static com.banco.cmv8.Datos.tipoCm;
import static com.banco.cmv8.Datos.viva;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResolverPropiedadesTest {

    private static final String P = "clb:NumeroCredito";

    @Mock
    private ResolutorAs400 as400;

    private final ClaveAdjunto clave = com.banco.cmv8.Datos.clave();
    private final UUID tipoId = UUID.randomUUID();

    private ResolverPropiedades.Resultado resolver(TipoDocumento tipo, Map<String, Object> valores, Set<String> columnas,
                                                   Optional<DefinicionTipoCmis> viva) {
        return new ResolverPropiedades(as400).resolver(tipo, clave, valores, NOMBRE, MIME, 1024L, columnas, viva);
    }

    private ResolverPropiedades.Resultado resolver(PropiedadCm p, Map<String, Object> valores,
                                                   DefinicionTipoCmis.Propiedad enCm) {
        return resolver(tipoCm(tipoId, p), valores, Set.of(), Optional.of(definicion(enCm)));
    }

    private static PropiedadCm columna(boolean obligatoria, String nombreLogico) {
        return propiedad(P, obligatoria, PropiedadCm.OrigenValor.COLUMNA, nombreLogico, null, null, null);
    }

    private static PropiedadCm consulta(String propiedadId, String consulta) {
        return propiedad(propiedadId, true, PropiedadCm.OrigenValor.CONSULTA, null, consulta, null, null);
    }

    private static PropiedadCm conPatron(String patron) {
        return propiedad(P, true, PropiedadCm.OrigenValor.COLUMNA, "numerocredito", null, null, patron);
    }

    private static DefinicionTipoCmis.Propiedad enCm(String tipoDato) {
        return viva(P, tipoDato, true, null);
    }

    private static Conflicto unico(ResolverPropiedades.Resultado r) {
        assertEquals(1, r.conflictos().size(), r.conflictos().toString());
        return r.conflictos().get(0);
    }

    // ------------------------------------------------------------------ COLUMNA

    @Test
    void origenColumnaTomaElValorDeLaColumnaConPrefijo() {
        var r = resolver(tipoCm(tipoId, columna(true, "NumeroCredito")), Map.of("cred_numerocredito", " CR-1 "),
                Set.of("cred_numerocredito"), Optional.of(definicion(enCm("string"))));
        assertEquals(Map.of(P, "CR-1"), r.propiedades());
        assertFalse(r.bloqueada());
        assertFalse(r.observada());
    }

    @Test
    void columnaVaciaDeUnaObligatoriaEsObservacion() {
        var r = resolver(columna(true, "numerocredito"), Map.of("cred_numerocredito", "  "), enCm("string"));
        assertEquals(List.of("Falta el valor de NumeroCredito"), r.observaciones());
        assertTrue(r.propiedades().isEmpty());
        assertFalse(r.bloqueada());
    }

    @Test
    void columnaVaciaDeUnaOpcionalNoSeEnviaNiObserva() {
        var r = resolver(columna(false, "numerocredito"), Map.of(), viva(P, "string", false, null));
        assertFalse(r.observada());
        assertTrue(r.propiedades().isEmpty());
        assertTrue(r.conflictos().isEmpty());
    }

    @Test
    void columnaInexistenteEnLaTablaEsConflictoDeCatalogo() {
        var r = resolver(tipoCm(tipoId, columna(true, "numerocredito")), Map.of(), Set.of("cred_otra"),
                Optional.of(definicion(enCm("string"))));
        Conflicto c = unico(r);
        assertEquals(Conflicto.Clase.COLUMNA_INEXISTENTE, c.clase());
        assertTrue(c.bloquea());
        assertEquals(P, c.propiedadId());
        assertFalse(r.observada());
    }

    @Test
    void columnaSinNombreLogicoEsConflicto() {
        var r = resolver(columna(true, " "), Map.of(), enCm("string"));
        assertEquals(Conflicto.Clase.OBLIGATORIA_SIN_ORIGEN, unico(r).clase());
    }

    // ------------------------------------------------------------------ CONSULTA

    @Test
    void consultaDeclaradaUsaLaColumnaDelResultadoYParametrosSinPrefijo() {
        when(as400.consultasDeclaradas()).thenReturn(Set.of("cliente"));
        when(as400.consultar(eq("cliente"), anyMap())).thenReturn(Optional.of(Map.of("NOMBRE", "Juan", "SEGMENTO", "PYME")));
        TipoDocumento tipo = tipoCm(tipoId, consulta("clb:Nombre", "Cliente.nombre"), consulta("clb:Segmento", "cliente.SEGMENTO"));
        Map<String, Object> valores = Map.of("cred_idcliente", "C-9", "_cred_tipodocumento_value", "x", "otro_campo", "y");

        var r = resolver(tipo, valores, Set.of(), Optional.of(definicion(viva("clb:Nombre", "string", true, null),
                viva("clb:Segmento", "string", false, null))));

        assertEquals(Map.of("clb:Nombre", "Juan", "clb:Segmento", "PYME"), r.propiedades());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> parametros = ArgumentCaptor.forClass(Map.class);
        verify(as400, times(1)).consultar(eq("cliente"), parametros.capture());
        assertEquals(Map.of("idcliente", "C-9"), parametros.getValue());
    }

    @Test
    void consultaNoDeclaradaEsConflictoYNoLlamaAlAs400() {
        when(as400.consultasDeclaradas()).thenReturn(Set.of());
        var r = resolver(consulta(P, "cliente.NUMERO"), Map.of(), enCm("string"));
        Conflicto c = unico(r);
        assertEquals(Conflicto.Clase.OBLIGATORIA_SIN_ORIGEN, c.clase());
        assertTrue(c.bloquea());
        verify(as400, never()).consultar(any(), any());
    }

    @Test
    void consultaSinColumnaEsConflicto() {
        var r = resolver(consulta(P, "cliente"), Map.of(), enCm("string"));
        assertEquals(Conflicto.Clase.OBLIGATORIA_SIN_ORIGEN, unico(r).clase());
    }

    @Test
    void as400SinFilaEsObservacion() {
        when(as400.consultasDeclaradas()).thenReturn(Set.of("cliente"));
        when(as400.consultar(eq("cliente"), anyMap())).thenReturn(Optional.empty());
        var r = resolver(consulta(P, "cliente.NUMERO"), Map.of("cred_idcliente", "C-0"), enCm("string"));
        assertTrue(r.observada());
        assertTrue(r.observaciones().get(0).contains("AS400 no encontró"));
        assertFalse(r.bloqueada());
    }

    @Test
    void parametroFaltanteDeLaConsultaEsObservacion() {
        when(as400.consultasDeclaradas()).thenReturn(Set.of("cliente"));
        when(as400.consultar(eq("cliente"), anyMap())).thenThrow(new IllegalArgumentException("falta idcliente"));
        var r = resolver(consulta(P, "cliente.NUMERO"), Map.of(), enCm("string"));
        assertTrue(r.observaciones().get(0).contains("falta idcliente"));
        assertFalse(r.bloqueada());
    }

    // ------------------------------------------------------------------ SISTEMA y LITERAL

    @Test
    void origenSistemaIdYNombreArchivo() {
        TipoDocumento tipo = tipoCm(tipoId,
                propiedad("clb:Id", true, PropiedadCm.OrigenValor.SISTEMA, "id", null, null, null),
                propiedad("clb:Archivo", true, PropiedadCm.OrigenValor.SISTEMA, "NombreArchivo", null, null, null));
        var r = resolver(tipo, Map.of(), Set.of(), Optional.of(definicion(viva("clb:Id", "string", true, null),
                viva("clb:Archivo", "string", true, null))));
        assertEquals(Map.of("clb:Id", clave.id().toString(), "clb:Archivo", NOMBRE), r.propiedades());
    }

    @Test
    void origenSistemaDesconocidoEsConflicto() {
        var r = resolver(propiedad(P, true, PropiedadCm.OrigenValor.SISTEMA, "usuario", null, null, null), Map.of(),
                enCm("string"));
        assertEquals(Conflicto.Clase.OBLIGATORIA_SIN_ORIGEN, unico(r).clase());
    }

    @Test
    void origenLiteral() {
        var r = resolver(propiedad(P, true, PropiedadCm.OrigenValor.LITERAL, null, null, "CREDITOS", null), Map.of(),
                enCm("string"));
        assertEquals(Map.of(P, "CREDITOS"), r.propiedades());
    }

    @Test
    void propiedadEnUsoSinOrigenEsConflicto() {
        var r = resolver(propiedad(P, true, null, null, null, null, null), Map.of(), enCm("string"));
        assertEquals(Conflicto.Clase.OBLIGATORIA_SIN_ORIGEN, unico(r).clase());
    }

    // ------------------------------------------------------------------ validaciones de datos

    @Test
    void valorQueNoCumpleElPatronEsObservacion() {
        var r = resolver(conPatron("^CR-\\d+$"), Map.of("cred_numerocredito", "XX-1"), enCm("string"));
        assertTrue(r.observaciones().get(0).contains("formato"));
        assertTrue(r.propiedades().isEmpty());
    }

    @Test
    void patronMalEscritoNoCastigaAlUsuario() {
        var r = resolver(conPatron("[abc"), Map.of("cred_numerocredito", "XX-1"), enCm("string"));
        assertFalse(r.observada());
        assertEquals("XX-1", r.propiedades().get(P));
    }

    @Test
    void valorMasLargoQueElMaximoVivoEsObservacion() {
        var r = resolver(columna(true, "numerocredito"), Map.of("cred_numerocredito", "CR-123456"), viva(P, "string", true, 5));
        assertTrue(r.observaciones().get(0).contains("largo máximo de 5"));
        assertTrue(r.propiedades().isEmpty());
    }

    @Test
    void convierteAlTipoDeDatoQueDeclaraContentManager() {
        TipoDocumento tipo = tipoCm(tipoId,
                propiedad("clb:Monto", true, PropiedadCm.OrigenValor.COLUMNA, "monto", null, null, null),
                propiedad("clb:Fecha", true, PropiedadCm.OrigenValor.COLUMNA, "fecha", null, null, null),
                propiedad("clb:Activo", true, PropiedadCm.OrigenValor.COLUMNA, "activo", null, null, null));
        Map<String, Object> valores = Map.of("cred_monto", "42", "cred_fecha", "2026-10-19", "cred_activo", "sí");

        var r = resolver(tipo, valores, Set.of(), Optional.of(definicion(viva("clb:Monto", "integer", true, null),
                viva("clb:Fecha", "datetime", true, null), viva("clb:Activo", "boolean", true, null))));

        assertFalse(r.observada(), r.observaciones().toString());
        assertEquals(BigInteger.valueOf(42), r.propiedades().get("clb:Monto"));
        assertEquals(Instant.parse("2026-10-19T00:00:00Z"),
                ((GregorianCalendar) r.propiedades().get("clb:Fecha")).toZonedDateTime().toInstant());
        assertEquals(Boolean.TRUE, r.propiedades().get("clb:Activo"));
    }

    @Test
    void valorNoConvertibleEsObservacion() {
        var r = resolver(columna(true, "numerocredito"), Map.of("cred_numerocredito", "abc"), enCm("integer"));
        assertTrue(r.observaciones().get(0).contains("integer"));
    }

    // ------------------------------------------------------------------ cruce con la definición viva

    @Test
    void propiedadEnUsoQueContentManagerYaNoPublicaBloquea() {
        var r = resolver(tipoCm(tipoId, propiedad("clb:Vieja", true, PropiedadCm.OrigenValor.LITERAL, null, null, "x", null)),
                Map.of(), Set.of(), Optional.of(definicion()));
        Conflicto c = unico(r);
        assertEquals(Conflicto.Clase.CATALOGO_DESACTUALIZADO, c.clase());
        assertEquals("clb:Vieja", c.propiedadId());
        assertTrue(r.bloqueada());
    }

    @Test
    void contentManagerExigeUnaPropiedadQueElCatalogoNoEnviaBloquea() {
        var r = resolver(tipoCm(tipoId), Map.of(), Set.of(), Optional.of(definicion(viva("clb:Nueva", "string", true, null))));
        Conflicto c = unico(r);
        assertEquals(Conflicto.Clase.CATALOGO_DESACTUALIZADO, c.clase());
        assertEquals("clb:Nueva", c.propiedadId());
        assertTrue(r.bloqueada());
    }

    @Test
    void catalogoMasEstrictoQueContentManagerEsAvisoYNoBloquea() {
        var r = resolver(columna(true, "numerocredito"), Map.of("cred_numerocredito", "CR-1"), viva(P, "string", false, null));
        assertFalse(r.bloqueada());
        assertEquals(1, r.avisos().size());
        assertEquals(Conflicto.Severidad.AVISO, r.avisos().get(0).severidad());
        assertEquals("CR-1", r.propiedades().get(P));
    }

    @Test
    void definicionVivaVaciaEsTipoAusente() {
        var r = resolver(tipoCm(tipoId, columna(true, "numerocredito")), Map.of("cred_numerocredito", "CR-1"), Set.of(),
                Optional.empty());
        Conflicto c = unico(r);
        assertEquals(Conflicto.Clase.TIPO_AUSENTE, c.clase());
        assertTrue(c.bloquea());
        assertTrue(r.propiedades().isEmpty());
    }

    // ------------------------------------------------------------------ antes de subir

    @Test
    void validarAntesDeSubirSoloEjecutaConsultasMarcadasYDeclaradas() {
        PropiedadCm previa = new PropiedadCm(UUID.randomUUID(), "clb:Cliente", "Cliente", "string", true, null, null, null,
                false, PropiedadCm.Decision.USAR, PropiedadCm.OrigenValor.CONSULTA, null, "cliente.NOMBRE", null, null,
                PropiedadCm.MomentoValidacion.ANTES_DE_SUBIR);
        PropiedadCm noDeclarada = new PropiedadCm(UUID.randomUUID(), "clb:Otra", "Otra", "string", true, null, null, null,
                false, PropiedadCm.Decision.USAR, PropiedadCm.OrigenValor.CONSULTA, null, "fantasma.X", null, null,
                PropiedadCm.MomentoValidacion.ANTES_DE_SUBIR);
        PropiedadCm alArchivar = consulta("clb:Segmento", "segmento.VALOR");
        when(as400.consultasDeclaradas()).thenReturn(Set.of("cliente", "segmento"));
        when(as400.consultar(eq("cliente"), anyMap())).thenReturn(Optional.empty());

        List<String> obs = new ResolverPropiedades(as400).validarAntesDeSubir(tipoCm(tipoId, previa, noDeclarada, alArchivar),
                clave, Map.of("cred_idcliente", "C-1"));

        assertEquals(1, obs.size());
        assertTrue(obs.get(0).contains("cliente"));
        verify(as400, times(1)).consultar(any(), any());
    }
}
