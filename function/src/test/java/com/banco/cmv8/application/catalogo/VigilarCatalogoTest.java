package com.banco.cmv8.application.catalogo;

import com.banco.cmv8.domain.catalogo.Conflicto;
import com.banco.cmv8.domain.catalogo.TipoDocumento;
import com.banco.cmv8.domain.port.ArchivoDocumental;
import com.banco.cmv8.domain.port.CatalogoTipos;
import com.banco.cmv8.domain.port.ResolutorAs400;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.banco.cmv8.Datos.CARPETA;
import static com.banco.cmv8.Datos.LOG;
import static com.banco.cmv8.Datos.RELOJ;
import static com.banco.cmv8.Datos.TIPO_CMIS;
import static com.banco.cmv8.Datos.tipoCm;
import static com.banco.cmv8.Datos.tipoLocal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VigilarCatalogoTest {

    @Mock
    private CatalogoTipos catalogoTipos;
    @Mock
    private ArchivoDocumental archivo;
    @Mock
    private ResolutorAs400 as400;
    @Mock
    private Conflictos conflictos;

    private VigilarCatalogo servicio() {
        return new VigilarCatalogo(new CatalogoEnCache(catalogoTipos, Duration.ofHours(1), RELOJ), archivo, as400, conflictos);
    }

    private static TipoDocumento sinUso(UUID id) {
        return new TipoDocumento(id, "X", "X", TipoDocumento.OrigenTipo.DESCUBIERTO, "clb:SinUso", "X", true, false,
                "/X", false, null, null, null, false, true, false, List.of());
    }

    @Test
    void registraLosConflictosDeLosTiposEnUsoConItemType() {
        UUID tipoId = UUID.randomUUID();
        when(catalogoTipos.listar()).thenReturn(List.of(tipoCm(tipoId), tipoLocal(UUID.randomUUID(), null),
                sinUso(UUID.randomUUID())));
        when(archivo.existeCarpeta(CARPETA)).thenReturn(true);
        when(archivo.definicionTipo(TIPO_CMIS)).thenReturn(Optional.empty());

        assertEquals(1, servicio().ejecutar(LOG));

        ArgumentCaptor<Conflicto> conflicto = ArgumentCaptor.forClass(Conflicto.class);
        verify(conflictos).registrar(conflicto.capture(), any());
        assertEquals(Conflicto.Clase.TIPO_AUSENTE, conflicto.getValue().clase());
        assertEquals(tipoId, conflicto.getValue().tipoDocumentoId());
        verify(archivo, never()).definicionTipo("clb:SinUso");
        verify(conflictos, never()).registrarConDetenido(any(), any());
    }

    @Test
    void unaFallaEnUnTipoNoFrenaElResto() {
        TipoDocumento roto = new TipoDocumento(UUID.randomUUID(), "R", "R", TipoDocumento.OrigenTipo.DESCUBIERTO, "clb:Roto",
                "R", true, true, "/Rota", false, null, null, null, false, true, false, List.of());
        when(catalogoTipos.listar()).thenReturn(List.of(roto, tipoCm(UUID.randomUUID())));
        when(archivo.existeCarpeta("/Rota")).thenThrow(new RuntimeException("CMIS caído"));
        when(archivo.existeCarpeta(CARPETA)).thenReturn(false);
        when(archivo.definicionTipo(TIPO_CMIS)).thenReturn(Optional.of(com.banco.cmv8.Datos.definicion()));

        assertEquals(1, servicio().ejecutar(LOG));

        ArgumentCaptor<Conflicto> conflicto = ArgumentCaptor.forClass(Conflicto.class);
        verify(conflictos).registrar(conflicto.capture(), any());
        assertEquals(Conflicto.Clase.CARPETA_INEXISTENTE, conflicto.getValue().clase());
    }

    @Test
    void cadaCorridaReleeElCatalogo() {
        VigilarCatalogo vigilar = servicio();
        vigilar.ejecutar(LOG);
        vigilar.ejecutar(LOG);
        verify(catalogoTipos, times(2)).listar();
    }
}
