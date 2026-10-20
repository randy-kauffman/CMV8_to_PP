package com.banco.cmv8.application;

import com.banco.cmv8.application.catalogo.CatalogoEnCache;
import com.banco.cmv8.application.catalogo.ResolverPropiedades;
import com.banco.cmv8.domain.ClaveAdjunto;
import com.banco.cmv8.domain.ContextoLlamador;
import com.banco.cmv8.domain.EstadoIngesta;
import com.banco.cmv8.domain.PoliticaSas;
import com.banco.cmv8.domain.RegistroIngesta;
import com.banco.cmv8.domain.SasEmitida;
import com.banco.cmv8.domain.TokenUsuario;
import com.banco.cmv8.domain.ValidacionException;
import com.banco.cmv8.domain.catalogo.TipoDocumento;
import com.banco.cmv8.domain.port.CatalogoTipos;
import com.banco.cmv8.domain.port.EmisorSas;
import com.banco.cmv8.domain.port.IntercambiadorTokenUsuario;
import com.banco.cmv8.domain.port.RegistroIngestas;
import com.banco.cmv8.domain.port.RepositorioAdjuntos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.banco.cmv8.Datos.AHORA;
import static com.banco.cmv8.Datos.INGESTA;
import static com.banco.cmv8.Datos.LOG;
import static com.banco.cmv8.Datos.RELOJ;
import static com.banco.cmv8.Datos.TABLA;
import static com.banco.cmv8.Datos.adjunto;
import static com.banco.cmv8.Datos.llamador;
import static com.banco.cmv8.Datos.tipoCm;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmitirSasLoteTest {

    private static final Duration VIGENCIA = Duration.ofMinutes(15);
    private static final OffsetDateTime EXPIRA = OffsetDateTime.of(2026, 10, 19, 12, 15, 0, 0, ZoneOffset.UTC);

    @Mock
    private RepositorioAdjuntos repositorio;
    @Mock
    private RegistroIngestas registros;
    @Mock
    private CatalogoTipos catalogoTipos;
    @Mock
    private ResolverPropiedades resolver;
    @Mock
    private EmisorSas emisor;
    @Mock
    private IntercambiadorTokenUsuario intercambiador;

    private final ContextoLlamador llamador = llamador();
    private final UUID tipoId = UUID.randomUUID();

    private EmitirSasLote servicio(VinculoUsuario vinculo, int loteMaximo) {
        return new EmitirSasLote(new PoliticaSas(10_000_000L, RELOJ), repositorio, registros,
                new CatalogoEnCache(catalogoTipos, Duration.ofMinutes(5), RELOJ), resolver, emisor, vinculo, INGESTA,
                VIGENCIA, EmitirSasLote.BLOQUE_BYTES, loteMaximo, RELOJ);
    }

    private EmitirSasLote servicio() {
        return servicio(VinculoUsuario.desactivado(), 10);
    }

    private static EmitirSasLote.Item item(UUID id) {
        return new EmitirSasLote.Item(id, "Contrato final.pdf", 2048L, "application/pdf");
    }

    /** El adjunto existe, el usuario puede escribirlo y está en Creada con un tipo en uso. */
    private void adjuntoAdmitido(UUID id) {
        ClaveAdjunto clave = new ClaveAdjunto(TABLA, id);
        String ruta = clave.ruta("Contrato_final.pdf");
        when(repositorio.registrarRutaSuplantando(clave, ruta, llamador))
                .thenReturn(adjunto(clave, EstadoIngesta.CREADA, tipoId, ruta, Map.of("cred_numerocredito", "CR-1")));
        lenient().when(catalogoTipos.listar()).thenReturn(List.of(tipoCm(tipoId)));
        lenient().when(resolver.validarAntesDeSubir(any(), any(), any())).thenReturn(List.of());
        when(emisor.emitirSubida(INGESTA, ruta, VIGENCIA, llamador))
                .thenReturn(new SasEmitida("https://sas/" + id, EXPIRA, "2025-07-05"));
    }

    @Test
    void loteVacioEsValidacion() {
        assertThrows(ValidacionException.class,
                () -> servicio().ejecutar(new EmitirSasLote.Peticion(TABLA, List.of()), llamador, LOG));
        assertThrows(ValidacionException.class, () -> servicio().ejecutar(null, llamador, LOG));
        verifyNoInteractions(repositorio, emisor, registros);
    }

    @Test
    void loteMayorAlMaximoEsValidacion() {
        var peticion = new EmitirSasLote.Peticion(TABLA, List.of(item(UUID.randomUUID()), item(UUID.randomUUID()),
                item(UUID.randomUUID())));
        assertThrows(ValidacionException.class, () -> servicio(VinculoUsuario.desactivado(), 2).ejecutar(peticion, llamador, LOG));
        verifyNoInteractions(repositorio, emisor, registros);
    }

    @Test
    void tablaInvalidaEsValidacionAntesDeTocarDataverse() {
        var peticion = new EmitirSasLote.Peticion("Cred-Adjunto", List.of(item(UUID.randomUUID())));
        assertThrows(ValidacionException.class, () -> servicio().ejecutar(peticion, llamador, LOG));
        verifyNoInteractions(repositorio);
    }

    @Test
    void pideElTokenOboUnaSolaVezParaTodoElLote() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        adjuntoAdmitido(a);
        adjuntoAdmitido(b);
        when(intercambiador.tokenStorage(llamador)).thenReturn(new TokenUsuario("token-storage", EXPIRA));

        var resultado = servicio(VinculoUsuario.activo(intercambiador), 10)
                .ejecutar(new EmitirSasLote.Peticion(TABLA, List.of(item(a), item(b))), llamador, LOG);

        verify(intercambiador, times(1)).tokenStorage(llamador);
        assertEquals("token-storage", resultado.tokenStorage());
        assertEquals(EXPIRA, resultado.tokenStorageExpiraUtc());
        assertTrue(resultado.items().stream().allMatch(EmitirSasLote.ItemResultado::exitoso));
    }

    @Test
    void sinVinculoActivoNoDevuelveTokenStorage() {
        UUID a = UUID.randomUUID();
        adjuntoAdmitido(a);
        var resultado = servicio().ejecutar(new EmitirSasLote.Peticion(TABLA, List.of(item(a))), llamador, LOG);
        assertNull(resultado.tokenStorage());
        assertNull(resultado.tokenStorageExpiraUtc());
        verifyNoInteractions(intercambiador);
    }

    @Test
    void vinculoActivoSinOidEsNoAutenticadoAntesDeTocarDataverse() {
        var peticion = new EmitirSasLote.Peticion(TABLA, List.of(item(UUID.randomUUID())));
        assertThrows(NoAutenticadoException.class, () -> servicio(VinculoUsuario.activo(intercambiador), 10)
                .ejecutar(peticion, com.banco.cmv8.Datos.sinOid(), LOG));
        verifyNoInteractions(repositorio, intercambiador);
    }

    @Test
    void losErroresPorItemQuedanEnElResultadoYNoFrenanElResto() {
        UUID denegado = UUID.randomUUID();
        UUID inexistente = UUID.randomUUID();
        UUID archivado = UUID.randomUUID();
        UUID bueno = UUID.randomUUID();
        when(repositorio.registrarRutaSuplantando(eq(new ClaveAdjunto(TABLA, denegado)), anyString(), eq(llamador)))
                .thenThrow(new AccesoDenegadoException("403"));
        when(repositorio.registrarRutaSuplantando(eq(new ClaveAdjunto(TABLA, inexistente)), anyString(), eq(llamador)))
                .thenThrow(new SolicitudNoEncontradaException("404"));
        ClaveAdjunto claveArchivado = new ClaveAdjunto(TABLA, archivado);
        when(repositorio.registrarRutaSuplantando(eq(claveArchivado), anyString(), eq(llamador)))
                .thenReturn(adjunto(claveArchivado, EstadoIngesta.ARCHIVADO, tipoId, null, Map.of()));
        adjuntoAdmitido(bueno);

        var resultado = servicio().ejecutar(new EmitirSasLote.Peticion(TABLA,
                List.of(item(denegado), item(inexistente), item(archivado), item(bueno))), llamador, LOG);

        List<EmitirSasLote.ItemResultado> items = resultado.items();
        assertEquals(4, items.size());
        assertEquals(403, items.get(0).codigoError());
        assertEquals(404, items.get(1).codigoError());
        assertEquals(409, items.get(2).codigoError());
        assertTrue(items.get(3).exitoso());
        assertEquals(bueno, items.get(3).id());
        verify(emisor, times(1)).emitirSubida(any(), any(), any(), any());
        verify(registros, times(1)).guardar(any());
    }

    @Test
    void itemRepetidoEnElLoteEs400YSoloSeEmiteUnaVez() {
        UUID a = UUID.randomUUID();
        adjuntoAdmitido(a);
        var resultado = servicio().ejecutar(new EmitirSasLote.Peticion(TABLA, List.of(item(a), item(a))), llamador, LOG);
        assertTrue(resultado.items().get(0).exitoso());
        assertEquals(400, resultado.items().get(1).codigoError());
        verify(repositorio, times(1)).registrarRutaSuplantando(any(), any(), any());
    }

    @Test
    void itemSinIdEs400() {
        var resultado = servicio().ejecutar(new EmitirSasLote.Peticion(TABLA,
                List.of(new EmitirSasLote.Item(null, "a.pdf", 1L, "x"))), llamador, LOG);
        assertEquals(400, resultado.items().get(0).codigoError());
        verifyNoInteractions(repositorio);
    }

    @Test
    void observacionAntesDeSubirDejaElItemConCodigo400SinEmitirSas() {
        UUID a = UUID.randomUUID();
        ClaveAdjunto clave = new ClaveAdjunto(TABLA, a);
        String ruta = clave.ruta("Contrato_final.pdf");
        when(repositorio.registrarRutaSuplantando(clave, ruta, llamador))
                .thenReturn(adjunto(clave, EstadoIngesta.CREADA, tipoId, ruta, Map.of()));
        when(catalogoTipos.listar()).thenReturn(List.of(tipoCm(tipoId)));
        when(resolver.validarAntesDeSubir(any(), eq(clave), any()))
                .thenReturn(List.of("El AS400 no encontró datos con la consulta cliente"));

        var resultado = servicio().ejecutar(new EmitirSasLote.Peticion(TABLA, List.of(item(a))), llamador, LOG);

        assertEquals(400, resultado.items().get(0).codigoError());
        assertTrue(resultado.items().get(0).error().contains("AS400"));
        verifyNoInteractions(emisor);
        verify(registros, never()).guardar(any());
    }

    @Test
    void tipoQueNoEstaEnUsoEs400() {
        UUID a = UUID.randomUUID();
        ClaveAdjunto clave = new ClaveAdjunto(TABLA, a);
        String ruta = clave.ruta("Contrato_final.pdf");
        when(repositorio.registrarRutaSuplantando(clave, ruta, llamador))
                .thenReturn(adjunto(clave, EstadoIngesta.CREADA, tipoId, ruta, Map.of()));
        TipoDocumento enDesuso = new TipoDocumento(tipoId, "PT95", "PT95 - Crédito", TipoDocumento.OrigenTipo.DESCUBIERTO,
                "clb:Credito", "Credito", true, false, "/Creditos", false, null, null, null, false, true, false, List.of());
        when(catalogoTipos.listar()).thenReturn(List.of(enDesuso));

        var resultado = servicio().ejecutar(new EmitirSasLote.Peticion(TABLA, List.of(item(a))), llamador, LOG);

        assertEquals(400, resultado.items().get(0).codigoError());
        verifyNoInteractions(emisor);
    }

    @Test
    void caminoFelizEscribeLaRutaSuplantandoEmiteEnIngestaYGuardaElRegistro() {
        UUID a = UUID.randomUUID();
        ClaveAdjunto clave = new ClaveAdjunto(TABLA, a);
        String ruta = TABLA + "/" + a + "/Contrato_final.pdf";
        adjuntoAdmitido(a);

        var resultado = servicio().ejecutar(new EmitirSasLote.Peticion(TABLA, List.of(item(a))), llamador, LOG);

        verify(repositorio).registrarRutaSuplantando(clave, ruta, llamador);
        verify(emisor).emitirSubida(INGESTA, ruta, VIGENCIA, llamador);
        ArgumentCaptor<RegistroIngesta> guardado = ArgumentCaptor.forClass(RegistroIngesta.class);
        verify(registros).guardar(guardado.capture());
        RegistroIngesta r = guardado.getValue();
        assertEquals(clave, r.clave());
        assertEquals(EstadoIngesta.CREADA, r.estado());
        assertEquals("oid-1", r.solicitanteOid());
        assertEquals(INGESTA, r.contenedor());
        assertEquals(ruta, r.ruta());
        assertEquals(tipoId, r.tipoDocumentoId());
        assertEquals(AHORA, r.sasEmitidaEn());
        assertEquals("CR-1", r.valores().get("cred_numerocredito"));

        EmitirSasLote.ItemResultado item = resultado.items().get(0);
        assertTrue(item.exitoso());
        assertEquals("https://sas/" + a, item.uploadUrl());
        assertEquals(ruta, item.rutaBlob());
        assertEquals(EXPIRA, item.expiraUtc());
        assertEquals(EmitirSasLote.BLOQUE_BYTES, resultado.bloqueBytes());
        assertEquals("2025-07-05", resultado.versionServicio());
        verify(repositorio, never()).actualizar(any(), any());
    }
}
