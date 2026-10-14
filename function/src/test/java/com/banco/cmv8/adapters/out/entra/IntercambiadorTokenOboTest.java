package com.banco.cmv8.adapters.out.entra;

import com.banco.cmv8.application.NoAutenticadoException;
import com.banco.cmv8.domain.ContextoLlamador;
import com.banco.cmv8.domain.TokenUsuario;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IntercambiadorTokenOboTest {

    private static final Instant AHORA = Instant.parse("2026-10-14T12:00:00Z");

    /** Reloj que se puede adelantar. */
    static final class RelojMovil extends Clock {
        Instant instante = AHORA;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instante;
        }
    }

    private final RelojMovil reloj = new RelojMovil();
    private final List<String> assertions = new ArrayList<>();
    private final IntercambiadorTokenObo obo = new IntercambiadorTokenObo(a -> {
        assertions.add(a);
        return new TokenUsuario("t" + assertions.size(), OffsetDateTime.ofInstant(reloj.instante, ZoneOffset.UTC)
                .plusMinutes(60));
    }, Duration.ofMinutes(20), reloj);

    private static ContextoLlamador usuario(String oid, String bearer) {
        return new ContextoLlamador(Optional.of(oid), Optional.empty(), Optional.of(bearer), Optional.empty());
    }

    @Test
    void reutilizaMientrasCubraLaVigenciaMinima() {
        TokenUsuario primero = obo.tokenStorage(usuario("o1", "a1"));
        reloj.instante = AHORA.plusSeconds(30 * 60);
        assertSame(primero, obo.tokenStorage(usuario("o1", "a2")));
        assertEquals(List.of("a1"), assertions);
    }

    @Test
    void renuevaCuandoQuedaMenosQueLaVigenciaMinima() {
        obo.tokenStorage(usuario("o1", "a1"));
        reloj.instante = AHORA.plusSeconds(45 * 60);
        assertEquals("t2", obo.tokenStorage(usuario("o1", "a2")).valor());
        assertEquals(List.of("a1", "a2"), assertions);
    }

    @Test
    void cacheSeparadaPorUsuario() {
        obo.tokenStorage(usuario("o1", "a1"));
        assertEquals("t2", obo.tokenStorage(usuario("o2", "b1")).valor());
    }

    @Test
    void sinBearerEs401() {
        assertThrows(NoAutenticadoException.class, () -> obo.tokenStorage(new ContextoLlamador(Optional.of("o1"),
                Optional.empty(), Optional.empty(), Optional.empty())));
    }
}
