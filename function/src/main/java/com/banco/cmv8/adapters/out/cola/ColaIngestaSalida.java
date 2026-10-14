package com.banco.cmv8.adapters.out.cola;

import com.banco.cmv8.config.Json;
import com.banco.cmv8.domain.MensajeIngesta;
import com.banco.cmv8.domain.port.ColaIngesta;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.ArrayList;
import java.util.List;

/**
 * Acumula los mensajes de una invocación; la función los entrega al binding @QueueOutput al terminar.
 * El host los escribe en la cola COLA_INGESTA de AzureWebJobsStorage con la identidad administrada.
 */
public class ColaIngestaSalida implements ColaIngesta {

    private final List<String> mensajes = new ArrayList<>();

    @Override
    public void encolar(MensajeIngesta mensaje) {
        try {
            mensajes.add(Json.MAPPER.writeValueAsString(mensaje));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar el mensaje de ingesta", e);
        }
    }

    public List<String> mensajes() {
        return List.copyOf(mensajes);
    }

    public boolean vacia() {
        return mensajes.isEmpty();
    }
}
