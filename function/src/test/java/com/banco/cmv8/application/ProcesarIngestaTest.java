package com.banco.cmv8.application;

import com.banco.cmv8.domain.Cliente;
import com.banco.cmv8.domain.EstadoSolicitud;
import com.banco.cmv8.domain.MensajeIngesta;
import com.banco.cmv8.domain.SolicitudIngesta;
import com.banco.cmv8.domain.port.AlmacenAdjuntos;
import com.banco.cmv8.domain.port.ArchivoDocumental;
import com.banco.cmv8.domain.port.RepositorioSolicitudes;
import com.banco.cmv8.domain.port.ResolutorCliente;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcesarIngestaTest {

    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String RUTA = ID + "/video.mp4";
    private static final Logger LOG = Logger.getAnonymousLogger();

    @Mock
    RepositorioSolicitudes repositorio;
    @Mock
    AlmacenAdjuntos almacen;
    @Mock
    ArchivoDocumental archivo;
    @Mock
    ResolutorCliente resolutor;

    private ProcesarIngesta casoDeUso(boolean exigirEscaneo) {
        return new ProcesarIngesta(repositorio, almacen, archivo, resolutor, exigirEscaneo);
    }

    private static SolicitudIngesta solicitud(EstadoSolicitud estado, String cmisObjectId, String idCliente,
                                              String idCliente2) {
        return new SolicitudIngesta(ID, "video.mp4", 100L, "video/mp4", estado, RUTA, cmisObjectId,
                idCliente, idCliente2, null, null, null, null);
    }

    private static MensajeIngesta mensaje() {
        return new MensajeIngesta(ID, RUTA, "prueba");
    }

    @Test
    void omiteSiYaEstaArchivada() {
        when(repositorio.obtener(ID)).thenReturn(Optional.of(solicitud(EstadoSolicitud.ARCHIVADO, "obj-1", null, null)));

        ProcesarIngesta.Desenlace d = casoDeUso(false).ejecutar(mensaje(), LOG);

        assertEquals(ProcesarIngesta.Desenlace.YA_ARCHIVADO, d);
        verifyNoInteractions(archivo);
        verify(repositorio, never()).actualizarEstado(any(), any());
        verify(repositorio, never()).marcarArchivado(any(), any());
    }

    @Test
    void caminoFelizArchivaMarcaYBorraEnOrden() throws Exception {
        SolicitudIngesta s = solicitud(EstadoSolicitud.SUBIDO, null, null, null);
        InputStream contenido = new ByteArrayInputStream(new byte[100]);
        when(repositorio.obtener(ID)).thenReturn(Optional.of(s));
        when(archivo.buscarPorSolicitud(s)).thenReturn(Optional.empty());
        when(almacen.tamano(RUTA)).thenReturn(100L);
        when(almacen.abrir(RUTA)).thenReturn(contenido);
        when(archivo.archivar(eq(s), eq(contenido), eq(100L), anyMap())).thenReturn("obj-9");

        ProcesarIngesta.Desenlace d = casoDeUso(false).ejecutar(mensaje(), LOG);

        assertEquals(ProcesarIngesta.Desenlace.ARCHIVADO, d);
        InOrder orden = inOrder(repositorio, archivo, almacen);
        orden.verify(repositorio).actualizarEstado(ID, EstadoSolicitud.PROCESANDO);
        orden.verify(archivo).archivar(eq(s), eq(contenido), eq(100L), anyMap());
        orden.verify(repositorio).marcarArchivado(ID, "obj-9");
        orden.verify(almacen).borrar(RUTA);
        verify(repositorio, never()).marcarError(any(), anyString());
    }

    @Test
    void reutilizaDocumentoExistenteEnCm() {
        SolicitudIngesta s = solicitud(EstadoSolicitud.PROCESANDO, null, null, null);
        when(repositorio.obtener(ID)).thenReturn(Optional.of(s));
        when(archivo.buscarPorSolicitud(s)).thenReturn(Optional.of("obj-existente"));

        casoDeUso(false).ejecutar(mensaje(), LOG);

        verify(archivo, never()).archivar(any(), any(), anyLong(), anyMap());
        verify(repositorio).marcarArchivado(ID, "obj-existente");
        verify(almacen).borrar(RUTA);
    }

    @Test
    void resuelveClienteCuandoFaltaElSegundoId() {
        SolicitudIngesta s = solicitud(EstadoSolicitud.SUBIDO, null, "C1", null);
        Cliente cliente = new Cliente("C1", "C1-B", "Ana", "AF", "D1");
        when(repositorio.obtener(ID)).thenReturn(Optional.of(s));
        when(resolutor.resolver("C1")).thenReturn(Optional.of(cliente));
        when(archivo.buscarPorSolicitud(any())).thenReturn(Optional.of("obj-1"));

        casoDeUso(false).ejecutar(mensaje(), LOG);

        verify(repositorio).registrarCliente(ID, cliente);
        verify(archivo).buscarPorSolicitud(s.conCliente(cliente));
    }

    @Test
    void errorMarcaErrorYRelanza() {
        SolicitudIngesta s = solicitud(EstadoSolicitud.SUBIDO, null, null, null);
        RuntimeException causa = new RuntimeException("CM caído");
        when(repositorio.obtener(ID)).thenReturn(Optional.of(s));
        when(archivo.buscarPorSolicitud(s)).thenReturn(Optional.empty());
        when(almacen.tamano(RUTA)).thenReturn(100L);
        when(almacen.abrir(RUTA)).thenReturn(new ByteArrayInputStream(new byte[100]));
        when(archivo.archivar(any(), any(), anyLong(), anyMap())).thenThrow(causa);

        IngestaFallidaException e = assertThrows(IngestaFallidaException.class,
                () -> casoDeUso(false).ejecutar(mensaje(), LOG));

        assertSame(causa, e.getCause());
        verify(repositorio).marcarError(eq(ID), anyString());
        verify(repositorio, never()).marcarArchivado(any(), any());
        verify(almacen, never()).borrar(anyString());
    }

    @Test
    void descartaMensajeConRutaAjena() {
        MensajeIngesta ajeno = new MensajeIngesta(ID, UUID.randomUUID() + "/x.pdf", "prueba");

        assertEquals(ProcesarIngesta.Desenlace.OMITIDO, casoDeUso(false).ejecutar(ajeno, LOG));
        verifyNoInteractions(repositorio, almacen, archivo);
    }

    @Test
    void conEscaneoExigidoRechazaMalicioso() {
        when(repositorio.obtener(ID)).thenReturn(Optional.of(solicitud(EstadoSolicitud.SUBIDO, null, null, null)));
        when(almacen.resultadoEscaneo(RUTA)).thenReturn(Optional.of("Malicious"));

        assertEquals(ProcesarIngesta.Desenlace.RECHAZADO, casoDeUso(true).ejecutar(mensaje(), LOG));
        verify(repositorio).marcarRechazado(eq(ID), anyString());
        verify(almacen).borrar(RUTA);
        verifyNoInteractions(archivo);
    }

    @Test
    void conEscaneoExigidoOmiteSinResultado() {
        when(repositorio.obtener(ID)).thenReturn(Optional.of(solicitud(EstadoSolicitud.SUBIDO, null, null, null)));
        when(almacen.resultadoEscaneo(RUTA)).thenReturn(Optional.empty());

        assertEquals(ProcesarIngesta.Desenlace.OMITIDO, casoDeUso(true).ejecutar(mensaje(), LOG));
        verifyNoInteractions(archivo);
        verify(repositorio, never()).actualizarEstado(any(), any());
    }
}
