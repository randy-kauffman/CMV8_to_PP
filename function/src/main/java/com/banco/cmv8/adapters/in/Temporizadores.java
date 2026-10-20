package com.banco.cmv8.adapters.in;

import com.banco.cmv8.adapters.out.cola.ColaIngestaSalida;
import com.banco.cmv8.config.Fabrica;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.OutputBinding;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.QueueOutput;
import com.microsoft.azure.functions.annotation.TimerTrigger;

import java.util.List;

/**
 * Los tres timers de la v2 (ADR-003 §6). Ninguno lee Dataverse para decidir: el Watchdog y Depurar leen las
 * Azure Tables, VigilarCatalogo lee Content Manager y el catálogo en caché.
 */
public class Temporizadores {

    /** Cada 15 minutos: reencola lo estancado y registra las SAS emitidas que nunca se subieron. */
    @FunctionName("Watchdog")
    public void watchdog(
            @TimerTrigger(name = "temporizador", schedule = "0 */15 * * * *") String temporizador,
            @QueueOutput(name = "salida", queueName = "%COLA_INGESTA%", connection = "AzureWebJobsStorage")
            OutputBinding<List<String>> salida,
            ExecutionContext ctx) {
        ColaIngestaSalida cola = new ColaIngestaSalida();
        Fabrica.get().reencolarEstancadas().ejecutar(cola, ctx.getLogger());
        AdjuntosHttp.entregar(cola, salida);
    }

    /** Cada hora: compara la definición viva de los tipos en uso contra el catálogo y registra conflictos. */
    @FunctionName("VigilarCatalogo")
    public void vigilarCatalogo(
            @TimerTrigger(name = "temporizador", schedule = "0 5 * * * *") String temporizador,
            ExecutionContext ctx) {
        Fabrica.get().vigilarCatalogo().ejecutar(ctx.getLogger());
    }

    /** Una vez por día (02:30 UTC): procesa la agenda de depuración respetando los estados. */
    @FunctionName("Depurar")
    public void depurar(
            @TimerTrigger(name = "temporizador", schedule = "0 30 2 * * *") String temporizador,
            ExecutionContext ctx) {
        Fabrica.get().depurar().ejecutar(ctx.getLogger());
    }
}
