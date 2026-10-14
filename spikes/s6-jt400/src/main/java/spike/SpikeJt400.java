package spike;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ibm.as400.access.AS400;
import com.ibm.as400.access.AS400JDBCDriver;
import com.ibm.as400.access.AS400SecurityException;
import com.ibm.as400.access.SecureAS400;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Properties;

/**
 * Spike S6: mecánica de jt400 contra PUB400 (signon, cambio de contraseña expirada, TLS, latencias).
 * La contraseña se lee de local.settings.json y nunca se imprime: toda salida pasa por red().
 */
public final class SpikeJt400 {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static File settingsFile;
    private static ObjectNode settings;
    private static String host;
    private static String user;
    private static String password;

    public static void main(String[] args) throws Exception {
        settingsFile = new File(args[0]);
        settings = (ObjectNode) JSON.readTree(settingsFile);
        ObjectNode values = (ObjectNode) settings.get("Values");
        host = values.get("AS400_HOST").asText();
        user = values.get("AS400_USER").asText();
        password = values.get("AS400_PASSWORD").asText();

        System.out.println("Java: " + System.getProperty("java.version"));
        System.out.println("jt400 (AS400JDBCDriver): " + new AS400JDBCDriver().getMajorVersion() + "."
                + new AS400JDBCDriver().getMinorVersion() + " / " + versionJt400());

        signon(true);
        signon(false);

        for (boolean secure : new boolean[] {true, false}) {
            jdbc(secure);
        }
    }

    private static String versionJt400() {
        Package p = AS400.class.getPackage();
        String v = p == null ? null : p.getImplementationVersion();
        try {
            Class<?> c = Class.forName("com.ibm.as400.access.Copyright");
            v = v + " / Copyright.version=" + c.getField("version").get(null);
        } catch (Exception ignored) {
            // sin campo de versión
        }
        return v;
    }

    // ---------------------------------------------------------------- signon + contraseña expirada

    private static void signon(boolean secure) throws Exception {
        System.out.println("\n== Signon " + (secure ? "SecureAS400 (TLS, 9476)" : "AS400 (claro, 8476)") + " ==");
        AS400 sys = nuevo(secure, password);
        long t0 = System.nanoTime();
        try {
            sys.connectService(AS400.SIGNON);
            System.out.printf("OK signon en %d ms; VRM servidor=%s; pwd expira=%s%n", ms(t0),
                    vrm(sys), sys.getPasswordExpirationDate());
        } catch (AS400SecurityException e) {
            System.out.printf("AS400SecurityException rc=%d en %d ms: %s%n", e.getReturnCode(), ms(t0), red(e.getMessage()));
            if (e.getReturnCode() == AS400SecurityException.PASSWORD_EXPIRED) {
                cambiarPassword(secure);
            }
        } catch (Exception e) {
            System.out.printf("FALLO signon en %d ms: %s: %s%n", ms(t0), e.getClass().getName(), red(String.valueOf(e.getMessage())));
        } finally {
            sys.disconnectAllServices();
        }
    }

    private static String vrm(AS400 sys) {
        try {
            return "V" + sys.getVersion() + "R" + sys.getRelease() + "M" + sys.getModification();
        } catch (Exception e) {
            return "?";
        }
    }

    private static AS400 nuevo(boolean secure, String pwd) throws Exception {
        AS400 sys = secure ? new SecureAS400(host, user, pwd.toCharArray()) : new AS400(host, user, pwd.toCharArray());
        sys.setGuiAvailable(false);
        return sys;
    }

    private static void cambiarPassword(boolean secure) throws Exception {
        String[] candidatas = {generar(16, true), generar(10, false)};
        for (String nueva : candidatas) {
            // Se guarda primero como pendiente para no perderla si el proceso muere tras el cambio.
            guardar("AS400_PASSWORD_PENDIENTE", nueva);
            AS400 sys = nuevo(secure, password);
            try {
                sys.changePassword(password.toCharArray(), nueva.toCharArray());
                password = nueva;
                guardar("AS400_PASSWORD", nueva);
                quitar("AS400_PASSWORD_PENDIENTE");
                System.out.printf("Contraseña cambiada (largo %d, %s). Guardada en local.settings.json.%n",
                        nueva.length(), nueva.length() == 16 ? "mayúsc+minúsc+dígitos" : "mayúsc+dígitos");
                AS400 check = nuevo(secure, password);
                long t0 = System.nanoTime();
                check.connectService(AS400.SIGNON);
                System.out.printf("Re-signon con la nueva contraseña OK en %d ms; VRM=%s%n", ms(t0), vrm(check));
                check.disconnectAllServices();
                return;
            } catch (AS400SecurityException e) {
                quitar("AS400_PASSWORD_PENDIENTE");
                System.out.printf("changePassword largo %d rechazado rc=%d: %s%n", nueva.length(), e.getReturnCode(),
                        red(e.getMessage()));
            } finally {
                sys.disconnectAllServices();
            }
        }
        throw new IllegalStateException("No se pudo cambiar la contraseña");
    }

