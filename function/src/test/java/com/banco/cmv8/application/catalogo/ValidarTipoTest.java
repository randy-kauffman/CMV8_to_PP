package com.banco.cmv8.application.catalogo;

import com.banco.cmv8.application.AccesoDenegadoException;
import com.banco.cmv8.application.SolicitudNoEncontradaException;
import com.banco.cmv8.domain.ContextoLlamador;
import com.banco.cmv8.domain.catalogo.TipoDocumento;
import com.banco.cmv8.domain.port.ArchivoDocumental;
import com.banco.cmv8.domain.port.CatalogoTipos;
import com.banco.cmv8.domain.port.ResolutorAs400;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.banco.cmv8.Datos.CARPETA;
import static com.banco.cmv8.Datos.LOG;
import static com.banco.cmv8.Datos.RELOJ;
import static com.banco.cmv8.Datos.TIPO_CMIS;
import static com.banco.cmv8.Datos.definicion;
import static com.banco.cmv8.Datos.llamador;
import static com.banco.cmv8.Datos.tipoCm;
import static com.banco.cmv8.Datos.tipoLocal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ValidarTipoTest {

    @Mock
    private CatalogoTipos catalogoTipos;
    @Mock
    private ArchivoDocumental archivo;
    @Mock
    private ResolutorAs400 as400;

    private final ContextoLlamador llamador = llamador();

    private ValidarTipo servicio() {
        return new ValidarTipo(catalogoTipos, new CatalogoEnCache(catalogoTipos, Duration.ofMinutes(5), RELOJ), archivo, as400);
    }

    @Test
    void sinRolEsAccesoDenegado() {
        when(catalogoTipos.puedeAdministrar(llamador)).thenReturn(false);
        assertThrows(AccesoDenegadoException.class, () -> servicio().ejecutar(Optional.empty(), llamador, LOG));
        verifyNoInteractions(archivo);
    }

    @Test
    void tipoInexistenteEs404() {
        when(catalogoTipos.puedeAdministrar(llamador)).thenReturn(true);
        when(catalogoTipos.listar()).thenReturn(List.of());
        assertThrows(SolicitudNoEncontradaException.class,
                () -> servicio().ejecutar(Optional.of(UUID.randomUUID()), llamador, LOG));
    }

    @Test
    void sinIdValidaLosTiposEnUsoConItemTypeEInformaCriticos() {
        when(catalogoTipos.puedeAdministrar(llamador)).thenReturn(true);
        when(catalogoTipos.listar()).thenReturn(List.of(tipoCm(UUID.randomUUID()), tipoLocal(UUID.randomUUID(), 10)));
        when(archivo.existeCarpeta(CARPETA)).thenReturn(false);
        when(archivo.definicionTipo(TIPO_CMIS)).thenReturn(Optional.of(definicion()));
        when(as400.consultasDeclaradas()).thenReturn(Set.of());

        var informe = servicio().ejecutar(Optional.empty(), llamador, LOG);

        assertEquals(1, informe.criticos());
        assertEquals(0, informe.avisos());
        verify(archivo).definicionTipo(TIPO_CMIS);
    }

    @Test
    void tipoSinCarpetaNoConsultaLaCarpeta() {
        UUID id = UUID.randomUUID();
        TipoDocumento sinCarpeta = new TipoDocumento(id, "PT95", "PT95", TipoDocumento.OrigenTipo.DESCUBIERTO, TIPO_CMIS,
                "Credito", true, true, " ", false, null, null, null, false, true, false, List.of());
        when(catalogoTipos.puedeAdministrar(llamador)).thenReturn(true);
        when(catalogoTipos.listar()).thenReturn(List.of(sinCarpeta));
        when(archivo.definicionTipo(TIPO_CMIS)).thenReturn(Optional.of(definicion()));

        var informe = servicio().ejecutar(Optional.of(id), llamador, LOG);

        assertTrue(informe.hallazgos().isEmpty());
        verify(archivo, never()).existeCarpeta(any());
    }
}
