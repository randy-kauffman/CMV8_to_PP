package com.banco.cmv8.application;

import com.banco.cmv8.application.catalogo.CatalogoEnCache;
import com.banco.cmv8.domain.ClaveAdjunto;
import com.banco.cmv8.domain.ContextoLlamador;
import com.banco.cmv8.domain.EstadoIngesta;
import com.banco.cmv8.domain.MensajeIngesta;
import com.banco.cmv8.domain.RegistroIngesta;
import com.banco.cmv8.domain.port.CatalogoTipos;
import com.banco.cmv8.domain.port.ColaIngesta;
import com.banco.cmv8.domain.port.IntercambiadorTokenUsuario;
import com.banco.cmv8.domain.port.RegistroIngestas;
import com.banco.cmv8.domain.port.RepositorioAdjuntos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.banco.cmv8.Datos.ALMACENADOS;
import static com.banco.cmv8.Datos.LOG;
import static com.banco.cmv8.Datos.RELOJ;
import static com.banco.cmv8.Datos.TABLA;
import static com.banco.cmv8.Datos.adjunto;
import static com.banco.cmv8.Datos.llamador;
import static com.banco.cmv8.Datos.registro;
import static com.banco.cmv8.Datos.ruta;
import static com.banco.cmv8.Datos.tipoCm;
import static com.banco.cmv8.Datos.tipoLocal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArchivarAlmacenadoTest {

    @Mock
    private RepositorioAdjuntos repositorio;
    @Mock
    private RegistroIngestas registros;
    @Mock
    private CatalogoTipos catalogoTipos;
    @Mock
    private ColaIngesta cola;
    @Mock
    private IntercambiadorTokenUsuario intercambiador;

    private final ContextoLlamador llamador = llamador();
    private final ClaveAdjunto clave = com.banco.cmv8.Datos.clave();
    private final String ruta = ruta(clave);
    private final UUID tipoCmId = UUID.randomUUID();
    private final UUID tipoLocalId = UUID.randomUUID();

    private ArchivarAlmacenado servicio(VinculoUsuario vinculo) {
        return new ArchivarAlmacenado(repositorio, registros, new CatalogoEnCache(catalogoTipos, Duration.ofMinutes(5), RELOJ),
                vinculo);
    }

    private ArchivarAlmacenado servicio() {
        return servicio(VinculoUsuario.desactivado());
    }

    private void adjuntoEn(EstadoIngesta estado, UUID tipo, String rutaBlob) {
        when(repositorio.obtenerSuplantando(clave, llamador)).thenReturn(Optional.of(adjunto(clave, estado, tipo, rutaBlob, Map.of())));
    }

    @Test
    void promocionTomaElTipoActualYEncolaDesdeAlmacenados() {
        adjuntoEn(EstadoIngesta.ALMACENADO, tipoCmId, ruta);
        when(catalogoTipos.listar()).thenReturn(List.of(tipoCm(tipoCmId), tipoLocal(tipoLocalId, null)));
        when(registros.obtener(clave)).thenReturn(Optional.of(
                registro(clave, EstadoIngesta.ALMACENADO, tipoLocalId, ALMACENADOS, Map.of())));

        servicio().ejecutar(clave, llamador, cola, LOG);

        ArgumentCaptor<RegistroIngesta> guardado = ArgumentCaptor.forClass(RegistroIngesta.class);
        verify(registros).guardar(guardado.capture());
        assertEquals(tipoCmId, guardado.getValue().tipoDocumentoId());
        verify(cola).encolar(new MensajeIngesta(clave, ALMACENADOS, ruta, "archivar"));
        verify(repositorio, never()).actualizar(any(), any());
    }

    @Test
    void soloSeArchivaUnAlmacenado() {
        adjuntoEn(EstadoIngesta.OBSERVADA, tipoCmId, ruta);
        assertThrows(EstadoInvalidoException.class, () -> servicio().ejecutar(clave, llamador, cola, LOG));
        verifyNoInteractions(cola);
    }

    @Test
    void tipoLocalNoVaAContentManager() {
        adjuntoEn(EstadoIngesta.ALMACENADO, tipoLocalId, ruta);
        when(catalogoTipos.listar()).thenReturn(List.of(tipoLocal(tipoLocalId, null)));
        assertThrows(EstadoInvalidoException.class, () -> servicio().ejecutar(clave, llamador, cola, LOG));
        verifyNoInteractions(cola);
    }

    @Test
    void adjuntoInexistenteEs404() {
        when(repositorio.obtenerSuplantando(clave, llamador)).thenReturn(Optional.empty());
        assertThrows(SolicitudNoEncontradaException.class, () -> servicio().ejecutar(clave, llamador, cola, LOG));
    }

    @Test
    void rutaAjenaEs404() {
        adjuntoEn(EstadoIngesta.ALMACENADO, tipoCmId, TABLA + "/" + UUID.randomUUID() + "/x.pdf");
        when(catalogoTipos.listar()).thenReturn(List.of(tipoCm(tipoCmId)));
        when(registros.obtener(clave)).thenReturn(Optional.of(registro(clave, EstadoIngesta.ALMACENADO, tipoCmId, ALMACENADOS, Map.of())));
        assertThrows(SolicitudNoEncontradaException.class, () -> servicio().ejecutar(clave, llamador, cola, LOG));
        verifyNoInteractions(cola);
    }

    @Test
    void vinculoActivoSinOidEsNoAutenticadoSinTocarDataverse() {
        assertThrows(NoAutenticadoException.class, () -> servicio(VinculoUsuario.activo(intercambiador))
                .ejecutar(clave, com.banco.cmv8.Datos.sinOid(), cola, LOG));
        verifyNoInteractions(repositorio);
    }
}
