package com.banco.cmv8.application;

import com.banco.cmv8.domain.EstadoIngesta;
import com.banco.cmv8.domain.MensajeIngesta;
import com.banco.cmv8.domain.RegistroIngesta;
import com.banco.cmv8.domain.port.AlmacenAdjuntos;
import com.banco.cmv8.domain.port.ColaIngesta;
import com.banco.cmv8.domain.port.RegistroIngestas;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Watchdog (ADR-003 §6): lee la Azure Table, NO Dataverse. Reencola lo que quedó en Subido o Procesando más de
 * N minutos (evento perdido, función caída) y registra las SAS emitidas que nunca se subieron (Creada con SAS
 * vieja y sin blob), que en v1 eran invisibles. El worker es idempotente: reencolar de más es seguro.
 */
public class ReencolarEstancadas {

    private static final int TOPE = 100;

    private final RegistroIngestas registros;
    private final AlmacenAdjuntos almacen;
    private final Duration antiguedad;

    public ReencolarEstancadas(RegistroIngestas registros, AlmacenAdjuntos almacen, Duration antiguedad) {
        this.registros = registros;
        this.almacen = almacen;
        this.antiguedad = antiguedad;
    }

    public int ejecutar(ColaIngesta cola, Logger log) {
        List<RegistroIngesta> estancados = registros.listarEstancados(
                Set.of(EstadoIngesta.CREADA, EstadoIngesta.SUBIDO, EstadoIngesta.PROCESANDO), antiguedad, TOPE);
        int encolados = 0;
        int sinSubir = 0;
        for (RegistroIngesta r : estancados) {
            if (!almacen.existe(r.contenedor(), r.ruta())) {
                if (r.estado() == EstadoIngesta.CREADA) {
                    sinSubir++;
                } else {
                    log.warning(() -> "Registro estancado sin blob; no se reencola. adjunto=" + r.clave()
                            + " estado=" + r.estado());
                }
                continue;
            }
            cola.encolar(new MensajeIngesta(r.clave(), r.contenedor(), r.ruta(), "watchdog"));
            encolados++;
        }
        final int total = encolados;
        final int vencidas = sinSubir;
        log.info(() -> "Watchdog: " + total + " reencolados de " + estancados.size() + " estancados; SAS emitidas sin subir: "
                + vencidas);
        return encolados;
    }
}
