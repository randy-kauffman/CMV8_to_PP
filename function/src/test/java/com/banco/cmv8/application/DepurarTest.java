package com.banco.cmv8.application;

import com.banco.cmv8.domain.Adjunto;
import com.banco.cmv8.domain.ClaveAdjunto;
import com.banco.cmv8.domain.EstadoIngesta;
import com.banco.cmv8.domain.RegistroIngesta;
import com.banco.cmv8.domain.port.AgendaDepuracion;
import com.banco.cmv8.domain.port.AlmacenAdjuntos;
import com.banco.cmv8.domain.port.RegistroIngestas;
import com.banco.cmv8.domain.port.RepositorioAdjuntos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.banco.cmv8.Datos.ALMACENADOS;
import static com.banco.cmv8.Datos.HOY;
import static com.banco.cmv8.Datos.LOG;
import static com.banco.cmv8.Datos.RELOJ;
import static com.banco.cmv8.Datos.registro;
import static com.banco.cmv8.Datos.ruta;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DepurarTest {

    @Mock
    private AgendaDepuracion agenda;
    @Mock
    private RegistroIngestas registros;
    @Mock
    private RepositorioAdjuntos repositorio;
    @Mock
    private AlmacenAdjuntos almacen;

    private final ClaveAdjunto clave = com.banco.cmv8.Datos.clave();
    private final String ruta = ruta(clave);

    private Depurar servicio() {
        return new Depurar(agenda, registros, repositorio, almacen, RELOJ);
    }

    /** Puntero en ayer y la clave agendada hoy con el registro en el estado dado. */
    private RegistroIngesta agendadoHoy(EstadoIngesta estado) {
        when(agenda.ultimoDiaProcesado()).thenReturn(Optional.of(HOY.minusDays(1)));
        when(agenda.pendientes(HOY)).thenReturn(List.of(clave));
        RegistroIngesta r = registro(clave, estado, HOY);
        when(registros.obtener(clave)).thenReturn(Optional.of(r));
        return r;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> patch() {
        ArgumentCaptor<Map<String, Object>> cambios = ArgumentCaptor.forClass(Map.class);
        verify(repositorio).actualizar(eq(clave), cambios.capture());
        return cambios.getValue();
    }

    private RegistroIngesta guardado() {
        ArgumentCaptor<RegistroIngesta> captor = ArgumentCaptor.forClass(RegistroIngesta.class);
        verify(registros).guardar(captor.capture());
        return captor.getValue();
    }

    @ParameterizedTest
    @EnumSource(value = EstadoIngesta.class, names = {"DETENIDA", "PROCESANDO"})
    void conHumanoOWorkerPendienteNoBorraYPostergaSieteDias(EstadoIngesta estado) {
        agendadoHoy(estado);

        assertEquals(0, servicio().ejecutar(LOG));

        verify(agenda).cancelar(HOY, clave);
        verify(agenda).programar(HOY.plusDays(Depurar.DIAS_POSTERGACION), clave);
        assertEquals(HOY.plusDays(7), guardado().fechaDepuracion());
        verify(almacen, never()).borrar(any(), any());
        verify(repositorio, never()).actualizar(any(), any());
        verify(agenda).marcarDiaProcesado(HOY);
    }

    @Test
    void observadaVencidaPasaARechazado() {
        agendadoHoy(EstadoIngesta.OBSERVADA);
        when(almacen.existe(ALMACENADOS, ruta)).thenReturn(true);

        assertEquals(1, servicio().ejecutar(LOG));

        verify(almacen).borrar(ALMACENADOS, ruta);
        Map<String, Object> p = patch();
        assertEquals(EstadoIngesta.RECHAZADO, p.get(Adjunto.COL_ESTADO));
        assertTrue(p.containsKey(Adjunto.COL_RUTA));
        assertNull(p.get(Adjunto.COL_RUTA));
        assertEquals("Vencida sin corrección", p.get(Adjunto.COL_MENSAJE));
        RegistroIngesta g = guardado();
        assertEquals(EstadoIngesta.RECHAZADO, g.estado());
        assertNull(g.fechaDepuracion());
        verify(agenda).cancelar(HOY, clave);
    }

    @Test
    void archivadoSoloVaciaLaRuta() {
        agendadoHoy(EstadoIngesta.ARCHIVADO);
        when(almacen.existe(ALMACENADOS, ruta)).thenReturn(true);

        assertEquals(1, servicio().ejecutar(LOG));

        Map<String, Object> p = patch();
        assertEquals(EstadoIngesta.ARCHIVADO, p.get(Adjunto.COL_ESTADO));
        assertNull(p.get(Adjunto.COL_RUTA));
        assertFalse(p.containsKey(Adjunto.COL_MENSAJE));
        assertEquals(EstadoIngesta.ARCHIVADO, guardado().estado());
    }

    @Test
    void almacenadoPasaADepurado() {
        agendadoHoy(EstadoIngesta.ALMACENADO);
        when(almacen.existe(ALMACENADOS, ruta)).thenReturn(true);

        assertEquals(1, servicio().ejecutar(LOG));

        verify(almacen).borrar(ALMACENADOS, ruta);
        assertEquals(EstadoIngesta.DEPURADO, patch().get(Adjunto.COL_ESTADO));
        assertEquals(EstadoIngesta.DEPURADO, guardado().estado());
    }

    @Test
    void blobYaBorradoIgualCierraDataverse() {
        agendadoHoy(EstadoIngesta.ALMACENADO);
        when(almacen.existe(ALMACENADOS, ruta)).thenReturn(false);

        assertEquals(1, servicio().ejecutar(LOG));

        verify(almacen, never()).borrar(any(), any());
        assertEquals(EstadoIngesta.DEPURADO, patch().get(Adjunto.COL_ESTADO));
    }

    @Test
    void storageRechazaElBorradoPorWormYSePosterga() {
        agendadoHoy(EstadoIngesta.ALMACENADO);
        when(almacen.existe(ALMACENADOS, ruta)).thenReturn(true);
        doThrow(new RuntimeException("BlobImmutableDueToPolicy")).when(almacen).borrar(ALMACENADOS, ruta);

        assertEquals(0, servicio().ejecutar(LOG));

        verify(agenda).programar(HOY.plusDays(7), clave);
        verify(repositorio, never()).actualizar(any(), any());
        assertEquals(HOY.plusDays(7), guardado().fechaDepuracion());
    }

    @Test
    void filaYaInexistenteEnDataverseCierraSoloElRegistro() {
        agendadoHoy(EstadoIngesta.ALMACENADO);
        when(almacen.existe(ALMACENADOS, ruta)).thenReturn(false);
        doThrow(new SolicitudNoEncontradaException("404")).when(repositorio).actualizar(eq(clave), any());

        assertEquals(1, servicio().ejecutar(LOG));
        assertEquals(EstadoIngesta.DEPURADO, guardado().estado());
    }

    @Test
    void fechaExtendidaDespuesDeAgendarSoloCancelaLaEntradaVieja() {
        when(agenda.pendientes(HOY)).thenReturn(List.of(clave));
        when(registros.obtener(clave)).thenReturn(Optional.of(registro(clave, EstadoIngesta.ALMACENADO, HOY.plusDays(30))));

        assertEquals(0, servicio().ejecutar(LOG));

        verify(agenda).cancelar(HOY, clave);
        verify(almacen, never()).borrar(any(), any());
        verify(repositorio, never()).actualizar(any(), any());
    }

    @Test
    void sinRegistroOperativoCancelaLaEntrada() {
        when(agenda.pendientes(HOY)).thenReturn(List.of(clave));
        when(registros.obtener(clave)).thenReturn(Optional.empty());
        assertEquals(0, servicio().ejecutar(LOG));
        verify(agenda).cancelar(HOY, clave);
    }

    @Test
    void procesaLosDiasAtrasadosDesdeElUltimoProcesado() {
        when(agenda.ultimoDiaProcesado()).thenReturn(Optional.of(HOY.minusDays(3)));

        servicio().ejecutar(LOG);

        InOrder orden = inOrder(agenda);
        for (LocalDate dia = HOY.minusDays(2); !dia.isAfter(HOY); dia = dia.plusDays(1)) {
            orden.verify(agenda).pendientes(dia);
            orden.verify(agenda).marcarDiaProcesado(dia);
        }
        verify(agenda, never()).pendientes(HOY.minusDays(3));
    }

    @Test
    void sinPunteroProcesaSoloHoy() {
        servicio().ejecutar(LOG);
        verify(agenda).pendientes(HOY);
        verify(agenda).marcarDiaProcesado(HOY);
        verify(agenda, never()).pendientes(HOY.minusDays(1));
    }

    @Test
    void unDiaConFallasNoAvanzaElPunteroParaReintentarloManana() {
        when(agenda.ultimoDiaProcesado()).thenReturn(Optional.of(HOY.minusDays(2)));
        when(agenda.pendientes(HOY.minusDays(1))).thenReturn(List.of(clave));
        when(registros.obtener(clave)).thenThrow(new RuntimeException("Azure Table caída"));

        servicio().ejecutar(LOG);

        verify(agenda).pendientes(HOY);
        verify(agenda, never()).marcarDiaProcesado(any());
    }
}
