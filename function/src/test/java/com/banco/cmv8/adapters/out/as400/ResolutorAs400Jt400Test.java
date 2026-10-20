package com.banco.cmv8.adapters.out.as400;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResolutorAs400Jt400Test {

    private final DataSource fuente = mock(DataSource.class);
    private final Connection conexion = mock(Connection.class);
    private final PreparedStatement sentencia = mock(PreparedStatement.class);
    private final ResultSet rs = mock(ResultSet.class);
    private final ResultSetMetaData meta = mock(ResultSetMetaData.class);

    private final ConsultasAs400 consultas = ConsultasAs400.parsear(
            "{\"cliente\": \"SELECT IDCLIENTE, nombre FROM LIB.CLI WHERE ID = :idCliente AND TIPO = :tipo\"}",
            Optional.empty());

    private void conexionLista() throws SQLException {
        when(fuente.getConnection()).thenReturn(conexion);
        when(conexion.prepareStatement(anyString())).thenReturn(sentencia);
        when(sentencia.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(meta);
    }

    @Test
    void devuelveLaPrimeraFilaConColumnasEnMayusculasYCharRecortado() throws SQLException {
        conexionLista();
        when(rs.next()).thenReturn(true);
        when(meta.getColumnCount()).thenReturn(2);
        when(meta.getColumnLabel(1)).thenReturn("IDCLIENTE");
        when(meta.getColumnLabel(2)).thenReturn("nombre");
        when(rs.getObject(1)).thenReturn("00123   ");
        when(rs.getObject(2)).thenReturn(null);

        Map<String, Object> fila = new ResolutorAs400Jt400(fuente, consultas)
                .consultar("cliente", Map.of("idCliente", "00123", "tipo", 4)).orElseThrow();

        assertEquals("00123", fila.get("IDCLIENTE"));
        assertTrue(fila.containsKey("NOMBRE"));
        verify(conexion).prepareStatement("SELECT IDCLIENTE, nombre FROM LIB.CLI WHERE ID = ? AND TIPO = ?");
        verify(sentencia).setString(1, "00123");
        verify(sentencia).setObject(2, 4);
        verify(sentencia).setMaxRows(1);
    }

    @Test
    void sinFilaDevuelveVacio() throws SQLException {
        conexionLista();
        when(rs.next()).thenReturn(false);

        assertFalse(new ResolutorAs400Jt400(fuente, consultas)
                .consultar("cliente", Map.of("idCliente", "x", "tipo", "y")).isPresent());
    }

    @Test
    void consultaInexistenteNoTocaLaBase() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> new ResolutorAs400Jt400(fuente, consultas).consultar("otra", Map.of()));
        verify(fuente, never()).getConnection();
    }

    @Test
    void parametroFaltanteNoTocaLaBase() throws SQLException {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new ResolutorAs400Jt400(fuente, consultas).consultar("cliente", Map.of("idCliente", "1")));
        assertTrue(e.getMessage().contains("tipo"));
        verify(fuente, never()).getConnection();
    }

    @Test
    void errorSqlNoIncluyeValores() throws SQLException {
        when(fuente.getConnection()).thenReturn(conexion);
        when(conexion.prepareStatement(anyString())).thenThrow(new SQLException("falló con 00123", "42704", -204));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new ResolutorAs400Jt400(fuente, consultas)
                        .consultar("cliente", Map.of("idCliente", "00123", "tipo", 1)));
        assertFalse(e.getMessage().contains("00123"));
        assertTrue(e.getMessage().contains("42704"));
    }

    @Test
    void declaraLasConsultasDelCatalogo() {
        assertEquals(java.util.Set.of("cliente"), new ResolutorAs400Jt400(fuente, consultas).consultasDeclaradas());
    }
}
