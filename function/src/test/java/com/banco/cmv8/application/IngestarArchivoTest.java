package com.banco.cmv8.application;

import com.banco.cmv8.application.catalogo.CatalogoEnCache;
import com.banco.cmv8.domain.Adjunto;
import com.banco.cmv8.domain.ClaveAdjunto;
import com.banco.cmv8.domain.ContextoLlamador;
import com.banco.cmv8.domain.EstadoIngesta;
import com.banco.cmv8.domain.MensajeIngesta;
import com.banco.cmv8.domain.PoliticaSas;
import com.banco.cmv8.domain.RegistroIngesta;
import com.banco.cmv8.domain.ValidacionException;
import com.banco.cmv8.domain.catalogo.TipoDocumento;
import com.banco.cmv8.domain.port.AlmacenAdjuntos;
import com.banco.cmv8.domain.port.CatalogoTipos;
import com.banco.cmv8.domain.port.ColaIngesta;
import com.banco.cmv8.domain.port.RegistroIngestas;
import com.banco.cmv8.domain.port.RepositorioAdjuntos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.banco.cmv8.Datos.AHORA;
import static com.banco.cmv8.Datos.INGESTA;
import static com.banco.cmv8.Datos.LOG;
import static com.banco.cmv8.Datos.RELOJ;
import static com.banco.cmv8.Datos.llamador;
import static com.banco.cmv8.Datos.tipoCm;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestarArchivoTest {

    @Mock
    private RepositorioAdjuntos repositorio;
    @Mock
    private RegistroIngestas registros;
    @Mock
    private CatalogoTipos catalogoTipos;
    @Mock
    private AlmacenAdjuntos almacen;
    @Mock
    private ColaIngesta cola;

    private final ContextoLlamador llamador = llamador();
    private final ClaveAdjunto clave = com.banco.cmv8.Datos.clave();
    private final String ruta = clave.ruta("factura.pdf");
    private final UUID tipoId = UUID.randomUUID();
    private final InputStream contenido = new ByteArrayInputStream(new byte[10]);

    private IngestarArchivo servicio() {
        return new IngestarArchivo(new PoliticaSas(1000L, RELOJ), repositorio, registros,
                new CatalogoEnCache(catalogoTipos, Duration.ofMinutes(5), RELOJ), almacen, INGESTA, RELOJ);
    }

    /** Fila creada por el flujo con el conector de Dataverse: sin nombre, tamaño ni tipo de contenido todavía. */
    private void filaDelFlujo(EstadoIngesta estado) {
        when(repositorio.registrarRutaSuplantando(clave, ruta, llamador)).thenReturn(new Adjunto(clave, null, null, null,
                estado, ruta, null, tipoId, null, Map.of("cred_numerocredito", "CR-1"), AHORA));
    }

    @Test
    void sinOidEsNoAutenticadoSinTocarDataverse() {
        assertThrows(NoAutenticadoException.class, () -> servicio().ejecutar(clave, "factura.pdf", "application/pdf", 10,
                contenido, com.banco.cmv8.Datos.sinOid(), cola, LOG));
        verifyNoInteractions(repositorio, almacen);
    }

    @Test
    void tamanoMayorAlLimiteEsValidacion() {
        assertThrows(ValidacionException.class, () -> servicio().ejecutar(clave, "factura.pdf", "application/pdf", 5000,
                contenido, llamador, cola, LOG));
        verifyNoInteractions(repositorio, almacen);
    }

    @Test
    void estadoQueNoAdmiteSubidaEs409YNoEscribeElBlob() {
        filaDelFlujo(EstadoIngesta.ARCHIVADO);
        assertThrows(EstadoInvalidoException.class, () -> servicio().ejecutar(clave, "factura.pdf", "application/pdf", 10,
                contenido, llamador, cola, LOG));
        verifyNoInteractions(almacen, cola);
    }

    @Test
    void tipoQueNoEstaEnUsoEsValidacion() {
        filaDelFlujo(EstadoIngesta.CREADA);
        when(catalogoTipos.listar()).thenReturn(List.of(new TipoDocumento(tipoId, "X", "X",
                TipoDocumento.OrigenTipo.LOCAL, null, null, false, false, null, false, null, null, null, false, true, false,
                List.of())));
        assertThrows(ValidacionException.class, () -> servicio().ejecutar(clave, "factura.pdf", "application/pdf", 10,
                contenido, llamador, cola, LOG));
        verify(almacen, never()).escribir(any(), any(), any(), anyLong(), any());
    }

    @Test
    void caminoFelizEscribeElBlobGuardaSubidoConLosDatosDelFlujoYEncola() {
        filaDelFlujo(EstadoIngesta.CREADA);
        when(catalogoTipos.listar()).thenReturn(List.of(tipoCm(tipoId)));

        String resultado = servicio().ejecutar(clave, "factura.pdf", "application/pdf", 10, contenido, llamador, cola, LOG);

        assertEquals(ruta, resultado);
        verify(almacen).escribir(INGESTA, ruta, contenido, 10, "application/pdf");
        ArgumentCaptor<RegistroIngesta> guardado = ArgumentCaptor.forClass(RegistroIngesta.class);
        verify(registros).guardar(guardado.capture());
        RegistroIngesta r = guardado.getValue();
        assertEquals(EstadoIngesta.SUBIDO, r.estado());
        assertEquals("factura.pdf", r.nombreArchivo());
        assertEquals(10L, r.tamanoBytes());
        assertEquals("application/pdf", r.contentType());
        assertEquals("oid-1", r.solicitanteOid());
        assertEquals(AHORA, r.subidoEn());
        assertEquals("CR-1", r.valores().get("cred_numerocredito"));
        verify(repositorio).actualizar(clave, Map.of(Adjunto.COL_ESTADO, EstadoIngesta.SUBIDO,
                Adjunto.COL_NOMBRE_ARCHIVO, "factura.pdf", Adjunto.COL_TAMANO, 10L,
                Adjunto.COL_CONTENT_TYPE, "application/pdf"));
        verify(cola).encolar(new MensajeIngesta(clave, INGESTA, ruta, "flujo"));
    }

    @Test
    void sinContentTypeUsaOctetStream() {
        filaDelFlujo(EstadoIngesta.ERROR);
        when(catalogoTipos.listar()).thenReturn(List.of(tipoCm(tipoId)));
        servicio().ejecutar(clave, "factura.pdf", " ", 10, contenido, llamador, cola, LOG);
        verify(almacen).escribir(INGESTA, ruta, contenido, 10, "application/octet-stream");
    }
}
