package com.banco.cmv8.adapters.out.cmis;

import com.banco.cmv8.domain.EstadoSolicitud;
import com.banco.cmv8.domain.SolicitudIngesta;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MapeoPropiedadesCmisTest {

    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static SolicitudIngesta solicitud() {
        return new SolicitudIngesta(ID, "a.pdf", 10L, "application/pdf", EstadoSolicitud.SUBIDO, ID + "/a.pdf",
                null, "C1", null, "Ana", "AF", "D1", null);
    }

    @Test
    void resuelveReferenciasYLiteralesYOmiteNulos() {
        MapeoPropiedadesCmis mapeo = new MapeoPropiedadesCmis(
                "{\"cm:cliente\":\"${idCliente}\",\"cm:cliente2\":\"${idCliente2}\",\"cm:origen\":\"PowerApps\","
                        + "\"cm:solicitud\":\"${solicitudId}\"}");

        Map<String, Object> p = mapeo.resolver(solicitud());

        assertEquals("C1", p.get("cm:cliente"));
        assertEquals("PowerApps", p.get("cm:origen"));
        assertEquals(ID.toString(), p.get("cm:solicitud"));
        assertEquals(3, p.size());
        assertEquals(Optional.of("cm:solicitud"), mapeo.propiedadSolicitud());
    }

    @Test
    void rechazaCampoDesconocido() {
        assertThrows(IllegalStateException.class, () -> new MapeoPropiedadesCmis("{\"x\":\"${noExiste}\"}"));
    }

    @Test
    void rechazaJsonInvalido() {
        assertThrows(IllegalStateException.class, () -> new MapeoPropiedadesCmis("[1,2]"));
    }

    @Test
    void vacioNoAgregaNada() {
        MapeoPropiedadesCmis mapeo = new MapeoPropiedadesCmis("{}");
        assertEquals(Map.of(), mapeo.resolver(solicitud()));
        assertEquals(Optional.empty(), mapeo.propiedadSolicitud());
    }

    @Test
    void escapaLiteralesDeConsulta() {
        assertEquals("O\\'Brien \\\\ x", ArchivoDocumentalCmis.escapar("O'Brien \\ x"));
    }
}
