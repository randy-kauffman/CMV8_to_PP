package com.banco.cmv8.application;

import com.banco.cmv8.domain.Adjunto;
import com.banco.cmv8.domain.ClaveAdjunto;
import com.banco.cmv8.domain.EstadoIngesta;
import com.banco.cmv8.domain.MensajeIngesta;
import com.banco.cmv8.domain.RegistroIngesta;
import com.banco.cmv8.domain.ResultadoEscaneo;
import com.banco.cmv8.domain.port.AlmacenAdjuntos;
import com.banco.cmv8.domain.port.ColaIngesta;
import com.banco.cmv8.domain.port.RegistroIngestas;
import com.banco.cmv8.domain.port.RepositorioAdjuntos;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Reacción a BlobCreated (o al resultado del antivirus): limpio → marca Subido en el registro operativo y encola;
 * malicioso → Rechazado y borra; desconocido → se ignora. Dataverse no se toca en el camino feliz: el Subido lo
 * escribe la app con la identidad del usuario (ADR-003 §4.1).
 */
public class RegistrarResultadoEscaneo {

    public record EventoEscaneo(ClaveAdjunto clave, String rutaBlob, ResultadoEscaneo resultado, String detalle,
                                String origen) {
    }

    private final RegistroIngestas registros;
    private final RepositorioAdjuntos repositorio;
    private final AlmacenAdjuntos almacen;
    private final String contenedorIngesta;
    private final Clock reloj;

    public RegistrarResultadoEscaneo(RegistroIngestas registros, RepositorioAdjuntos repositorio,
                                     AlmacenAdjuntos almacen, String contenedorIngesta, Clock reloj) {
        this.registros = registros;
        this.repositorio = repositorio;
        this.almacen = almacen;
        this.contenedorIngesta = contenedorIngesta;
        this.reloj = reloj;
    }

    public void ejecutar(EventoEscaneo evento, ColaIngesta cola, Logger log) {
        ClaveAdjunto clave = evento.clave();
        String ruta = evento.rutaBlob();
        if (clave == null || !clave.esSuRuta(ruta)) {
            log.warning(() -> "Evento ignorado: ruta fuera del esquema <tabla>/<id>/<archivo>. ruta=" + ruta);
            return;
        }
        Optional<RegistroIngesta> registro = registros.obtener(clave);
        if (registro.isEmpty()) {
            log.warning(() -> "Blob sin registro operativo (no pasó por el broker); se ignora. adjunto=" + clave);
            return;
        }
        switch (evento.resultado()) {
            case LIMPIO -> {
                if (registro.get().estado() == EstadoIngesta.CREADA) {
                    registros.guardar(registro.get().subido(reloj.instant()));
                }
                cola.encolar(new MensajeIngesta(clave, contenedorIngesta, ruta, evento.origen()));
                log.info(() -> "Adjunto encolado para procesar. adjunto=" + clave + " origen=" + evento.origen());
            }
            case MALICIOSO -> {
                String detalle = evento.detalle() == null || evento.detalle().isBlank() ? "" : " (" + evento.detalle() + ")";
                String motivo = "Malware detectado por el antivirus" + detalle;
                repositorio.actualizar(clave, Map.of(Adjunto.COL_ESTADO, EstadoIngesta.RECHAZADO, Adjunto.COL_MENSAJE, motivo));
                registros.guardar(registro.get().conEstado(EstadoIngesta.RECHAZADO).conMensaje(motivo));
                almacen.borrar(contenedorIngesta, ruta);
                log.warning(() -> "Archivo malicioso rechazado y borrado. adjunto=" + clave);
            }
            case DESCONOCIDO -> log.warning(() -> "Resultado de escaneo desconocido; se ignora. adjunto=" + clave
                    + " detalle=" + evento.detalle());
        }
    }
}
