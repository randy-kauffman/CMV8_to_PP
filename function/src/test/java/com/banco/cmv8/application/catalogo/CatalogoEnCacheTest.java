package com.banco.cmv8.application.catalogo;

import com.banco.cmv8.domain.catalogo.TipoDocumento;
import com.banco.cmv8.domain.port.CatalogoTipos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.banco.cmv8.Datos.AHORA;
import static com.banco.cmv8.Datos.tipoCm;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatalogoEnCacheTest {

    /** Reloj que avanza a mano: sin sleeps. */
    private static final class RelojManual extends Clock {
        private Instant ahora = AHORA;

        void avanzar(Duration d) {
            ahora = ahora.plus(d);
        }

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
            return ahora;
        }
    }

    private static final Duration VIGENCIA = Duration.ofMinutes(5);

    @Mock
    private CatalogoTipos catalogo;

    private final RelojManual reloj = new RelojManual();
    private final UUID id = UUID.randomUUID();

    private CatalogoEnCache cache() {
        return new CatalogoEnCache(catalogo, VIGENCIA, reloj);
    }

    @Test
    void dentroDeLaVigenciaNoVuelveALeerDataverse() {
        when(catalogo.listar()).thenReturn(List.of(tipoCm(id)));
        CatalogoEnCache cache = cache();
        assertTrue(cache.tipo(id).isPresent());
        reloj.avanzar(VIGENCIA);
        assertTrue(cache.tipo(id).isPresent());
        assertEquals(1, cache.tipos().size());
        verify(catalogo, times(1)).listar();
    }

    @Test
    void vencidaLaVigenciaRecarga() {
        when(catalogo.listar()).thenReturn(List.of(tipoCm(id)), List.of());
        CatalogoEnCache cache = cache();
        assertTrue(cache.tipo(id).isPresent());
        reloj.avanzar(VIGENCIA.plusSeconds(1));
        assertTrue(cache.tipo(id).isEmpty());
        verify(catalogo, times(2)).listar();
    }

    @Test
    void invalidarFuerzaLaRecarga() {
        when(catalogo.listar()).thenReturn(List.of(tipoCm(id)));
        CatalogoEnCache cache = cache();
        cache.tipos();
        cache.invalidar();
        cache.tipos();
        verify(catalogo, times(2)).listar();
    }

    @Test
    void idNuloNoConsultaElCatalogo() {
        assertTrue(cache().tipo(null).isEmpty());
        verifyNoInteractions(catalogo);
    }

    @Test
    void tiposEnUsoFiltra() {
        UUID otro = UUID.randomUUID();
        TipoDocumento sinUso = new TipoDocumento(otro, "X", "X", TipoDocumento.OrigenTipo.LOCAL, null, null, false, false,
                null, false, null, null, null, false, true, false, List.of());
        when(catalogo.listar()).thenReturn(List.of(tipoCm(id), sinUso));
        List<TipoDocumento> enUso = cache().tiposEnUso();
        assertEquals(1, enUso.size());
        assertEquals(id, enUso.get(0).id());
    }
}
