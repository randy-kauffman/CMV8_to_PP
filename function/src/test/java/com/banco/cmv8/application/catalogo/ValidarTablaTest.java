package com.banco.cmv8.application.catalogo;

import com.banco.cmv8.application.NoAutenticadoException;
import com.banco.cmv8.application.SolicitudNoEncontradaException;
import com.banco.cmv8.domain.ContextoLlamador;
import com.banco.cmv8.domain.ValidacionException;
import com.banco.cmv8.domain.catalogo.PropiedadCm;
import com.banco.cmv8.domain.catalogo.TipoDocumento;
import com.banco.cmv8.domain.port.CatalogoTipos;
import com.banco.cmv8.domain.port.RepositorioAdjuntos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.banco.cmv8.Datos.LOG;
import static com.banco.cmv8.Datos.RELOJ;
import static com.banco.cmv8.Datos.TABLA;
import static com.banco.cmv8.Datos.columna;
import static com.banco.cmv8.Datos.llamador;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ValidarTablaTest {

    private static final Set<String> RESERVADAS = Set.of("cred_estadoingesta", "cred_tipodocumento", "cred_rutablob",
            "cred_cmisobjectid", "cred_mensajeerror", "cred_nombrearchivo", "cred_tamanobytes", "cred_contenttype");

    @Mock
    private RepositorioAdjuntos repositorio;
    @Mock
    private CatalogoTipos catalogoTipos;

    private final ContextoLlamador llamador = llamador();

    private ValidarTabla servicio() {
        return new ValidarTabla(repositorio, new CatalogoEnCache(catalogoTipos, Duration.ofMinutes(5), RELOJ));
    }

    private static TipoDocumento tipo(UUID id, boolean revisado, PropiedadCm... props) {
        return new TipoDocumento(id, "PT95", "PT95 - Crédito", TipoDocumento.OrigenTipo.DESCUBIERTO, "clb:Credito",
                "Credito", true, true, "/C", false, null, null, null, false, revisado, false, List.of(props));
    }

    @Test
    void sinOidEsNoAutenticado() {
        assertThrows(NoAutenticadoException.class,
                () -> servicio().ejecutar(TABLA, Optional.empty(), com.banco.cmv8.Datos.sinOid(), LOG));
        verifyNoInteractions(repositorio);
    }

    @Test
    void nombreDeTablaInvalidoEsValidacion() {
        assertThrows(ValidacionException.class, () -> servicio().ejecutar("Cred;drop", Optional.empty(), llamador, LOG));
        verifyNoInteractions(repositorio);
    }

    @Test
    void tablaSinColumnasVisiblesEs404() {
        when(repositorio.columnas(TABLA)).thenReturn(Set.of());
        assertThrows(SolicitudNoEncontradaException.class, () -> servicio().ejecutar(TABLA, Optional.empty(), llamador, LOG));
    }

    @Test
    void tablaCompletaEstaOk() {
        Set<String> columnas = new HashSet<>(RESERVADAS);
        columnas.add("cred_numerocredito");
        when(repositorio.columnas(TABLA)).thenReturn(columnas);
        when(catalogoTipos.listar()).thenReturn(List.of(tipo(UUID.randomUUID(), true, columna("clb:Numero", "NumeroCredito"))));

        var informe = servicio().ejecutar(TABLA, Optional.empty(), llamador, LOG);

        assertTrue(informe.ok(), informe.faltantes().toString());
        assertEquals("cred_", informe.prefijo());
        assertTrue(informe.avisos().isEmpty());
    }

    @Test
    void informaReservadasYColumnasDeOrigenFaltantesYTiposNoRevisados() {
        Set<String> columnas = new HashSet<>(RESERVADAS);
        columnas.remove("cred_rutablob");
        when(repositorio.columnas(TABLA)).thenReturn(columnas);
        when(catalogoTipos.listar()).thenReturn(List.of(tipo(UUID.randomUUID(), false, columna("clb:Numero", "numerocredito"))));

        var informe = servicio().ejecutar(TABLA, Optional.empty(), llamador, LOG);

        assertFalse(informe.ok());
        assertEquals(2, informe.faltantes().size());
        assertTrue(informe.faltantes().get(0).startsWith("cred_rutablob"));
        assertTrue(informe.faltantes().get(1).startsWith("cred_numerocredito"));
        assertEquals(1, informe.avisos().size());
    }

    @Test
    void conTipoIndicadoSoloValidaEseTipo() {
        UUID elegido = UUID.randomUUID();
        when(repositorio.columnas(TABLA)).thenReturn(RESERVADAS);
        when(catalogoTipos.listar()).thenReturn(List.of(tipo(elegido, true),
                tipo(UUID.randomUUID(), true, columna("clb:Numero", "numerocredito"))));

        var informe = servicio().ejecutar(TABLA, Optional.of(elegido), llamador, LOG);

        assertTrue(informe.ok(), informe.faltantes().toString());
    }
}
