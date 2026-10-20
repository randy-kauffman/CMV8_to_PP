package com.banco.cmv8.application;

import com.banco.cmv8.application.catalogo.CatalogoEnCache;
import com.banco.cmv8.application.catalogo.Conflictos;
import com.banco.cmv8.application.catalogo.ResolverPropiedades;
import com.banco.cmv8.domain.Adjunto;
import com.banco.cmv8.domain.ClaveAdjunto;
import com.banco.cmv8.domain.DocumentoAArchivar;
import com.banco.cmv8.domain.EstadoIngesta;
import com.banco.cmv8.domain.MensajeIngesta;
import com.banco.cmv8.domain.RegistroIngesta;
import com.banco.cmv8.domain.catalogo.Conflicto;
import com.banco.cmv8.domain.catalogo.TipoDocumento;
import com.banco.cmv8.domain.port.AgendaDepuracion;
import com.banco.cmv8.domain.port.AlmacenAdjuntos;
import com.banco.cmv8.domain.port.ArchivoDocumental;
import com.banco.cmv8.domain.port.CatalogoTipos;
import com.banco.cmv8.domain.port.RegistroIngestas;
import com.banco.cmv8.domain.port.RepositorioAdjuntos;
import com.banco.cmv8.domain.port.ResolutorAs400;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.banco.cmv8.Datos.ALMACENADOS;
import static com.banco.cmv8.Datos.CARPETA;
import static com.banco.cmv8.Datos.HOY;
import static com.banco.cmv8.Datos.INGESTA;
import static com.banco.cmv8.Datos.LOG;
import static com.banco.cmv8.Datos.MIME;
import static com.banco.cmv8.Datos.NOMBRE;
import static com.banco.cmv8.Datos.RELOJ;
import static com.banco.cmv8.Datos.TABLA;
import static com.banco.cmv8.Datos.TIPO_CMIS;
import static com.banco.cmv8.Datos.columna;
import static com.banco.cmv8.Datos.definicion;
import static com.banco.cmv8.Datos.registro;
import static com.banco.cmv8.Datos.ruta;
import static com.banco.cmv8.Datos.tipo;
import static com.banco.cmv8.Datos.tipoCm;
import static com.banco.cmv8.Datos.tipoLocal;
import static com.banco.cmv8.Datos.viva;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcesarIngestaTest {

    private static final int OBSERVADA_DIAS = 15;
    private static final String PROPIEDAD = "clb:NumeroCredito";

    @Mock
    private RegistroIngestas registros;
    @Mock
    private RepositorioAdjuntos repositorio;
    @Mock
    private CatalogoTipos catalogoTipos;
    @Mock
    private AlmacenAdjuntos almacen;
    @Mock
    private ArchivoDocumental archivo;
    @Mock
    private ResolutorAs400 as400;
    @Mock
    private Conflictos conflictos;
    @Mock
    private AgendaDepuracion agenda;

    private final ClaveAdjunto clave = com.banco.cmv8.Datos.clave();
    private final String ruta = ruta(clave);
    private final UUID tipoId = UUID.randomUUID();
    private final Map<String, Object> valores = Map.of("cred_numerocredito", "CR-1");

    private ProcesarIngesta servicio(boolean escaneo, boolean worm, boolean bloquear) {
        return new ProcesarIngesta(registros, repositorio, new CatalogoEnCache(catalogoTipos, Duration.ofMinutes(5), RELOJ),
                almacen, archivo, new ResolverPropiedades(as400), conflictos, agenda, INGESTA, ALMACENADOS, escaneo,
                worm, bloquear, OBSERVADA_DIAS, RELOJ);
    }

    private ProcesarIngesta servicio() {
        return servicio(false, false, false);
    }

    private MensajeIngesta mensaje() {
        return new MensajeIngesta(clave, INGESTA, ruta, "blob-creado");
    }

    private void registroEn(EstadoIngesta estado, String contenedor, Map<String, Object> vals) {
        when(registros.obtener(clave)).thenReturn(Optional.of(registro(clave, estado, tipoId, contenedor, vals)));
    }

    /** Registro Subido en ingesta, blob presente y el tipo dado en el catálogo. */
    private void listoParaProcesar(TipoDocumento tipo) {
        registroEn(EstadoIngesta.SUBIDO, INGESTA, valores);
        when(almacen.existe(INGESTA, ruta)).thenReturn(true);
        when(catalogoTipos.listar()).thenReturn(List.of(tipo));
    }

    /** Content Manager publica el tipo con la propiedad que usa el catálogo y la carpeta existe. */
    private void contentManagerAlDia() {
        when(archivo.definicionTipo(TIPO_CMIS)).thenReturn(Optional.of(definicion(viva(PROPIEDAD, "string", true, 50))));
        when(archivo.existeCarpeta(CARPETA)).thenReturn(true);
    }

    private void cmCreaDocumento(String contenedor, String objectId) {
        when(almacen.tamano(contenedor, ruta)).thenReturn(1024L);
        when(almacen.abrir(contenedor, ruta)).thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        when(archivo.archivar(any(), any(), eq(1024L))).thenReturn(objectId);
    }

    private TipoDocumento tipoConPropiedad(TipoDocumento.Momento momento, Integer dias, boolean inmutable) {
        return tipo(tipoId, momento, dias, inmutable, columna(PROPIEDAD, "numerocredito"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> unicoPatch() {
        ArgumentCaptor<Map<String, Object>> cambios = ArgumentCaptor.forClass(Map.class);
        verify(repositorio, times(1)).actualizar(eq(clave), cambios.capture());
        return cambios.getValue();
    }

    private RegistroIngesta ultimoRegistroGuardado() {
        ArgumentCaptor<RegistroIngesta> captor = ArgumentCaptor.forClass(RegistroIngesta.class);
        verify(registros, org.mockito.Mockito.atLeastOnce()).guardar(captor.capture());
        List<RegistroIngesta> todos = captor.getAllValues();
        return todos.get(todos.size() - 1);
    }

    private static int codigo(Map<String, Object> patch) {
        return ((EstadoIngesta) patch.get(Adjunto.COL_ESTADO)).codigo();
    }

    // ------------------------------------------------------------------ descartes

    @Test
    void omiteUnMensajeCuyaRutaNoEsDelAdjunto() {
        String ajena = TABLA + "/" + UUID.randomUUID() + "/" + NOMBRE;
        var desenlace = servicio().ejecutar(new MensajeIngesta(clave, INGESTA, ajena, "blob-creado"), LOG);
        assertEquals(ProcesarIngesta.Desenlace.OMITIDO, desenlace);
        verifyNoInteractions(registros, repositorio, almacen, archivo);
    }

    @Test
    void omiteSinRegistroOperativoSinPreguntarleADataverse() {
        when(registros.obtener(clave)).thenReturn(Optional.empty());
        assertEquals(ProcesarIngesta.Desenlace.OMITIDO, servicio().ejecutar(mensaje(), LOG));
        verifyNoInteractions(repositorio, almacen, archivo);
    }

    @Test
    void registroArchivadoEsYaArchivadoYBorraElBlobDuplicado() {
        registroEn(EstadoIngesta.ARCHIVADO, INGESTA, Map.of());
        assertEquals(ProcesarIngesta.Desenlace.YA_ARCHIVADO, servicio().ejecutar(mensaje(), LOG));
        verify(almacen).borrar(INGESTA, ruta);
        verifyNoInteractions(repositorio, archivo);
        verify(registros, never()).guardar(any());
    }

    @Test
    void registroRechazadoSeOmite() {
        registroEn(EstadoIngesta.RECHAZADO, INGESTA, Map.of());
        assertEquals(ProcesarIngesta.Desenlace.OMITIDO, servicio().ejecutar(mensaje(), LOG));
        verifyNoInteractions(repositorio, archivo, almacen);
    }

    @Test
    void detenidaNoSeProcesaNiSeTocaNada() {
        registroEn(EstadoIngesta.DETENIDA, INGESTA, valores);
        assertEquals(ProcesarIngesta.Desenlace.DETENIDA, servicio().ejecutar(mensaje(), LOG));
        verifyNoInteractions(repositorio, almacen, archivo, conflictos, agenda, catalogoTipos);
        verify(registros, never()).guardar(any());
    }

    @Test
    void blobInexistenteSeOmite() {
        registroEn(EstadoIngesta.SUBIDO, INGESTA, valores);
        when(almacen.existe(INGESTA, ruta)).thenReturn(false);
        assertEquals(ProcesarIngesta.Desenlace.OMITIDO, servicio().ejecutar(mensaje(), LOG));
        verifyNoInteractions(repositorio, archivo);
        verify(registros, never()).guardar(any());
    }

    @Test
    void escaneoPendienteSeOmiteHastaElProximoIntento() {
        registroEn(EstadoIngesta.SUBIDO, INGESTA, valores);
        when(almacen.existe(INGESTA, ruta)).thenReturn(true);
        when(almacen.resultadoEscaneo(INGESTA, ruta)).thenReturn(Optional.empty());
        assertEquals(ProcesarIngesta.Desenlace.OMITIDO, servicio(true, false, false).ejecutar(mensaje(), LOG));
        verifyNoInteractions(repositorio, archivo);
    }

    @Test
    void escaneoMaliciosoRechazaConEstado5YBorra() {
        registroEn(EstadoIngesta.SUBIDO, INGESTA, valores);
        when(almacen.existe(INGESTA, ruta)).thenReturn(true);
        when(almacen.resultadoEscaneo(INGESTA, ruta)).thenReturn(Optional.of("Malicious"));

        assertEquals(ProcesarIngesta.Desenlace.RECHAZADO, servicio(true, false, false).ejecutar(mensaje(), LOG));

        Map<String, Object> patch = unicoPatch();
        assertEquals(5, codigo(patch));
        assertTrue(patch.containsKey(Adjunto.COL_RUTA));
        assertNull(patch.get(Adjunto.COL_RUTA));
        verify(almacen).borrar(INGESTA, ruta);
        assertEquals(EstadoIngesta.RECHAZADO, ultimoRegistroGuardado().estado());
        verifyNoInteractions(archivo);
    }

    // ------------------------------------------------------------------ catálogo y datos

    @Test
    void detieneCuandoContentManagerYaNoPublicaElTipo() {
        listoParaProcesar(tipoConPropiedad(TipoDocumento.Momento.AL_SUBIR, null, false));
        when(archivo.definicionTipo(TIPO_CMIS)).thenReturn(Optional.empty());
        when(archivo.existeCarpeta(CARPETA)).thenReturn(true);
        UUID conflictoId = UUID.randomUUID();
        when(conflictos.registrarConDetenido(any(), any())).thenReturn(conflictoId);

        assertEquals(ProcesarIngesta.Desenlace.DETENIDA, servicio().ejecutar(mensaje(), LOG));

        ArgumentCaptor<Conflicto> conflicto = ArgumentCaptor.forClass(Conflicto.class);
        verify(conflictos).registrarConDetenido(conflicto.capture(), any());
        assertEquals(Conflicto.Clase.TIPO_AUSENTE, conflicto.getValue().clase());
        assertTrue(conflicto.getValue().bloquea());
        assertEquals(tipoId, conflicto.getValue().tipoDocumentoId());
        Map<String, Object> patch = unicoPatch();
        assertEquals(9, codigo(patch));
        RegistroIngesta guardado = ultimoRegistroGuardado();
        assertEquals(EstadoIngesta.DETENIDA, guardado.estado());
        assertEquals(conflicto.getValue().clave(), guardado.conflictoClave());
        verify(archivo, never()).archivar(any(), any(), anyLong());
        verify(almacen, never()).mover(any(), any(), any(), any());
        verify(repositorio, never()).obtener(any());
    }

    @Test
    void carpetaInexistenteEnContentManagerDetiene() {
        listoParaProcesar(tipoConPropiedad(TipoDocumento.Momento.AL_SUBIR, null, false));
        when(archivo.definicionTipo(TIPO_CMIS)).thenReturn(Optional.of(definicion(viva(PROPIEDAD, "string", true, 50))));
        when(archivo.existeCarpeta(CARPETA)).thenReturn(false);
        when(conflictos.registrarConDetenido(any(), any())).thenReturn(UUID.randomUUID());

        assertEquals(ProcesarIngesta.Desenlace.DETENIDA, servicio().ejecutar(mensaje(), LOG));

        ArgumentCaptor<Conflicto> conflicto = ArgumentCaptor.forClass(Conflicto.class);
        verify(conflictos).registrarConDetenido(conflicto.capture(), any());
        assertEquals(Conflicto.Clase.CARPETA_INEXISTENTE, conflicto.getValue().clase());
        verify(archivo, never()).archivar(any(), any(), anyLong());
    }

    @Test
    void faltaUnValorDelArchivoQuedaObservadaConVencimientoAgendado() {
        registroEn(EstadoIngesta.SUBIDO, INGESTA, Map.of());
        when(almacen.existe(INGESTA, ruta)).thenReturn(true);
        when(catalogoTipos.listar()).thenReturn(List.of(tipoConPropiedad(TipoDocumento.Momento.AL_SUBIR, null, false)));
        contentManagerAlDia();

        assertEquals(ProcesarIngesta.Desenlace.OBSERVADA, servicio().ejecutar(mensaje(), LOG));

        Map<String, Object> patch = unicoPatch();
        assertEquals(8, codigo(patch));
        assertTrue(((String) patch.get(Adjunto.COL_MENSAJE)).contains("NumeroCredito"));
        verify(agenda).programar(HOY.plusDays(OBSERVADA_DIAS), clave);
        RegistroIngesta guardado = ultimoRegistroGuardado();
        assertEquals(EstadoIngesta.OBSERVADA, guardado.estado());
        assertEquals(HOY.plusDays(OBSERVADA_DIAS), guardado.fechaDepuracion());
        verify(archivo, never()).archivar(any(), any(), anyLong());
        verifyNoInteractions(conflictos);
    }

    @Test
    void tipoInexistenteEnElCatalogoQuedaObservada() {
        registroEn(EstadoIngesta.SUBIDO, INGESTA, valores);
        when(almacen.existe(INGESTA, ruta)).thenReturn(true);
        when(catalogoTipos.listar()).thenReturn(List.of());

        assertEquals(ProcesarIngesta.Desenlace.OBSERVADA, servicio().ejecutar(mensaje(), LOG));
        assertEquals(8, codigo(unicoPatch()));
        verifyNoInteractions(archivo);
    }

    // ------------------------------------------------------------------ archivar en Content Manager

    @Test
    void caminoFelizArchivaConUnSoloPatchYBorraLaIngesta() {
        listoParaProcesar(tipoConPropiedad(TipoDocumento.Momento.AL_SUBIR, null, false));
        contentManagerAlDia();
        cmCreaDocumento(INGESTA, "obj-1");

        assertEquals(ProcesarIngesta.Desenlace.ARCHIVADO, servicio().ejecutar(mensaje(), LOG));

        ArgumentCaptor<DocumentoAArchivar> doc = ArgumentCaptor.forClass(DocumentoAArchivar.class);
        verify(archivo).archivar(doc.capture(), any(), eq(1024L));
        assertEquals(new DocumentoAArchivar(TIPO_CMIS, CARPETA, clave.id() + "_" + NOMBRE, MIME, true,
                Map.of(PROPIEDAD, "CR-1")), doc.getValue());

        Map<String, Object> patch = unicoPatch();
        assertEquals(4, patch.size());
        assertEquals(EstadoIngesta.ARCHIVADO, patch.get(Adjunto.COL_ESTADO));
        assertEquals("obj-1", patch.get(Adjunto.COL_CMIS));
        assertTrue(patch.containsKey(Adjunto.COL_RUTA));
        assertNull(patch.get(Adjunto.COL_RUTA));
        assertTrue(patch.containsKey(Adjunto.COL_MENSAJE));
        assertNull(patch.get(Adjunto.COL_MENSAJE));

        verify(almacen).borrar(INGESTA, ruta);
        verify(almacen, never()).mover(any(), any(), any(), any());
        verify(agenda, never()).programar(any(), any());
        verify(repositorio, never()).obtener(any());
        verify(repositorio, never()).obtenerSuplantando(any(), any());
        RegistroIngesta guardado = ultimoRegistroGuardado();
        assertEquals(EstadoIngesta.ARCHIVADO, guardado.estado());
        assertEquals("obj-1", guardado.cmisObjectId());
        assertEquals(1, guardado.intentos());
    }

    @Test
    void conRetencionMueveAAlmacenadosYAgendaLaDepuracion() {
        listoParaProcesar(tipoConPropiedad(TipoDocumento.Momento.AL_SUBIR, 30, false));
        contentManagerAlDia();
        cmCreaDocumento(INGESTA, "obj-1");

        assertEquals(ProcesarIngesta.Desenlace.ARCHIVADO, servicio().ejecutar(mensaje(), LOG));

        verify(almacen).mover(INGESTA, ruta, ALMACENADOS, ruta);
        Map<String, Object> patch = unicoPatch();
        assertEquals(ruta, patch.get(Adjunto.COL_RUTA));
        verify(agenda).programar(HOY.plusDays(30), clave);
        verify(almacen, never()).borrar(any(), any());
        verify(almacen, never()).ponerInmutable(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        RegistroIngesta guardado = ultimoRegistroGuardado();
        assertEquals(ALMACENADOS, guardado.contenedor());
        assertEquals(HOY.plusDays(30), guardado.fechaDepuracion());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void inmutableConWormHabilitadoPoneLaPoliticaSegunElFlagDeBloqueo(boolean bloquear) {
        listoParaProcesar(tipoConPropiedad(TipoDocumento.Momento.AL_SUBIR, 30, true));
        contentManagerAlDia();
        cmCreaDocumento(INGESTA, "obj-1");

        assertEquals(ProcesarIngesta.Desenlace.ARCHIVADO, servicio(false, true, bloquear).ejecutar(mensaje(), LOG));

        verify(almacen).ponerInmutable(ALMACENADOS, ruta,
                HOY.plusDays(31).atStartOfDay(ZoneOffset.UTC).toInstant(), bloquear);
    }

    @Test
    void inmutableConWormDeshabilitadoNoPonePolitica() {
        listoParaProcesar(tipoConPropiedad(TipoDocumento.Momento.AL_SUBIR, 30, true));
        contentManagerAlDia();
        cmCreaDocumento(INGESTA, "obj-1");

        assertEquals(ProcesarIngesta.Desenlace.ARCHIVADO, servicio().ejecutar(mensaje(), LOG));
        verify(almacen, never()).ponerInmutable(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void documentoYaExistenteEnContentManagerNoSeCreaDeNuevo() {
        listoParaProcesar(tipoConPropiedad(TipoDocumento.Momento.AL_SUBIR, null, false));
        contentManagerAlDia();
        when(archivo.buscarPorNombre(TIPO_CMIS, CARPETA, clave.id() + "_" + NOMBRE)).thenReturn(Optional.of("obj-viejo"));

        assertEquals(ProcesarIngesta.Desenlace.ARCHIVADO, servicio().ejecutar(mensaje(), LOG));

        verify(archivo, never()).archivar(any(), any(), anyLong());
        verify(almacen, never()).abrir(any(), any());
        assertEquals("obj-viejo", unicoPatch().get(Adjunto.COL_CMIS));
    }

    @Test
    void siLaFilaDesaparecioTrasCrearSeCompensaBorrandoEnContentManager() {
        listoParaProcesar(tipoConPropiedad(TipoDocumento.Momento.AL_SUBIR, null, false));
        contentManagerAlDia();
        cmCreaDocumento(INGESTA, "obj-1");
        doThrow(new SolicitudNoEncontradaException("404")).when(repositorio).actualizar(eq(clave), anyMap());

        IngestaFallidaException e = assertThrows(IngestaFallidaException.class,
                () -> servicio().ejecutar(mensaje(), LOG));

        assertInstanceOf(SolicitudNoEncontradaException.class, e.getCause());
        verify(archivo).borrar("obj-1");
        verify(almacen, never()).borrar(any(), any());
        // El PATCH a Dataverse falló, pero el registro operativo igual sale de Procesando (ADR-003 §4.2).
        assertEquals(EstadoIngesta.ERROR, ultimoRegistroGuardado().estado());
    }

    @Test
    void siLaFilaDesaparecioConUnDocumentoPreexistenteNoSeBorraDeContentManager() {
        listoParaProcesar(tipoConPropiedad(TipoDocumento.Momento.AL_SUBIR, null, false));
        contentManagerAlDia();
        when(archivo.buscarPorNombre(TIPO_CMIS, CARPETA, clave.id() + "_" + NOMBRE)).thenReturn(Optional.of("obj-viejo"));
        doThrow(new SolicitudNoEncontradaException("404")).when(repositorio).actualizar(eq(clave), anyMap());

        assertThrows(IngestaFallidaException.class, () -> servicio().ejecutar(mensaje(), LOG));
        verify(archivo, never()).borrar(any());
    }

    @Test
    void tipoCmSinCarpetaEsErrorTecnico() {
        TipoDocumento sinCarpeta = new TipoDocumento(tipoId, "PT95", "PT95 - Crédito", TipoDocumento.OrigenTipo.DESCUBIERTO,
                TIPO_CMIS, "Credito", true, true, null, false, TipoDocumento.Destino.CONTENT_MANAGER,
                TipoDocumento.Momento.AL_SUBIR, null, false, true, false, List.of(columna(PROPIEDAD, "numerocredito")));
        listoParaProcesar(sinCarpeta);
        when(archivo.definicionTipo(TIPO_CMIS)).thenReturn(Optional.of(definicion(viva(PROPIEDAD, "string", true, 50))));

        IngestaFallidaException e = assertThrows(IngestaFallidaException.class, () -> servicio().ejecutar(mensaje(), LOG));
        assertInstanceOf(IllegalStateException.class, e.getCause());
        verify(archivo, never()).archivar(any(), any(), anyLong());
        assertEquals(6, codigo(unicoPatch()));
    }

    // ------------------------------------------------------------------ Blob

    @Test
    void tipoSoloBlobLocalQuedaAlmacenadoConSuRetencion() {
        listoParaProcesar(tipoLocal(tipoId, 90));

        assertEquals(ProcesarIngesta.Desenlace.ALMACENADO, servicio().ejecutar(mensaje(), LOG));

        verify(almacen).mover(INGESTA, ruta, ALMACENADOS, ruta);
        Map<String, Object> patch = unicoPatch();
        assertEquals(7, codigo(patch));
        assertEquals(ruta, patch.get(Adjunto.COL_RUTA));
        verify(agenda).programar(HOY.plusDays(90), clave);
        verifyNoInteractions(archivo, as400);
        verify(repositorio, never()).columnas(any());
        RegistroIngesta guardado = ultimoRegistroGuardado();
        assertEquals(EstadoIngesta.ALMACENADO, guardado.estado());
        assertEquals(ALMACENADOS, guardado.contenedor());
    }

    @Test
    void tipoSoloBlobPermanenteNoAgenda() {
        listoParaProcesar(tipoLocal(tipoId, null));
        assertEquals(ProcesarIngesta.Desenlace.ALMACENADO, servicio().ejecutar(mensaje(), LOG));
        verify(agenda, never()).programar(any(), any());
        assertNull(ultimoRegistroGuardado().fechaDepuracion());
    }

    @Test
    void diferidoQuedaAlmacenadoSinFechaAunqueSeaInmutable() {
        listoParaProcesar(tipoConPropiedad(TipoDocumento.Momento.DIFERIDO, 30, true));
        contentManagerAlDia();

        assertEquals(ProcesarIngesta.Desenlace.ALMACENADO, servicio(false, true, true).ejecutar(mensaje(), LOG));

        verify(almacen).mover(INGESTA, ruta, ALMACENADOS, ruta);
        assertEquals(7, codigo(unicoPatch()));
        verify(agenda, never()).programar(any(), any());
        verify(almacen, never()).ponerInmutable(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(archivo, never()).archivar(any(), any(), anyLong());
        assertNull(ultimoRegistroGuardado().fechaDepuracion());
    }

    @Test
    void mensajeDesdeAlmacenadosConTipoCmArchivaSinMoverNiEscanear() {
        registroEn(EstadoIngesta.ALMACENADO, ALMACENADOS, valores);
        when(almacen.existe(ALMACENADOS, ruta)).thenReturn(true);
        when(catalogoTipos.listar()).thenReturn(List.of(tipoConPropiedad(TipoDocumento.Momento.DIFERIDO, null, false)));
        contentManagerAlDia();
        cmCreaDocumento(ALMACENADOS, "obj-2");

        var desenlace = servicio(true, false, false)
                .ejecutar(new MensajeIngesta(clave, ALMACENADOS, ruta, "archivar"), LOG);

        assertEquals(ProcesarIngesta.Desenlace.ARCHIVADO, desenlace);
        verify(almacen, never()).resultadoEscaneo(any(), any());
        verify(almacen, never()).mover(any(), any(), any(), any());
        verify(almacen).borrar(ALMACENADOS, ruta);
        assertEquals("obj-2", unicoPatch().get(Adjunto.COL_CMIS));
    }

    // ------------------------------------------------------------------ fallas técnicas

    @Test
    void excepcionTecnicaMarcaErrorYLanzaIngestaFallida() {
        listoParaProcesar(tipoConPropiedad(TipoDocumento.Momento.AL_SUBIR, null, false));
        contentManagerAlDia();
        when(almacen.tamano(INGESTA, ruta)).thenReturn(1024L);
        when(almacen.abrir(INGESTA, ruta)).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        when(archivo.archivar(any(), any(), anyLong())).thenThrow(new RuntimeException("CM caído"));

        IngestaFallidaException e = assertThrows(IngestaFallidaException.class, () -> servicio().ejecutar(mensaje(), LOG));

        assertEquals("CM caído", e.getCause().getMessage());
        Map<String, Object> patch = unicoPatch();
        assertEquals(6, codigo(patch));
        assertTrue(((String) patch.get(Adjunto.COL_MENSAJE)).contains("CM caído"));
        assertEquals(EstadoIngesta.ERROR, ultimoRegistroGuardado().estado());
        verify(almacen, never()).borrar(any(), any());
    }
}
