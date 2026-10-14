package com.banco.cmv8.adapters.out.as400;

import com.banco.cmv8.domain.Cliente;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sustituto JDBC con proxies dinámicos: ni base embebida ni pool; solo el contrato que usa el adaptador. */
class ResolutorClienteJt400Test {

    private static final String SQL = "SELECT * FROM BIB.CLIENTES WHERE IDCLIENTE = ?";

    private final List<String> eventos = new ArrayList<>();

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> tipo, InvocationHandler h) {
        return (T) Proxy.newProxyInstance(tipo.getClassLoader(), new Class<?>[] {tipo}, h);
    }

    /** Un DataSource cuya única fila, si {@code fila} no es null, es la pasada; registra cierres. */
    private DataSource fuente(Map<String, String> fila, SQLException falla) {
        return proxy(DataSource.class, (p, m, a) -> {
            if (!m.getName().equals("getConnection")) {
                return null;
            }
            eventos.add("getConnection");
            return proxy(Connection.class, (pc, mc, ac) -> switch (mc.getName()) {
                case "prepareStatement" -> {
                    eventos.add("prepare:" + ac[0]);
                    yield proxy(PreparedStatement.class, (ps, ms, as) -> switch (ms.getName()) {
                        case "setString" -> { eventos.add("bind:" + as[1]); yield null; }
                        case "setMaxRows" -> { eventos.add("maxRows:" + as[0]); yield null; }
                        case "executeQuery" -> {
                            if (falla != null) {
                                throw falla;
                            }
                            boolean[] leida = {false};
                            yield proxy(ResultSet.class, (rs, mr, ar) -> switch (mr.getName()) {
                                case "next" -> { boolean hay = fila != null && !leida[0]; leida[0] = true; yield hay; }
                                case "getString" -> fila.get((String) ar[0]);
                                case "close" -> { eventos.add("close:rs"); yield null; }
                                default -> null;
                            });
                        }
                        case "close" -> { eventos.add("close:ps"); yield null; }
                        default -> null;
                    });
                }
                case "close" -> { eventos.add("close:conn"); yield null; }
                default -> null;
            });
        });
    }

    @Test
    void resuelveRecortandoCharYDevuelveLaConexionAlPool() {
        Map<String, String> fila = new java.util.HashMap<>();
        fila.put("IDCLIENTE", "0001234   ");
        fila.put("IDCLIENTE2", "A-9 ");
        fila.put("NOMBRE", "ACME S.A.      ");
        fila.put("AFILIADOPADRE", null);
        fila.put("IDDUENO", "U77");
        ResolutorClienteJt400 resolutor = new ResolutorClienteJt400(fuente(fila, null), SQL);

        Optional<Cliente> cliente = resolutor.resolver("0001234");

        assertTrue(cliente.isPresent());
        assertEquals("0001234", cliente.get().idCliente());
        assertEquals("ACME S.A.", cliente.get().nombre());
        assertEquals(null, cliente.get().afiliadoPadre());
        assertEquals(List.of("getConnection", "prepare:" + SQL, "bind:0001234", "maxRows:1",
                "close:rs", "close:ps", "close:conn"), eventos);
    }

    @Test
    void sinFilaDevuelveVacioYCierraIgual() {
        ResolutorClienteJt400 resolutor = new ResolutorClienteJt400(fuente(null, null), SQL);
        assertTrue(resolutor.resolver("nadie").isEmpty());
        assertTrue(eventos.contains("close:conn"));
    }

    @Test
    void errorSqlSeTraduceSinExponerDatosYCierraLaConexion() {
        SQLException falla = new SQLException("usuario SECRETO clave SECRETA", "08001", 999);
        ResolutorClienteJt400 resolutor = new ResolutorClienteJt400(fuente(null, falla), SQL);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> resolutor.resolver("x"));

        assertEquals("Error consultando AS400 (SQLState 08001, código 999)", e.getMessage());
        assertTrue(eventos.contains("close:conn"));
    }
}
