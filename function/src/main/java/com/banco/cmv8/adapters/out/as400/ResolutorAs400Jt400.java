package com.banco.cmv8.adapters.out.as400;

import com.banco.cmv8.domain.port.ResolutorAs400;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Consultas con nombre a Db2 for i (AS400) con jt400 (ADR-003 §6). El catálogo dice qué consulta y con qué
 * parámetros; el SQL vive solo en {@code AS400_CONSULTAS_JSON} ({@link ConsultasAs400}).
 *
 * <p>Toma las conexiones de un {@link DataSource}: en producción el pool de {@link FuenteConexionesAs400}; en las
 * pruebas, un sustituto. Cerrar la conexión la devuelve al pool. Nunca se loguean el SQL con valores ni los
 * valores: son datos de clientes.
 */
public class ResolutorAs400Jt400 implements ResolutorAs400 {

    private static final Logger LOG = Logger.getLogger(ResolutorAs400Jt400.class.getName());

    private final DataSource conexiones;
    private final ConsultasAs400 consultas;

    public ResolutorAs400Jt400(DataSource conexiones, ConsultasAs400 consultas) {
        this.conexiones = Objects.requireNonNull(conexiones, "conexiones");
        this.consultas = Objects.requireNonNull(consultas, "consultas");
        LOG.info(() -> "Consultas AS400 declaradas: " + consultas.nombres());
    }

    @Override
    public Set<String> consultasDeclaradas() {
        return consultas.nombres();
    }

    @Override
    public Optional<Map<String, Object>> consultar(String nombreConsulta, Map<String, Object> parametros) {
        ConsultasAs400.Consulta consulta = consultas.consulta(nombreConsulta)
                .orElseThrow(() -> new IllegalArgumentException("Consulta AS400 no declarada: " + nombreConsulta));
        List<Object> valores = valoresEnOrden(consulta, parametros);

        try (Connection conexion = conexiones.getConnection();
             PreparedStatement sentencia = conexion.prepareStatement(consulta.sql())) {
            for (int i = 0; i < valores.size(); i++) {
                Object valor = valores.get(i);
                if (valor instanceof String texto) {
                    sentencia.setString(i + 1, texto);
                } else {
                    sentencia.setObject(i + 1, valor);
                }
            }
            sentencia.setMaxRows(1);
            try (ResultSet rs = sentencia.executeQuery()) {
                return rs.next() ? Optional.of(fila(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            // Sin valores ni SQL en el mensaje.
            throw new IllegalStateException("Error en la consulta AS400 '" + nombreConsulta + "' (SQLState "
                    + e.getSQLState() + ", código " + e.getErrorCode() + ")", e);
        }
    }

    /** Un parámetro ausente o nulo es error del catálogo o de los datos: nunca se ejecuta con huecos. */
    static List<Object> valoresEnOrden(ConsultasAs400.Consulta consulta, Map<String, Object> parametros) {
        // Sin distinguir mayúsculas: el SQL del Banco puede escribir :idCliente y la columna de Dataverse es idcliente.
        Map<String, Object> dados = new java.util.HashMap<>();
        if (parametros != null) {
            parametros.forEach((k, v) -> dados.put(k.toLowerCase(java.util.Locale.ROOT), v));
        }
        return consulta.parametros().stream()
                .map(nombre -> {
                    Object valor = dados.get(nombre.toLowerCase(java.util.Locale.ROOT));
                    if (valor == null) {
                        throw new IllegalArgumentException("Falta el parámetro '" + nombre + "' de la consulta AS400 '"
                                + consulta.nombre() + "'");
                    }
                    return valor;
                })
                .toList();
    }

    /** Columnas en mayúsculas; CHAR de Db2 for i viene con relleno de espacios: se recorta. */
    static Map<String, Object> fila(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        Map<String, Object> fila = new LinkedHashMap<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String columna = meta.getColumnLabel(i);
            if (columna == null || columna.isBlank()) {
                columna = meta.getColumnName(i);
            }
            Object valor = rs.getObject(i);
            if (valor instanceof String texto) {
                valor = texto.trim();
            }
            fila.put(columna.toUpperCase(Locale.ROOT), valor);
        }
        return Collections.unmodifiableMap(fila);
    }
}
