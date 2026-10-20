package com.banco.cmv8.domain.port;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Consultas con nombre al AS400 (ADR-003 §6): el catálogo dice QUÉ consulta y con qué parámetros; el SQL
 * vive solo en la configuración de Azure ({@code AS400_CONSULTAS_JSON}). Un maker nunca escribe SQL.
 */
public interface ResolutorAs400 {

    /** Nombres de consulta declarados, para que ValidarTipo detecte una referencia a una consulta inexistente. */
    Set<String> consultasDeclaradas();

    /**
     * Ejecuta la consulta con nombre con sus parámetros y devuelve la primera fila como columna → valor
     * (nombres de columna en mayúsculas), o vacío si no hay fila.
     */
    Optional<Map<String, Object>> consultar(String nombreConsulta, Map<String, Object> parametros);
}
