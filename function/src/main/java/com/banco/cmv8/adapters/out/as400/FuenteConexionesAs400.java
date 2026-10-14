package com.banco.cmv8.adapters.out.as400;

import com.ibm.as400.access.AS400JDBCDataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.SQLException;
import java.time.Duration;

/**
 * Pool de conexiones a Db2 for i (jt400) con HikariCP. Una instancia por proceso de la Function: abrir una
 * conexión con TLS cuesta segundos (S6: 3,6 s de conexión, 4,5 s el ciclo conectar+consultar+cerrar) y una
 * consulta en conexión abierta ~120 ms.
 *
 * <p>Decisiones (docs/backlog-tecnico.md, hallazgo 5):
 * <ul>
 *   <li>Pool chico: la resolución de cliente es una consulta corta; más conexiones solo ocupan jobs QZDASOINIT.</li>
 *   <li>{@code maxLifetime} por debajo del timeout de inactividad del job y del firewall, para que el pool
 *       recicle antes de que el otro lado corte.</li>
 *   <li>{@code initializationFailTimeout = -1}: la Function arranca aunque el IBM i no responda; el error sale
 *       en la invocación, no en el arranque.</li>
 *   <li>Sin reintentos de conexión dentro de una invocación: un secreto equivocado multiplicado por reintentos
 *       puede deshabilitar el perfil (QMAXSIGN).</li>
 * </ul>
 */
public final class FuenteConexionesAs400 {

    public record ParametrosAs400(String host, String usuario, String clave, boolean seguro) {

        @Override
        public String toString() {
            return "ParametrosAs400[host=" + host + ", usuario=" + usuario + ", seguro=" + seguro + "]";
        }
    }

    public record ParametrosPool(int maximo, int minimoOcioso, Duration vidaMaxima) {

        public static ParametrosPool porDefecto() {
            return new ParametrosPool(4, 1, Duration.ofMinutes(10));
        }

        public ParametrosPool {
            if (maximo < 1 || minimoOcioso < 0 || minimoOcioso > maximo) {
                throw new IllegalArgumentException("Pool AS400 inválido: maximo=" + maximo + " minimoOcioso=" + minimoOcioso);
            }
            if (vidaMaxima.compareTo(Duration.ofSeconds(30)) < 0) {
                throw new IllegalArgumentException("La vida máxima de una conexión AS400 debe ser de al menos 30 s");
            }
        }
    }

    private FuenteConexionesAs400() {
    }

    public static HikariDataSource crear(ParametrosAs400 as400, ParametrosPool pool) {
        AS400JDBCDataSource origen = new AS400JDBCDataSource(as400.host(), as400.usuario(), as400.clave());
        origen.setSecure(as400.seguro());
        origen.setPrompt(false);
        origen.setNaming("sql");
        try {
            origen.setLoginTimeout(30);
        } catch (SQLException e) {
            throw new IllegalStateException("jt400 rechazó el login timeout", e);
        }
        origen.setSocketTimeout(60_000);
        origen.setAccess("read only");
        // Cache de sentencias preparadas de jt400 (paquete SQL extendido): mismo efecto que reutilizar la
        // PreparedStatement en S6, pero a través del pool.
        origen.setExtendedDynamic(true);
        origen.setPackageCache(true);

        HikariConfig config = new HikariConfig();
        config.setPoolName("as400");
        config.setDataSource(origen);
        config.setMaximumPoolSize(pool.maximo());
        config.setMinimumIdle(pool.minimoOcioso());
        config.setMaxLifetime(pool.vidaMaxima().toMillis());
        config.setKeepaliveTime(Duration.ofMinutes(2).toMillis());
        config.setIdleTimeout(Duration.ofMinutes(5).toMillis());
        config.setConnectionTimeout(Duration.ofSeconds(10).toMillis());
        config.setValidationTimeout(Duration.ofSeconds(5).toMillis());
        config.setConnectionTestQuery("VALUES 1");
        config.setReadOnly(true);
        config.setInitializationFailTimeout(-1);
        config.setRegisterMbeans(false);
        return new HikariDataSource(config);
    }
}
