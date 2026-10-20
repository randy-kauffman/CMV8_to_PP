package com.banco.cmv8.application.catalogo;

import com.banco.cmv8.application.AccesoDenegadoException;
import com.banco.cmv8.application.SolicitudNoEncontradaException;
import com.banco.cmv8.domain.ClaveAdjunto;
import com.banco.cmv8.domain.ContextoLlamador;
import com.banco.cmv8.domain.EstadoIngesta;
import com.banco.cmv8.domain.MensajeIngesta;
import com.banco.cmv8.domain.RegistroIngesta;
import com.banco.cmv8.domain.catalogo.Conflicto;
import com.banco.cmv8.domain.catalogo.ConflictoRegistrado;
import com.banco.cmv8.domain.port.CatalogoTipos;
import com.banco.cmv8.domain.port.ColaIngesta;
import com.banco.cmv8.domain.port.RegistroIngestas;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.banco.cmv8.Datos.INGESTA;
import static com.banco.cmv8.Datos.LOG;
import static com.banco.cmv8.Datos.llamador;
import static com.banco.cmv8.Datos.registro;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReanudarConflictoTest {

    private static final String CLAVE_CONFLICTO = "tipo|clb:X|TIPO_AUSENTE";

    @Mock
    private CatalogoTipos catalogo;
    @Mock
    private CatalogoEnCache cache;
    @Mock
    private RegistroIngestas registros;
    @Mock
    private ColaIngesta cola;

    private final ContextoLlamador llamador = llamador();
    private final UUID conflictoId = UUID.randomUUID();

    private ReanudarConflicto servicio() {
        return new ReanudarConflicto(catalogo, cache, registros);
    }

    private ConflictoRegistrado conflicto(boolean abierto) {
        return new ConflictoRegistrado(conflictoId, CLAVE_CONFLICTO, UUID.randomUUID(), Conflicto.Clase.TIPO_AUSENTE, abierto);
    }

    @Test
    void sinRolEsAccesoDenegado() {
        when(catalogo.puedeAdministrar(llamador)).thenReturn(false);
        assertThrows(AccesoDenegadoException.class, () -> servicio().ejecutar(conflictoId, llamador, cola, LOG));
        verifyNoInteractions(registros, cola, cache);
    }

    @Test
    void conflictoInexistenteEs404() {
        when(catalogo.puedeAdministrar(llamador)).thenReturn(true);
        when(catalogo.obtenerConflicto(conflictoId)).thenReturn(Optional.empty());
        assertThrows(SolicitudNoEncontradaException.class, () -> servicio().ejecutar(conflictoId, llamador, cola, LOG));
        verifyNoInteractions(registros, cola);
    }

    @Test
    void resuelveInvalidaElCacheYReencolaSoloLosDetenidos() {
        when(catalogo.puedeAdministrar(llamador)).thenReturn(true);
        when(catalogo.obtenerConflicto(conflictoId)).thenReturn(Optional.of(conflicto(true)));
        ClaveAdjunto a = com.banco.cmv8.Datos.clave();
        ClaveAdjunto b = com.banco.cmv8.Datos.clave();
        RegistroIngesta detenido = registro(a, EstadoIngesta.DETENIDA, UUID.randomUUID(), INGESTA, Map.of())
                .conConflicto(CLAVE_CONFLICTO).conMensaje("Conflicto de catálogo");
        RegistroIngesta yaObservado = registro(b, EstadoIngesta.OBSERVADA, UUID.randomUUID(), INGESTA, Map.of())
                .conConflicto(CLAVE_CONFLICTO);
        when(registros.listarPorConflicto(CLAVE_CONFLICTO)).thenReturn(List.of(detenido, yaObservado));

        assertEquals(1, servicio().ejecutar(conflictoId, llamador, cola, LOG));

        InOrder orden = inOrder(cache, cola);
        orden.verify(cache).invalidar();
        orden.verify(cola).encolar(new MensajeIngesta(a, INGESTA, detenido.ruta(), "conflicto"));
        verify(catalogo).marcarConflictoResuelto(conflictoId);
        verify(registros).borrarConflicto(CLAVE_CONFLICTO);
        ArgumentCaptor<RegistroIngesta> guardado = ArgumentCaptor.forClass(RegistroIngesta.class);
        verify(registros).guardar(guardado.capture());
        assertEquals(EstadoIngesta.SUBIDO, guardado.getValue().estado());
        assertNull(guardado.getValue().conflictoClave());
        assertNull(guardado.getValue().mensaje());
    }

    @Test
    void conflictoYaCerradoNoSeVuelveAMarcarPeroIgualReencola() {
        when(catalogo.puedeAdministrar(llamador)).thenReturn(true);
        when(catalogo.obtenerConflicto(conflictoId)).thenReturn(Optional.of(conflicto(false)));
        servicio().ejecutar(conflictoId, llamador, cola, LOG);
        verify(catalogo, never()).marcarConflictoResuelto(any());
        verify(registros).listarPorConflicto(CLAVE_CONFLICTO);
    }
}