    /** Sin caracteres repetidos ni dígitos adyacentes (QPWDLMTREP / QPWDLMTAJC), empieza con letra. */
    private static String generar(int largo, boolean mixta) {
        SecureRandom r = new SecureRandom();
        String up = "ABCDEFGHJKLMNPQRSTUVWXYZ", lo = "abcdefghijkmnpqrstuvwxyz", dg = "23456789";
        while (true) {
            StringBuilder sb = new StringBuilder();
            sb.append(up.charAt(r.nextInt(up.length())));
            while (sb.length() < largo) {
                boolean prevDigit = Character.isDigit(sb.charAt(sb.length() - 1));
                String pool = prevDigit ? (mixta ? up + lo : up) : (mixta ? up + lo + dg + dg : up + dg + dg);
                char c = pool.charAt(r.nextInt(pool.length()));
                if (sb.indexOf(String.valueOf(c)) < 0) {
                    sb.append(c);
                }
            }
            String s = sb.toString();
            boolean ok = s.chars().anyMatch(Character::isDigit) && s.chars().anyMatch(Character::isUpperCase)
                    && (!mixta || s.chars().anyMatch(Character::isLowerCase));
            if (ok) {
                return s;
            }
        }
    }

    private static void guardar(String clave, String valor) throws Exception {
        ((ObjectNode) settings.get("Values")).put(clave, valor);
        escribir();
    }

    private static void quitar(String clave) throws Exception {
        ((ObjectNode) settings.get("Values")).remove(clave);
        escribir();
    }

