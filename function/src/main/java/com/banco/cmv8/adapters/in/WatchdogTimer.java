package com.banco.cmv8.adapters.in;

import com.banco.cmv8.adapters.out.cola.ColaIngestaSalida;
import com.banco.cmv8.config.Fabrica;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.OutputBinding;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.QueueOutput;
import com.microsoft.azure.functions.annotation.TimerTrigger;

import java.util.List;

/** Cada 15 minutos reencola las solicitudes que quedaron en SUBIDO sin resultado. */
public class WatchdogTimer {

    @FunctionName("Watchdog")
    public void run(
            @TimerTrigger(name = "temporizador", schedule = "0 */15 * * * *") String temporizador,
            @QueueOutput(name = "salida", queueName = "%COLA_INGESTA%", connection = "AzureWebJobsStorage")
            OutputBinding<List<String>> salida,
            ExecutionContext ctx) {
        ColaIngestaSalida cola = new ColaIngestaSalida();
        Fabrica.get().reencolarEstancadas().ejecutar(cola, ctx.getLogger());
        if (!cola.vacia()) {
            salida.setValue(cola.mensajes());
        }
    }
}
