package com.banco.cmv8.adapters.out.cmis;

import com.banco.cmv8.config.Json;
import com.banco.cmv8.domain.SolicitudIngesta;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mapeo de CMIS_PROPIEDADES_JSON: {@code {"idPropiedadCM": "${campo}" | "literal"}}.
 * Campos disponibles: idCliente, idCliente2, nombreCliente, afiliadoPadre, idDueno, solicitudId,
 * nombreArchivo, contentType. Un campo sin valor no se envía (CM decidirá si es obligatorio).
 */
public final class MapeoPropiedadesCmis {

    public static final String CAMPO_SOLICITUD = "solicitudId";

    private static final Pattern REFERENCIA = Pattern.compile("^\\$\\{([A-Za-z0-9]+)}$");

    private static final Map<String, Function<SolicitudIngesta, String>> CAMPOS = Map.of(
            "idCliente", SolicitudIngesta::idCliente,
            "idCliente2", SolicitudIngesta::idCliente2,
            "nombreCliente", SolicitudIngesta::nombreCliente,
            "afiliadoPadre", SolicitudIngesta::afiliadoPadre,
            "idDueno", SolicitudIngesta::idDueno,
            CAMPO_SOLICITUD, s -> s.id().toString(),
            "nombreArchivo", SolicitudIngesta::nombreArchivo,
            "contentType", SolicitudIngesta::contentType);

    private final Map<String, String> mapeo;

    public MapeoPropiedadesCmis(String json) {
        Map<String, String> leido;
        try {
            leido = json == null || json.isBlank()
                    ? Map.of()
                    : Json.MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, String>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("CMIS_PROPIEDADES_JSON no es un objeto JSON de texto a texto", e);
        }
        for (Map.Entry<String, String> e : leido.entrySet()) {
            campoReferenciado(e.getValue()).ifPresent(campo -> {
                if (!CAMPOS.containsKey(campo)) {
                    throw new IllegalStateException("CMIS_PROPIEDADES_JSON referencia un campo desconocido: "
                            + campo + ". Válidos: " + camposValidos());
                }
            });
        }
        this.mapeo = Collections.unmodifiableMap(new LinkedHashMap<>(leido));
    }

    public static Set<String> camposValidos() {
        return CAMPOS.keySet();
    }

    private static Optional<String> campoReferenciado(String valor) {
        if (valor == null) {
            return Optional.empty();
        }
        Matcher m = REFERENCIA.matcher(valor.trim());
        return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /** Propiedad CM que guarda el solicitudId, si el mapeo la define (permite idempotencia por consulta). */
    public Optional<String> propiedadSolicitud() {
        return mapeo.entrySet().stream()
                .filter(e -> campoReferenciado(e.getValue()).map(CAMPO_SOLICITUD::equals).orElse(false))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /** Propiedades resueltas para la solicitud (los valores son texto; ver S4a para tipos no texto). */
    public Map<String, Object> resolver(SolicitudIngesta solicitud) {
        Map<String, Object> propiedades = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : mapeo.entrySet()) {
            Optional<String> campo = campoReferenciado(e.getValue());
            String valor = campo.isPresent() ? CAMPOS.get(campo.get()).apply(solicitud) : e.getValue();
            if (valor != null && !valor.isBlank()) {
                propiedades.put(e.getKey(), valor);
            }
        }
        return propiedades;
    }
}
