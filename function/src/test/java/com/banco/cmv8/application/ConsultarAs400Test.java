package com.banco.cmv8.application;

import com.banco.cmv8.domain.ValidacionException;
import com.banco.cmv8.domain.port.ResolutorAs400;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.banco.cmv8.Datos.LOG;
import static com.banco.cmv8.Datos.llamador;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConsultarAs400Test {

    @Mock
    private ResolutorAs400 as400;

    @Test
    void sinOidEsNoAutenticado() {
        assertThrows(NoAutenticadoException.class,
                () -> new ConsultarAs400(as400).ejecutar("cliente", Map.of(), com.banco.cmv8.Datos.sinOid(), LOG));
        verifyNoInteractions(as400);
    }

    @Test
    void consultaNoDeclaradaEsValidacion() {
        when(as400.consultasDeclaradas()).thenReturn(Set.of("cliente"));
        assertThrows(ValidacionException.class,
                () -> new ConsultarAs400(as400).ejecutar("DROP TABLE", Map.of(), llamador(), LOG));
        verify(as400, never()).consultar(any(), any());
    }

    @Test
    void consultaDeclaradaDevuelveLaFila() {
        when(as400.consultasDeclaradas()).thenReturn(Set.of("cliente"));
        when(as400.consultar("cliente", Map.of("idcliente", "C-9"))).thenReturn(Optional.of(Map.of("NOMBRE", "Juan")));
        var fila = new ConsultarAs400(as400).ejecutar(" Cliente ", Map.of("idcliente", "C-9"), llamador(), LOG);
        assertEquals(Map.of("NOMBRE", "Juan"), fila.orElseThrow());
    }

    @Test
    void parametroFaltanteEsValidacion() {
        when(as400.consultasDeclaradas()).thenReturn(Set.of("cliente"));
        when(as400.consultar("cliente", Map.of())).thenThrow(new IllegalArgumentException("falta idcliente"));
        assertThrows(ValidacionException.class, () -> new ConsultarAs400(as400).ejecutar("cliente", null, llamador(), LOG));
    }
}
