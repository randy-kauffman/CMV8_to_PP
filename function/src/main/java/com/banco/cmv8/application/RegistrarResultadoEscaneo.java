package com.banco.cmv8.application;

import com.banco.cmv8.domain.MensajeIngesta;
import com.banco.cmv8.domain.PoliticaSas;
import com.banco.cmv8.domain.ResultadoEscaneo;
import com.banco.cmv8.domain.port.AlmacenAdjuntos;
import com.banco.cmv8.domain.port.ColaIngesta;
import com.banco.cmv8.domain.port.RepositorioSolicitudes;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * Caso de uso: reaccionar al resultado del antivirus (o a BlobCreated en el modo sin Defender).
 * Limpio → encola. Malicioso → rechaza y borra. Desconocido → se ignora (queda registrado en el log).
 */
public class RegistrarResultadoEscaneo {

    public record EventoEscaneo(UUID solicitudId, String rutaBlob, ResultadoEscaneo resultado, String detalle,
                                String origen) {
    }

    private final RepositorioSolicitudes repositorio;
    private final AlmacenAdjuntos almacen;

    public RegistrarResultadoEscaneo(RepositorioSolicitudes repositorio, AlmacenAdjuntos almacen) {
        this.repositorio = repositorio;
        this.almacen = almacen;
    }

    public void ejecutar(EventoEscaneo evento, ColaIngesta cola, Logger log) {
        UUID id = evento.solicitudId();
        String ruta = evento.rutaBlob();
        if (id == null || !PoliticaSas.rutaPerteneceA(ruta, id)) {
            log.warning(() -> "Evento ignorado: ruta fuera del esquema {solicitudId}/{archivo}. rutaBlob=" + ruta);
            return;
        }
        switch (evento.resultado()) {
            case LIMPIO -> {
                cola.encolar(new MensajeIngesta(id, ruta, evento.origen()));
                log.info(() -> "Solicitud encolada para archivar. solicitudId=" + id + " origen=" + evento.origen());
            }
            case MALICIOSO -> {
                String detalle = evento.detalle() == null || evento.detalle().isBlank() ? "" : " (" + evento.detalle() + ")";
                repositorio.marcarRechazado(id, "Malware detectado por el antivirus" + detalle);
                almacen.borrar(ruta);
                log.warning(() -> "Archivo malicioso rechazado y borrado. solicitudId=" + id + " rutaBlob=" + ruta);
            }
            case DESCONOCIDO -> log.warning(() -> "Resultado de escaneo desconocido; se ignora. solicitudId=" + id
                    + " rutaBlob=" + ruta + " detalle=" + evento.detalle());
        }
    }
}
