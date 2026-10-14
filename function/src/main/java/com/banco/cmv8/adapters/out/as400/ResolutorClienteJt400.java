package com.banco.cmv8.adapters.out.as400;

import com.banco.cmv8.domain.Cliente;
import com.banco.cmv8.domain.port.ResolutorCliente;
import com.ibm.as400.access.AS400JDBCDriver;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Properties;

/**
 * Resolución de cliente en Db2 for i (AS400) con jt400 (JDBC tipo 4, puertos 8471/9471 y 8476/9476).
 *
 * <p>Se instancia el driver directamente en vez de usar DriverManager: el worker de Functions carga el
 * código en un classloader propio y DriverManager no siempre ve drivers registrados ahí.
 *
 * <p>La consulta real del negocio la define S6: AS400_SQL_CLIENTE con un solo '?' y columnas
 * IDCLIENTE, IDCLIENTE2, NOMBRE, AFILIADOPADRE, IDDUENO.
 * TODO(S6): pool de conexiones si la latencia de abrir conexión (TLS + login) resulta alta.
 */
public class ResolutorClienteJt400 implements ResolutorCliente {

    public record ParametrosAs400(String host, String usuario, String clave, boolean seguro, String sql) {

        @Override
        public String toString() {
            return "ParametrosAs400[host=" + host + ", usuario=" + usuario + ", seguro=" + seguro + "]";
        }
    }

    private final ParametrosAs400 parametros;
    private final AS400JDBCDriver driver = new AS400JDBCDriver();

    public ResolutorClienteJt400(ParametrosAs400 parametros) {
        this.parametros = parametros;
    }

    String url() {
        return "jdbc:as400://" + parametros.host() + ";prompt=false;naming=sql;secure=" + parametros.seguro();
    }

    @Override
    public Optional<Cliente> resolver(String idCliente) {
        Properties props = new Properties();
        props.setProperty("user", parametros.usuario());
        props.setProperty("password", parametros.clave());
        props.setProperty("login timeout", "30");
        props.setProperty("socket timeout", "60000");
        try (Connection conexion = driver.connect(url(), props)) {
            if (conexion == null) {
                throw new IllegalStateException("El driver jt400 no aceptó la URL de conexión");
            }
            conexion.setReadOnly(true);
            try (PreparedStatement consulta = conexion.prepareStatement(parametros.sql())) {
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
