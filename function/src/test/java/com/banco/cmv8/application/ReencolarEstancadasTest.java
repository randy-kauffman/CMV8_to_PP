package com.banco.cmv8.application;

import com.banco.cmv8.domain.ClaveAdjunto;
import com.banco.cmv8.domain.EstadoIngesta;
import com.banco.cmv8.domain.MensajeIngesta;
import com.banco.cmv8.domain.RegistroIngesta;
import com.banco.cmv8.domain.port.AlmacenAdjuntos;
import com.banco.cmv8.domain.port.ColaIngesta;
import com.banco.cmv8.domain.port.RegistroIngestas;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.banco.cmv8.Datos.INGESTA;
import static com.banco.cmv8.Datos.LOG;
import static com.banco.cmv8.Datos.registro;
import static com.banco.cmv8.Datos.ruta;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReencolarEstancadasTest {

    private static final Duration ANTIGUEDAD = Duration.ofMinutes(30);

    @Mock
    private RegistroIngestas registros;
    @Mock
    private AlmacenAdjuntos almacen;
    @Mock
    private ColaIngesta cola;

    private RegistroIngesta estancado(EstadoIngesta estado, boolean conBlob) {
        ClaveAdjunto clave = com.banco.cmv8.Datos.clave();
        when(almacen.existe(INGESTA, ruta(clave))).thenReturn(conBlob);
        return registro(clave, estado, UUID.randomUUID(), INGESTA, Map.of());
    }

    @Test
    void reencolaSoloLosQueTienenBlobYLeeLaTablaNoDataverse() {
        RegistroIngesta subido = estancado(EstadoIngesta.SUBIDO, true);
        RegistroIngesta procesando = estancado(EstadoIngesta.PROCESANDO, true);
        RegistroIngesta sasSinSubir = estancado(EstadoIngesta.CREADA, false);
        RegistroIngesta sinBlob = estancado(EstadoIngesta.PROCESANDO, false);
        when(registros.listarEstancados(Set.of(EstadoIngesta.CREADA, EstadoIngesta.SUBIDO, EstadoIngesta.PROCESANDO),
                ANTIGUEDAD, 100)).thenReturn(List.of(subido, procesando, sasSinSubir, sinBlob));

        int encolados = new ReencolarEstancadas(registros, almacen, ANTIGUEDAD).ejecutar(cola, LOG);

        assertEquals(2, encolados);
        verify(cola).encolar(new MensajeIngesta(subido.clave(), INGESTA, subido.ruta(), "watchdog"));
        verify(cola).encolar(new MensajeIngesta(procesando.clave(), INGESTA, procesando.ruta(), "watchdog"));
        verify(cola, times(2)).encolar(any());
    }

    @Test
    void sinEstancadosNoEncolaNada() {
        assertEquals(0, new ReencolarEstancadas(registros, almacen, ANTIGUEDAD).ejecutar(cola, LOG));
        verify(cola, times(0)).encolar(any());
    }
}
