package com.banco.cmv8.application;

import com.banco.cmv8.domain.Adjunto;
import com.banco.cmv8.domain.ClaveAdjunto;
import com.banco.cmv8.domain.ContextoLlamador;
import com.banco.cmv8.domain.EstadoIngesta;
import com.banco.cmv8.domain.MensajeIngesta;
import com.banco.cmv8.domain.RegistroIngesta;
import com.banco.cmv8.domain.port.AgendaDepuracion;
import com.banco.cmv8.domain.port.ColaIngesta;
import com.banco.cmv8.domain.port.RegistroIngestas;
import com.banco.cmv8.domain.port.RepositorioAdjuntos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.banco.cmv8.Datos.HOY;
import static com.banco.cmv8.Datos.INGESTA;
import static com.banco.cmv8.Datos.LOG;
import static com.banco.cmv8.Datos.adjunto;
import static com.banco.cmv8.Datos.llamador;
import static com.banco.cmv8.Datos.registro;
import static com.banco.cmv8.Datos.ruta;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReanudarTest {

    @Mock
    private RepositorioAdjuntos repositorio;
    @Mock
    private RegistroIngestas registros;
    @Mock
    private AgendaDepuracion agenda;
    @Mock
    private ColaIngesta cola;

    private final ContextoLlamador llamador = llamador();
    private final ClaveAdjunto clave = com.banco.cmv8.Datos.clave();
    private final String ruta = ruta(clave);
    private final UUID tipoId = UUID.randomUUID();

    private Reanudar servicio() {
        return new Reanudar(repositorio, registros, agenda, VinculoUsuario.desactivado());
    }

    @Test
    void observadaCorregidaVuelveASubidoConLosValoresNuevosYSeReencola() {
        when(repositorio.obtenerSuplantando(clave, llamador)).thenReturn(Optional.of(
                adjunto(clave, EstadoIngesta.OBSERVADA, tipoId, ruta, Map.of("cred_numerocredito", "CR-corregido"))));
        when(registros.obtener(clave)).thenReturn(Optional.of(registro(clave, EstadoIngesta.OBSERVADA, tipoId, INGESTA, Map.of())
                .conMensaje("Falta el valor").conFechaDepuracion(HOY.plusDays(15))));

        servicio().ejecutar(clave, llamador, cola, LOG);

        verify(agenda).cancelar(HOY.plusDays(15), clave);
        ArgumentCaptor<RegistroIngesta> guardado = ArgumentCaptor.forClass(RegistroIngesta.class);
        verify(registros).guardar(guardado.capture());
        RegistroIngesta r = guardado.getValue();
        assertEquals(EstadoIngesta.SUBIDO, r.estado());
        assertEquals("CR-corregido", r.valores().get("cred_numerocredito"));
        assertNull(r.mensaje());
        assertNull(r.fechaDepuracion());
        verify(repositorio).actualizar(clave, Map.of(Adjunto.COL_ESTADO, EstadoIngesta.SUBIDO));
        verify(cola).encolar(new MensajeIngesta(clave, INGESTA, ruta, "reanudar"));
    }

    @Test
    void errorTambienSeReanudaSinAgendaQueCancelar() {
        when(repositorio.obtenerSuplantando(clave, llamador)).thenReturn(Optional.of(
                adjunto(clave, EstadoIngesta.ERROR, tipoId, ruta, Map.of())));
        when(registros.obtener(clave)).thenReturn(Optional.of(registro(clave, EstadoIngesta.ERROR, tipoId, INGESTA, Map.of())));

        servicio().ejecutar(clave, llamador, cola, LOG);

        verifyNoInteractions(agenda);
        verify(cola).encolar(new MensajeIngesta(clave, INGESTA, ruta, "reanudar"));
    }

    @Test
    void otroEstadoNoSeReanuda() {
        when(repositorio.obtenerSuplantando(clave, llamador)).thenReturn(Optional.of(
                adjunto(clave, EstadoIngesta.DETENIDA, tipoId, ruta, Map.of())));
        assertThrows(EstadoInvalidoException.class, () -> servicio().ejecutar(clave, llamador, cola, LOG));
        verifyNoInteractions(cola, registros);
        verify(repositorio, never()).actualizar(any(), any());
    }

    @Test
    void sinRegistroOperativoEs404() {
        when(repositorio.obtenerSuplantando(clave, llamador)).thenReturn(Optional.of(
                adjunto(clave, EstadoIngesta.OBSERVADA, tipoId, ruta, Map.of())));
        when(registros.obtener(clave)).thenReturn(Optional.empty());
        assertThrows(SolicitudNoEncontradaException.class, () -> servicio().ejecutar(clave, llamador, cola, LOG));
        verifyNoInteractions(cola);
    }

    @Test
    void sinArchivoEs404() {
        when(repositorio.obtenerSuplantando(clave, llamador)).thenReturn(Optional.of(
                adjunto(clave, EstadoIngesta.OBSERVADA, tipoId, null, Map.of())));
        when(registros.obtener(clave)).thenReturn(Optional.of(registro(clave, EstadoIngesta.OBSERVADA, tipoId, INGESTA, Map.of())));
        assertThrows(SolicitudNoEncontradaException.class, () -> servicio().ejecutar(clave, llamador, cola, LOG));
        verifyNoInteractions(cola, agenda);
    }
}
