package com.banco.cmv8.application;

import com.banco.cmv8.domain.ContextoLlamador;
import com.banco.cmv8.domain.EstadoSolicitud;
import com.banco.cmv8.domain.PoliticaSas;
import com.banco.cmv8.domain.SasEmitida;
import com.banco.cmv8.domain.SolicitudIngesta;
import com.banco.cmv8.domain.TokenUsuario;
import com.banco.cmv8.domain.port.AlmacenAdjuntos;
import com.banco.cmv8.domain.port.AutorizadorSolicitud;
import com.banco.cmv8.domain.port.EmisorSas;
import com.banco.cmv8.domain.port.IntercambiadorTokenUsuario;
import com.banco.cmv8.domain.port.RepositorioSolicitudes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Ramas de la SAS ligada al usuario (S1b) en los casos de uso del broker. */
@ExtendWith(MockitoExtension.class)
class EmitirSasSubidaTest {

    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Logger LOG = Logger.getAnonymousLogger();
    private static final OffsetDateTime EXPIRA_SAS = OffsetDateTime.of(2026, 10, 14, 12, 15, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime EXPIRA_TOKEN = EXPIRA_SAS.plusMinutes(60);
    private static final ContextoLlamador USUARIO = new ContextoLlamador(Optional.of("oid-1"), Optional.of("tid-1"),
            Optional.of("bearer-broker"), Optional.empty());

    @Mock
    AutorizadorSolicitud autorizador;
    @Mock
    RepositorioSolicitudes repositorio;
    @Mock
    EmisorSas emisor;
    @Mock
    AlmacenAdjuntos almacen;

    /** Fake del puerto: registra a quién se le pidió el token y devuelve uno fijo, o falla. */
    static final class IntercambiadorFalso implements IntercambiadorTokenUsuario {
        final List<ContextoLlamador> llamadas = new ArrayList<>();
        RuntimeException falla;

        @Override
        public TokenUsuario tokenStorage(ContextoLlamador llamador) {
            llamadas.add(llamador);
            if (falla != null) {
                throw falla;
            }
            return new TokenUsuario("token-storage-" + llamador.objectId().orElseThrow(), EXPIRA_TOKEN);
        }
    }

    private final IntercambiadorFalso intercambiador = new IntercambiadorFalso();

    private EmitirSasSubida subida(VinculoUsuario vinculo) {
        return new EmitirSasSubida(new PoliticaSas(), autorizador, repositorio, emisor, Duration.ofMinutes(15),
                EmitirSasSubida.BLOQUE_BYTES, vinculo);
    }

    private static EmitirSasSubida.Peticion peticion() {
        return new EmitirSasSubida.Peticion(ID, "s1b.bin", 1_048_576L, "application/octet-stream");
    }

    private static SolicitudIngesta solicitud(EstadoSolicitud estado, String ruta) {
        return new SolicitudIngesta(ID, "s1b.bin", 1L, null, estado, ruta, null, null, null, null, null, null, null);
    }

    private void solicitudCreadaAutorizada() {
        when(autorizador.puedeEscribir(ID, USUARIO)).thenReturn(true);
        when(repositorio.obtener(ID)).thenReturn(Optional.of(solicitud(EstadoSolicitud.CREADA, null)));
        when(emisor.emitirSubida(anyString(), any(), any()))
                .thenReturn(new SasEmitida("https://cuenta/c/ruta?sig=x", EXPIRA_SAS, "2026-02-06"));
    }

    @Test
    void ligadaDevuelveTokenDeStorageDelMismoUsuario() {
        solicitudCreadaAutorizada();

        EmitirSasSubida.Resultado r = subida(VinculoUsuario.activo(intercambiador)).ejecutar(peticion(), USUARIO, LOG);

        assertEquals("token-storage-oid-1", r.tokenStorage());
        assertEquals(EXPIRA_TOKEN, r.tokenStorageExpiraUtc());
        assertEquals(List.of(USUARIO), intercambiador.llamadas);
        assertFalse(r.toString().contains("token-storage"), "el toString no debe exponer el token");
    }

    @Test
    void noLigadaNoPideTokenYDejaLosCamposVacios() {
        solicitudCreadaAutorizada();

        EmitirSasSubida.Resultado r = subida(VinculoUsuario.desactivado()).ejecutar(peticion(), USUARIO, LOG);

        assertNull(r.tokenStorage());
        assertNull(r.tokenStorageExpiraUtc());
        assertTrue(intercambiador.llamadas.isEmpty());
    }

    @Test
    void ligadaSinOidEs401YNoConsultaDataverse() {
        ContextoLlamador sinOid = new ContextoLlamador(Optional.empty(), Optional.empty(), Optional.of("b"),
                Optional.empty());

        NoAutenticadoException e = assertThrows(NoAutenticadoException.class,
                () -> subida(VinculoUsuario.activo(intercambiador)).ejecutar(peticion(), sinOid, LOG));

        assertTrue(e.getMessage().contains("oid"));
        verifyNoInteractions(autorizador, repositorio, emisor);
    }

    @Test
    void ligadaSinBearerEs401() {
        ContextoLlamador sinBearer = new ContextoLlamador(Optional.of("oid-1"), Optional.empty(), Optional.empty(),
                Optional.empty());

        assertThrows(NoAutenticadoException.class,
                () -> subida(VinculoUsuario.activo(intercambiador)).ejecutar(peticion(), sinBearer, LOG));
        verifyNoInteractions(autorizador, repositorio, emisor);
    }

    @Test
    void noLigadaSinOidSigueComoAntes() {
        ContextoLlamador anonimo = ContextoLlamador.anonimo();
        when(autorizador.puedeEscribir(ID, anonimo)).thenReturn(false);

        assertThrows(AccesoDenegadoException.class,
                () -> subida(VinculoUsuario.desactivado()).ejecutar(peticion(), anonimo, LOG));
    }

    @Test
    void ligadaSinPermisoEnDataverseNoHaceObo() {
        when(autorizador.puedeEscribir(ID, USUARIO)).thenReturn(false);

        assertThrows(AccesoDenegadoException.class,
                () -> subida(VinculoUsuario.activo(intercambiador)).ejecutar(peticion(), USUARIO, LOG));
        assertTrue(intercambiador.llamadas.isEmpty());
    }

    @Test
    void falloDelOboNoRegistraRutaNiEmiteSas() {
        when(autorizador.puedeEscribir(ID, USUARIO)).thenReturn(true);
        when(repositorio.obtener(ID)).thenReturn(Optional.of(solicitud(EstadoSolicitud.CREADA, null)));
        intercambiador.falla = new IntercambioTokenException("OBO", "invalid_grant", null);

        IntercambioTokenException e = assertThrows(IntercambioTokenException.class,
                () -> subida(VinculoUsuario.activo(intercambiador)).ejecutar(peticion(), USUARIO, LOG));

        assertEquals("invalid_grant", e.codigoEntra());
        verify(repositorio, never()).registrarRutaBlob(any(), anyString());
        verifyNoInteractions(emisor);
    }

    @Test
    void lecturaLigadaDevuelveToken() {
        String ruta = ID + "/s1b.bin";
        when(autorizador.puedeLeer(ID, USUARIO)).thenReturn(true);
        when(repositorio.obtener(ID)).thenReturn(Optional.of(solicitud(EstadoSolicitud.SUBIDO, ruta)));
        when(almacen.existe(ruta)).thenReturn(true);
        when(emisor.emitirLectura(eq(ruta), any(), any()))
                .thenReturn(new SasEmitida("https://cuenta/c/ruta?sig=y", EXPIRA_SAS, "2026-02-06"));

        EmitirSasLectura.Resultado r = new EmitirSasLectura(autorizador, repositorio, almacen, emisor,
                Duration.ofMinutes(5), VinculoUsuario.activo(intercambiador)).ejecutar(ID, USUARIO, LOG);

        assertEquals("token-storage-oid-1", r.tokenStorage());
        assertEquals(EXPIRA_TOKEN, r.tokenStorageExpiraUtc());
    }

    @Test
    void lecturaLigadaSinOidEs401() {
        assertThrows(NoAutenticadoException.class, () -> new EmitirSasLectura(autorizador, repositorio, almacen,
                emisor, Duration.ofMinutes(5), VinculoUsuario.activo(intercambiador))
                .ejecutar(ID, ContextoLlamador.anonimo(), LOG));
        verifyNoInteractions(autorizador);
    }
}