    private static void escribir() throws Exception {
        Path destino = settingsFile.toPath();
        Path tmp = destino.resolveSibling(destino.getFileName() + ".tmp");
        JSON.writeValue(tmp.toFile(), settings);
        Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
        Files.move(tmp, destino, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    // ---------------------------------------------------------------- JDBC

    private static void jdbc(boolean secure) {
        String url = "jdbc:as400://" + host + ";prompt=false;naming=sql;secure=" + secure;
        System.out.println("\n== JDBC " + url + " ==");
        AS400JDBCDriver driver = new AS400JDBCDriver();
        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        props.setProperty("login timeout", "30");
        props.setProperty("socket timeout", "60000");

        long t0 = System.nanoTime();
        try (Connection c1 = driver.connect(url, props)) {
            System.out.printf("Conexión 1: %d ms%n", ms(t0));
            DatabaseMetaData md = c1.getMetaData();
            System.out.println("Driver: " + md.getDriverName() + " " + md.getDriverVersion()
                    + " | Producto: " + md.getDatabaseProductName() + " " + md.getDatabaseProductVersion());

            long q = System.nanoTime();
            imprimir(c1, "SELECT CURRENT SERVER, CURRENT USER, CURRENT TIMESTAMP FROM SYSIBM.SYSDUMMY1", true);
            System.out.printf("Primera consulta: %d ms%n", ms(q));
            q = System.nanoTime();
            imprimir(c1, "SELECT COUNT(*) FROM QSYS2.SYSTABLES WHERE TABLE_SCHEMA = 'QSYS2'", false);
            System.out.printf("COUNT SYSTABLES: %d ms%n", ms(q));
            repetir(c1, "Conexión 1");

            preparada(c1);
            try {
                imprimir(c1, "SELECT OS_NAME, OS_VERSION, OS_RELEASE, HOST_NAME FROM SYSIBMADM.ENV_SYS_INFO", false);
            } catch (SQLException e) {
                System.out.println("ENV_SYS_INFO no disponible: " + red(e.getMessage()));
            }

            long t2 = System.nanoTime();
            try (Connection c2 = driver.connect(url, props)) {
                System.out.printf("Conexión 2 (con la 1 abierta): %d ms%n", ms(t2));
                long q2 = System.nanoTime();
                imprimir(c2, "SELECT CURRENT SERVER, CURRENT USER, CURRENT TIMESTAMP FROM SYSIBM.SYSDUMMY1", true);
                System.out.printf("Primera consulta conexión 2: %d ms%n", ms(q2));
                repetir(c2, "Conexión 2");
            }
            // Patrón "una conexión por invocación" (lo que hace hoy ResolutorClienteJt400).
            long[] ciclos = new long[5];
            for (int i = 0; i < ciclos.length; i++) {
                long t = System.nanoTime();
                try (Connection c = driver.connect(url, props);
                     PreparedStatement ps = c.prepareStatement(
                             "SELECT TABLE_NAME FROM QSYS2.SYSTABLES WHERE TABLE_SCHEMA = ? FETCH FIRST 1 ROWS ONLY")) {
                    ps.setString(1, "QSYS2");
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                    }
                }
                ciclos[i] = ms(t);
            }
            System.out.printf("Ciclo conectar+consulta+cerrar x5 (ms): %s, promedio %.1f%n", Arrays.toString(ciclos),
                    Arrays.stream(ciclos).average().orElse(0));
        } catch (SQLException e) {
            System.out.printf("FALLO JDBC secure=%s en %d ms: SQLState=%s code=%d %s%n", secure, ms(t0),
                    e.getSQLState(), e.getErrorCode(), red(e.getMessage()));
            Throwable cause = e.getCause();
            while (cause != null) {
                System.out.println("  causa: " + cause.getClass().getName() + ": " + red(String.valueOf(cause.getMessage())));
                cause = cause.getCause();
            }
        }
    }

    private static void repetir(Connection c, String etiqueta) throws SQLException {
        long[] t = new long[20];
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT CURRENT SERVER, CURRENT USER, CURRENT TIMESTAMP FROM SYSIBM.SYSDUMMY1")) {
            for (int i = 0; i < t.length; i++) {
                long s = System.nanoTime();
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                }
                t[i] = System.nanoTime() - s;
            }
        }
        double avg = Arrays.stream(t).average().orElse(0) / 1e6;
        long min = Arrays.stream(t).min().orElse(0) / 1_000_000, max = Arrays.stream(t).max().orElse(0) / 1_000_000;
        System.out.printf("%s: 20 consultas SYSDUMMY1 (misma PreparedStatement) promedio %.1f ms (min %d, max %d)%n",
                etiqueta, avg, min, max);
        long[] u = new long[20];
        for (int i = 0; i < u.length; i++) {
            long s = System.nanoTime();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT TABLE_NAME FROM QSYS2.SYSTABLES WHERE TABLE_SCHEMA = ? FETCH FIRST 3 ROWS ONLY")) {
                ps.setString(1, "QSYS2");
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rs.getString(1);
                    }
                }
            }
            u[i] = System.nanoTime() - s;
        }
        System.out.printf("%s: 20 x (prepare+bind+execute+cerrar) parametrizada promedio %.1f ms%n", etiqueta,
                Arrays.stream(u).average().orElse(0) / 1e6);
    }

    private static void preparada(Connection c) throws SQLException {
        long s = System.nanoTime();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT TABLE_NAME FROM QSYS2.SYSTABLES WHERE TABLE_SCHEMA = ? FETCH FIRST 3 ROWS ONLY")) {
            ps.setString(1, "QSYS2");
            try (ResultSet rs = ps.executeQuery()) {
                StringBuilder sb = new StringBuilder();
                while (rs.next()) {
                    sb.append(rs.getString("TABLE_NAME").trim()).append(' ');
                }
                System.out.printf("PreparedStatement con ? (naming=sql): [%s] en %d ms%n", sb.toString().trim(), ms(s));
            }
        }
    }

    private static void imprimir(Connection c, String sql, boolean redactar) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData m = rs.getMetaData();
            while (rs.next()) {
                StringBuilder sb = new StringBuilder("  ");
                for (int i = 1; i <= m.getColumnCount(); i++) {
                    String v = String.valueOf(rs.getString(i)).trim();
                    sb.append(m.getColumnLabel(i)).append('=').append(redactar ? red(v) : v).append("  ");
                }
                System.out.println(sb);
            }
        }
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private static String red(String s) {
        if (s == null) {
            return null;
        }
        String r = s;
        if (password != null && !password.isEmpty()) {
            r = r.replace(password, "<PWD>");
        }
        return r.replaceAll("(?i)" + java.util.regex.Pattern.quote(user), "<USER>");
    }
}
