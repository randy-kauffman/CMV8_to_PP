package com.banco.cmv8.adapters.out.as400;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Catálogo de consultas con nombre del AS400 (ADR-003 §6), parseado de {@code AS400_CONSULTAS_JSON}:
 * {@code {"cliente": "SELECT ... WHERE IDCLIENTE = :idCliente"}}. Puro, sin JDBC: se prueba sin servidor.
 *
 * <p>Los parámetros {@code :nombre} se reescriben a {@code ?} guardando el orden, para ejecutar SIEMPRE con
 * PreparedStatement (nunca concatenación de valores). Un mismo parámetro puede aparecer varias veces. Lo que
 * está entre comillas simples (literal) o dobles (identificador) no se toca.
 */
public final class ConsultasAs400 {

    /** Nombre de la consulta v1 cuando solo está {@code AS400_SQL_CLIENTE}. */
    public static final String CONSULTA_V1 = "cliente";
    /** Único parámetro de la consulta v1 (su '?' posicional). */
    public static final String PARAMETRO_V1 = "idcliente"; // nombre lógico de la columna en la tabla de adjuntos

    static final Pattern NOMBRE_VALIDO = Pattern.compile("^[a-z][a-z0-9_]*$");

    private static final ObjectMapper JSON = new ObjectMapper();

    /** SQL listo para preparar y los nombres de parámetro en el orden de los '?'. */
    public record Consulta(String nombre, String sql, List<String> parametros) {

        public Consulta {
            Objects.requireNonNull(nombre, "nombre");
            Objects.requireNonNull(sql, "sql");
            parametros = List.copyOf(parametros);
        }

        /** Sin el SQL: no se loguea (puede revelar el esquema del Banco). */
        @Override
        public String toString() {
            return "Consulta[" + nombre + ", parametros=" + parametros + "]";
        }
    }

    private final Map<String, Consulta> consultas;

    private ConsultasAs400(Map<String, Consulta> consultas) {
        this.consultas = Collections.unmodifiableMap(consultas);
    }

    /**
     * @param json      contenido de {@code AS400_CONSULTAS_JSON} (vacío o en blanco = sin consultas v2)
     * @param sqlV1     {@code AS400_SQL_CLIENTE}: solo se usa si el JSON está vacío; un único '?'
     */
    public static ConsultasAs400 parsear(String json, Optional<String> sqlV1) {
        Map<String, Consulta> resultado = new LinkedHashMap<>();
        if (json != null && !json.isBlank()) {
            JsonNode raiz;
            try {
                raiz = JSON.readTree(json);
            } catch (JsonProcessingException e) {
                // Sin el contenido en el mensaje: es SQL del Banco.
                throw new IllegalArgumentException("AS400_CONSULTAS_JSON no es JSON válido (línea "
                        + (e.getLocation() == null ? "?" : e.getLocation().getLineNr()) + ")");
            }
            if (raiz == null || !raiz.isObject()) {
                throw new IllegalArgumentException("AS400_CONSULTAS_JSON debe ser un objeto {\"nombre\": \"SQL\"}");
            }
            for (Map.Entry<String, JsonNode> campo : raiz.properties()) {
                String nombre = campo.getKey();
                if (!NOMBRE_VALIDO.matcher(nombre).matches()) {
                    throw new IllegalArgumentException("Nombre de consulta AS400 inválido: '" + nombre
                            + "' (minúsculas, dígitos y guion bajo, empezando por letra)");
                }
                if (!campo.getValue().isTextual() || campo.getValue().asText().isBlank()) {
                    throw new IllegalArgumentException("La consulta AS400 '" + nombre + "' no es un SQL en texto");
                }
                resultado.put(nombre, reescribir(nombre, campo.getValue().asText()));
            }
        } else if (sqlV1 != null && sqlV1.isPresent() && !sqlV1.get().isBlank()) {
            String sql = sqlV1.get();
            int marcadores = contarMarcadores(sql);
            if (marcadores != 1) {
                throw new IllegalArgumentException("AS400_SQL_CLIENTE debe tener exactamente un '?' (tiene "
                        + marcadores + ")");
            }
            resultado.put(CONSULTA_V1, new Consulta(CONSULTA_V1, sql, List.of(PARAMETRO_V1)));
        }
        return new ConsultasAs400(resultado);
    }

    public Set<String> nombres() {
        return consultas.keySet();
    }

    public Optional<Consulta> consulta(String nombre) {
        return Optional.ofNullable(nombre == null ? null : consultas.get(nombre));
    }

    /**
     * Reescribe {@code :param} a {@code ?}. Rechaza '?' posicionales mezclados: no habría nombre con qué ligarlos.
     */
    static Consulta reescribir(String nombre, String sql) {
        StringBuilder salida = new StringBuilder(sql.length());
        List<String> parametros = new ArrayList<>();
        char comilla = 0;
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (comilla != 0) {
                salida.append(c);
                if (c == comilla) {
                    comilla = 0; // una comilla duplicada ('') cierra y reabre: el resultado es el mismo
                }
                i++;
                continue;
            }
            if (c == '\'' || c == '"') {
                comilla = c;
                salida.append(c);
                i++;
                continue;
            }
            if (c == '?') {
                throw new IllegalArgumentException("La consulta AS400 '" + nombre
                        + "' usa '?' posicional; usá parámetros con nombre (:parametro)");
            }
            if (c == ':' && i + 1 < sql.length() && esInicioIdentificador(sql.charAt(i + 1))) {
                int fin = i + 1;
                while (fin < sql.length() && esParteIdentificador(sql.charAt(fin))) {
                    fin++;
                }
                parametros.add(sql.substring(i + 1, fin));
                salida.append('?');
                i = fin;
                continue;
            }
            salida.append(c);
            i++;
        }
        if (comilla != 0) {
            throw new IllegalArgumentException("La consulta AS400 '" + nombre + "' tiene comillas sin cerrar");
        }
        return new Consulta(nombre, salida.toString(), parametros);
    }

    /** Cuenta '?' fuera de comillas (validación de la consulta v1). */
    static int contarMarcadores(String sql) {
        int cuenta = 0;
        char comilla = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (comilla != 0) {
                if (c == comilla) {
                    comilla = 0;
                }
            } else if (c == '\'' || c == '"') {
                comilla = c;
            } else if (c == '?') {
                cuenta++;
            }
        }
        return cuenta;
    }

    private static boolean esInicioIdentificador(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean esParteIdentificador(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
