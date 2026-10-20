package com.banco.cmv8.application;

import com.banco.cmv8.domain.Adjunto;
import com.banco.cmv8.domain.ClaveAdjunto;
import com.banco.cmv8.domain.EstadoIngesta;
import com.banco.cmv8.domain.MensajeIngesta;
import com.banco.cmv8.domain.RegistroIngesta;
import com.banco.cmv8.domain.ResultadoEscaneo;
import com.banco.cmv8.domain.port.AlmacenAdjuntos;
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

import static com.banco.cmv8.Datos.AHORA;
import static com.banco.cmv8.Datos.INGESTA;
import static com.banco.cmv8.Datos.LOG;
import static com.banco.cmv8.Datos.RELOJ;
import static com.banco.cmv8.Datos.TABLA;
import static com.banco.cmv8.Datos.registro;
import static com.banco.cmv8.Datos.ruta;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegistrarResultadoEscaneoTest {

    @Mock
    private RegistroIngestas registros;
    @Mock
    private RepositorioAdjuntos repositorio;
    @Mock
    private AlmacenAdjuntos almacen;
    @Mock
    private ColaIngesta cola;

    private final ClaveAdjunto clave = com.banco.cmv8.Datos.clave();
    private final String ruta = ruta(clave);

    private RegistrarResultadoEscaneo servicio() {
        return new RegistrarResultadoEscaneo(registros, repositorio, almacen, INGESTA, RELOJ);
    }

    private RegistrarResultadoEscaneo.EventoEscaneo evento(ResultadoEscaneo resultado, String detalle) {
        return new RegistrarResultadoEscaneo.EventoEscaneo(clave, ruta, resultado, detalle, "escaneo");
    }

    private void registroEn(EstadoIngesta estado) {
        when(registros.obtener(clave)).thenReturn(Optional.of(registro(clave, estado, UUID.randomUUID(), INGESTA, Map.of())));
    }

    @Test
    void rutaAjenaSeIgnora() {
        servicio().ejecutar(new RegistrarResultadoEscaneo.EventoEscaneo(clave, TABLA + "/" + UUID.randomUUID() + "/x.pdf",
                ResultadoEscaneo.LIMPIO, null, "escaneo"), cola, LOG);
        servicio().ejecutar(new RegistrarResultadoEscaneo.EventoEscaneo(null, ruta, ResultadoEscaneo.LIMPIO, null, "escaneo"),
                cola, LOG);
        verifyNoInteractions(registros, repositorio, almacen, cola);
    }

    @Test
    void blobSinRegistroOperativoSeIgnora() {
        when(registros.obtener(clave)).thenReturn(Optional.empty());
        servicio().ejecutar(evento(ResultadoEscaneo.LIMPIO, null), cola, LOG);
        verifyNoInteractions(repositorio, almacen, cola);
    }

    @Test
    void limpioSobreCreadaMarcaSubidoEnLaTablaYEncolaSinTocarDataverse() {
        registroEn(EstadoIngesta.CREADA);
        servicio().ejecutar(evento(ResultadoEscaneo.LIMPIO, null), cola, LOG);

        ArgumentCaptor<RegistroIngesta> guardado = ArgumentCaptor.forClass(RegistroIngesta.class);
        verify(registros).guardar(guardado.capture());
        assertEquals(EstadoIngesta.SUBIDO, guardado.getValue().estado());
        assertEquals(AHORA, guardado.getValue().subidoEn());
        verify(cola).encolar(new MensajeIngesta(clave, INGESTA, ruta, "escaneo"));
        verifyNoInteractions(repositorio, almacen);
    }

    @Test
    void limpioSobreSubidoSoloEncola() {
        registroEn(EstadoIngesta.SUBIDO);
        servicio().ejecutar(evento(ResultadoEscaneo.LIMPIO, null), cola, LOG);
        verify(registros, never()).guardar(any());
        verify(cola).encolar(new MensajeIngesta(clave, INGESTA, ruta, "escaneo"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void maliciosoRechazaYBorra() {
        registroEn(EstadoIngesta.CREADA);
        servicio().ejecutar(evento(ResultadoEscaneo.MALICIOSO, "Trojan:Win32"), cola, LOG);

        ArgumentCaptor<Map<String, Object>> cambios = ArgumentCaptor.forClass(Map.class);
        verify(repositorio).actualizar(org.mockito.ArgumentMatchers.eq(clave), cambios.capture());
        assertEquals(EstadoIngesta.RECHAZADO, cambios.getValue().get(Adjunto.COL_ESTADO));
        assertEquals("Malware detectado por el antivirus (Trojan:Win32)", cambios.getValue().get(Adjunto.COL_MENSAJE));
        ArgumentCaptor<RegistroIngesta> guardado = ArgumentCaptor.forClass(RegistroIngesta.class);
        verify(registros).guardar(guardado.capture());
        assertEquals(EstadoIngesta.RECHAZADO, guardado.getValue().estado());
        verify(almacen).borrar(INGESTA, ruta);
        verifyNoInteractions(cola);
    }

    @Test
    void desconocidoNoHaceNada() {
        registroEn(EstadoIngesta.CREADA);
        servicio().ejecutar(evento(ResultadoEscaneo.DESCONOCIDO, "timeout"), cola, LOG);
        verifyNoInteractions(repositorio, almacen, cola);
        verify(registros, never()).guardar(any());
    }
}
