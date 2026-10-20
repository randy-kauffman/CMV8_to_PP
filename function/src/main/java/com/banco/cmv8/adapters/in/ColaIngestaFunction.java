package com.banco.cmv8.adapters.in;

import com.banco.cmv8.config.Fabrica;
import com.banco.cmv8.config.Json;
import com.banco.cmv8.domain.MensajeIngesta;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.QueueTrigger;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Worker de ingesta. Si ProcesarIngesta lanza, el mensaje vuelve a la cola (visibilityTimeout) hasta
 * maxDequeueCount (host.json) y luego pasa a "<cola>-poison" (alertar sobre esa cola).
 */
public class ColaIngestaFunction {

    @FunctionName("ProcesarIngesta")
    public void run(
            @QueueTrigger(name = "mensaje", queueName = "%COLA_INGESTA%", connection = "AzureWebJobsStorage")
            String mensaje,
            ExecutionContext ctx) {
        Logger log = ctx.getLogger();
        MensajeIngesta m = leer(mensaje, log);
        if (m == null) {
            return;
        }
        log.info(() -> "Procesando ingesta. adjunto=" + m.tabla() + "/" + m.id() + " contenedor=" + m.contenedor()
                + " origen=" + m.origen());
        Fabrica.get().procesarIngesta().ejecutar(m, log);
    }

    private static MensajeIngesta leer(String mensaje, Logger log) {
        try {
            return Json.MAPPER.readValue(mensaje, MensajeIngesta.class);
        } catch (Exception e) {
            // Reintentar un mensaje mal formado no lo arregla: se descarta con log.
            log.log(Level.SEVERE, "Mensaje de ingesta inválido; se descarta", e);
            return null;
        }
    }
}
