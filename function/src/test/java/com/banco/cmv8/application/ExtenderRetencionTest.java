package com.banco.cmv8.application;

import com.banco.cmv8.application.catalogo.CatalogoEnCache;
import com.banco.cmv8.domain.ClaveAdjunto;
import com.banco.cmv8.domain.ContextoLlamador;
import com.banco.cmv8.domain.EstadoIngesta;
import com.banco.cmv8.domain.RegistroIngesta;
import com.banco.cmv8.domain.ValidacionException;
import com.banco.cmv8.domain.catalogo.TipoDocumento;
import com.banco.cmv8.domain.port.AgendaDepuracion;
import com.banco.cmv8.domain.port.AlmacenAdjuntos;
import com.banco.cmv8.domain.port.CatalogoTipos;
import com.banco.cmv8.domain.port.RegistroIngestas;
import com.banco.cmv8.domain.port.RepositorioAdjuntos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.banco.cmv8.Datos.ALMACENADOS;
import static com.banco.cmv8.Datos.HOY;
import static com.banco.cmv8.Datos.LOG;
import static com.banco.cmv8.Datos.RELOJ;
import static com.banco.cmv8.Datos.adjunto;
import static com.banco.cmv8.Datos.llamador;
import static com.banco.cmv8.Datos.registro;
import static com.banco.cmv8.Datos.ruta;
import static com.banco.cmv8.Datos.tipo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExtenderRetencionTest {

    @Mock
    private RepositorioAdjuntos repositorio;
    @Mock
    private RegistroIngestas registros;
    @Mock
    private AgendaDepuracion agenda;
    @Mock
    private AlmacenAdjuntos almacen;
    @Mock
    private CatalogoTipos catalogoTipos;

    private final ContextoLlamador llamador = llamador();
    private final ClaveAdjunto clave = com.banco.cmv8.Datos.clave();
    private final UUID tipoId = UUID.randomUUID();
    private final LocalDate actual = HOY.plusDays(10);

    private ExtenderRetencion servicio(boolean worm) {
        // Compuerta de administración: en estos tests el llamador siempre es administrador documental.
        org.mockito.Mockito.lenient().when(catalogoTipos.puedeAdministrar(any())).thenReturn(true);
        return new ExtenderRetencion(repositorio, registros, agenda, almacen,
                new CatalogoEnCache(catalogoTipos, Duration.ofMinutes(5), RELOJ), catalogoTipos, worm, true, RELOJ);
    }

    private void adjuntoConFecha(boolean inmutable) {
        when(repositorio.obtenerSuplantando(clave, llamador)).thenReturn(Optional.of(
                adjunto(clave, EstadoIngesta.ALMACENADO, tipoId, ruta(clave), Map.of())));
        RegistroIngesta r = registro(clave, EstadoIngesta.ALMACENADO, tipoId, ALMACENADOS, Map.of()).conFechaDepuracion(actual);
        when(registros.obtener(clave)).thenReturn(Optional.of(r));
        if (inmutable) {
            when(catalogoTipos.listar()).thenReturn(List.of(tipo(tipoId, TipoDocumento.Momento.AL_SUBIR, 10, true)));
        }
    }

    private RegistroIngesta guardado() {
        ArgumentCaptor<RegistroIngesta> captor = ArgumentCaptor.forClass(RegistroIngesta.class);
        verify(registros).guardar(captor.capture());
        return captor.getValue();
    }

    @Test
    void extiendeMoviendoLaEntradaDeLaAgenda() {
        adjuntoConFecha(false);
        servicio(true).ejecutar(clave, Optional.of(HOY.plusDays(100)), llamador, LOG);
        verify(agenda).cancelar(actual, clave);
        verify(agenda).programar(HOY.plusDays(100), clave);
        assertEquals(HOY.plusDays(100), guardado().fechaDepuracion());
        verify(almacen, never()).ponerInmutable(any(), any(), any(), anyBoolean());
    }

    @Test
    void fechaVaciaCongelaLaRetencion() {
        adjuntoConFecha(false);
        servicio(true).ejecutar(clave, Optional.empty(), llamador, LOG);
        verify(agenda).cancelar(actual, clave);
        verify(agenda, never()).programar(any(), any());
        assertNull(guardado().fechaDepuracion());
    }

    @Test
    void fechaPasadaEsValidacion() {
        adjuntoConFecha(false);
        assertThrows(ValidacionException.class,
                () -> servicio(true).ejecutar(clave, Optional.of(HOY.minusDays(1)), llamador, LOG));
        verifyNoInteractions(agenda);
        verify(registros, never()).guardar(any());
    }

    @Test
    void inmutableNoAdmiteAcortar() {
        adjuntoConFecha(true);
        assertThrows(ValidacionException.class,
                () -> servicio(true).ejecutar(clave, Optional.of(HOY.plusDays(5)), llamador, LOG));
        verifyNoInteractions(agenda, almacen);
    }

    @Test
    void inmutableAlExtenderRenuevaLaPolitica() {
        adjuntoConFecha(true);
        servicio(true).ejecutar(clave, Optional.of(HOY.plusDays(100)), llamador, LOG);
        verify(almacen).ponerInmutable(ALMACENADOS, ruta(clave),
                HOY.plusDays(101).atStartOfDay(ZoneOffset.UTC).toInstant(), true);
    }

    @Test
    void inmutableConWormDeshabilitadoPuedeAcortar() {
        adjuntoConFecha(true);
        servicio(false).ejecutar(clave, Optional.of(HOY.plusDays(5)), llamador, LOG);
        verify(agenda).programar(HOY.plusDays(5), clave);
        verifyNoInteractions(almacen);
    }

    @Test
    void sinAccesoALaFilaEs404() {
        when(repositorio.obtenerSuplantando(clave, llamador)).thenReturn(Optional.empty());
        assertThrows(SolicitudNoEncontradaException.class,
                () -> servicio(true).ejecutar(clave, Optional.of(HOY.plusDays(100)), llamador, LOG));
        verifyNoInteractions(registros, agenda);
    }

    @Test
    void rechazaSinRolDeAdministradorDocumental() {
        org.mockito.Mockito.when(catalogoTipos.puedeAdministrar(any())).thenReturn(false);
        ExtenderRetencion servicio = new ExtenderRetencion(repositorio, registros, agenda, almacen,
                new CatalogoEnCache(catalogoTipos, Duration.ofMinutes(5), RELOJ), catalogoTipos, false, true, RELOJ);
        assertThrows(AccesoDenegadoException.class,
                () -> servicio.ejecutar(new ClaveAdjunto("cred_adjuntocredito", UUID.randomUUID()),
                        Optional.of(HOY.plusDays(10)), llamador(), LOG));
        verify(repositorio, never()).obtenerSuplantando(any(), any());
    }
}
