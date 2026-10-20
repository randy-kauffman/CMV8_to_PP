package com.banco.cmv8.application;

import com.banco.cmv8.domain.ContextoLlamador;
import com.banco.cmv8.domain.ValidacionException;
import com.banco.cmv8.domain.port.ResolutorAs400;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Consulta con nombre al AS400 para la app (reemplaza al ResolverCliente de v1): la app muestra los datos del
 * cliente antes de subir. Solo consultas declaradas en la configuración de Azure; nunca SQL del cliente.
 */
public class ConsultarAs400 {

    private final ResolutorAs400 as400;

    public ConsultarAs400(ResolutorAs400 as400) {
        this.as400 = as400;
    }

    public Optional<Map<String, Object>> ejecutar(String nombre, Map<String, Object> parametros, ContextoLlamador llamador,
                                                  Logger log) {
        if (llamador.objectId().isEmpty()) {
            throw new NoAutenticadoException("La consulta requiere la identidad Entra del llamador");
        }
        String consulta = nombre == null ? "" : nombre.trim().toLowerCase(Locale.ROOT);
        if (!as400.consultasDeclaradas().contains(consulta)) {
            throw new ValidacionException("La consulta " + consulta + " no está declarada");
        }
        try {
            Optional<Map<String, Object>> fila = as400.consultar(consulta, parametros == null ? Map.of() : parametros);
            log.info(() -> "Consulta AS400 " + consulta + " " + (fila.isPresent() ? "con fila" : "sin fila") + " llamador=" + llamador);
            return fila;
        } catch (IllegalArgumentException e) {
            throw new ValidacionException(e.getMessage());
        }
    }
}
