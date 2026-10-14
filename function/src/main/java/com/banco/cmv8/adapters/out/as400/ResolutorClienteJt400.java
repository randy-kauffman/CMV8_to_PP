package com.banco.cmv8.adapters.out.as400;

import com.banco.cmv8.domain.Cliente;
import com.banco.cmv8.domain.port.ResolutorCliente;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Resolución de cliente en Db2 for i (AS400) con jt400 (JDBC tipo 4, puertos 8471/9471 y 8476/9476).
 *
 * <p>Toma las conexiones de un {@link DataSource}: en producción el pool de {@link FuenteConexionesAs400};
 * en las pruebas, un sustituto. Cerrar la conexión la devuelve al pool, no la corta.
 *
 * <p>La consulta real del negocio la define el Banco: AS400_SQL_CLIENTE con un solo '?' y columnas
 * IDCLIENTE, IDCLIENTE2, NOMBRE, AFILIADOPADRE, IDDUENO.
 */
public class ResolutorClienteJt400 implements ResolutorCliente {

    private final DataSource conexiones;
    private final String sql;

    public ResolutorClienteJt400(DataSource conexiones, String sql) {
        this.conexiones = conexiones;
        this.sql = sql;
    }

    @Override
    public Optional<Cliente> resolver(String idCliente) {
        try (Connection conexion = conexiones.getConnection();
             PreparedStatement consulta = conexion.prepareStatement(sql)) {
            consulta.setString(1, idCliente);
            consulta.setMaxRows(1);
            try (ResultSet rs = consulta.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Cliente(
                        columna(rs, "IDCLIENTE"),
                        columna(rs, "IDCLIENTE2"),
                        columna(rs, "NOMBRE"),
                        columna(rs, "AFILIADOPADRE"),
                        columna(rs, "IDDUENO")));
            }
        } catch (SQLException e) {
            // Sin el id ni la contraseña en el mensaje.
            throw new IllegalStateException("Error consultando AS400 (SQLState " + e.getSQLState()
                    + ", código " + e.getErrorCode() + ")", e);
        }
    }

    /** CHAR de Db2 for i viene con relleno de espacios: se recorta. */
    private static String columna(ResultSet rs, String nombre) throws SQLException {
        String valor = rs.getString(nombre);
        return valor == null ? null : valor.trim();
    }
}
