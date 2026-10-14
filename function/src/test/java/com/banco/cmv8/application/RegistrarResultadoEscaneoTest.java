package com.banco.cmv8.application;

import com.banco.cmv8.domain.MensajeIngesta;
import com.banco.cmv8.domain.ResultadoEscaneo;
import com.banco.cmv8.domain.port.AlmacenAdjuntos;
import com.banco.cmv8.domain.port.ColaIngesta;
import com.banco.cmv8.domain.port.RepositorioSolicitudes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class RegistrarResultadoEscaneoTest {

    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String RUTA = ID + "/doc.pdf";
    private static final Logger LOG = Logger.getAnonymousLogger();

    @Mock
    RepositorioSolicitudes repositorio;
    @Mock
    AlmacenAdjuntos almacen;
    @Mock
    ColaIngesta cola;

    private RegistrarResultadoEscaneo casoDeUso() {
        return new RegistrarResultadoEscaneo(repositorio, almacen);
    }

    private static RegistrarResultadoEscaneo.EventoEscaneo evento(ResultadoEscaneo r, String ruta) {
        return new RegistrarResultadoEscaneo.EventoEscaneo(ID, ruta, r, "detalle", "escaneo");
    }

    @Test
    void limpioEncola() {
        casoDeUso().ejecutar(evento(ResultadoEscaneo.LIMPIO, RUTA), cola, LOG);

        verify(cola).encolar(new MensajeIngesta(ID, RUTA, "escaneo"));
        verifyNoInteractions(repositorio, almacen);
    }

    @Test
    void maliciosoRechazaYBorra() {
        casoDeUso().ejecutar(evento(ResultadoEscaneo.MALICIOSO, RUTA), cola, LOG);

        verify(repositorio).marcarRechazado(eq(ID), argThat(m -> m.contains("Malware")));
        verify(almacen).borrar(RUTA);
        verifyNoInteractions(cola);
    }

    @Test
    void desconocidoSeIgnora() {
        casoDeUso().ejecutar(evento(ResultadoEscaneo.DESCONOCIDO, RUTA), cola, LOG);

        verifyNoInteractions(repositorio, almacen, cola);
    }

    @Test
    void rutaAjenaSeIgnora() {
        casoDeUso().ejecutar(evento(ResultadoEscaneo.MALICIOSO, UUID.randomUUID() + "/x.pdf"), cola, LOG);

        verifyNoInteractions(repositorio, almacen, cola);
    }

    @Test
    void textoDeDefenderSeInterpreta() {
        assertEquals(ResultadoEscaneo.LIMPIO, ResultadoEscaneo.desdeTexto("No threats found"));
        assertEquals(ResultadoEscaneo.MALICIOSO, ResultadoEscaneo.desdeTexto("Malicious"));
        assertEquals(ResultadoEscaneo.DESCONOCIDO, ResultadoEscaneo.desdeTexto("Error"));
        assertEquals(ResultadoEscaneo.DESCONOCIDO, ResultadoEscaneo.desdeTexto(null));
    }
}
