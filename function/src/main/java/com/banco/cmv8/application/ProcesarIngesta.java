package com.banco.cmv8.application;

import com.banco.cmv8.domain.Cliente;
import com.banco.cmv8.domain.EstadoSolicitud;
import com.banco.cmv8.domain.MensajeIngesta;
import com.banco.cmv8.domain.PoliticaSas;
import com.banco.cmv8.domain.ResultadoEscaneo;
import com.banco.cmv8.domain.SolicitudIngesta;
import com.banco.cmv8.domain.port.AlmacenAdjuntos;
import com.banco.cmv8.domain.port.ArchivoDocumental;
import com.banco.cmv8.domain.port.RepositorioSolicitudes;
import com.banco.cmv8.domain.port.ResolutorCliente;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Worker: lleva el blob de una solicitud a Content Manager en streaming.
 *
 * <p>Idempotente: si la fila ya está ARCHIVADO con objectId no vuelve a subir; si el documento
 * ya existe en CM (caída entre archivar y marcar) reutiliza su objectId. Ante una falla marca
 * ERROR y relanza para que la cola reintente (maxDequeueCount en host.json).
 */
public class ProcesarIngesta {

    public enum Desenlace { ARCHIVADO, YA_ARCHIVADO, RECHAZADO, OMITIDO }

    private final RepositorioSolicitudes repositorio;
    private final AlmacenAdjuntos almacen;
    private final ArchivoDocumental archivo;
    private final ResolutorCliente resolutor;
    private final boolean exigirEscaneoLimpio;

    public ProcesarIngesta(RepositorioSolicitudes repositorio, AlmacenAdjuntos almacen, ArchivoDocumental archivo,
                           ResolutorCliente resolutor, boolean exigirEscaneoLimpio) {
        this.repositorio = repositorio;
        this.almacen = almacen;
        this.archivo = archivo;
        this.resolutor = resolutor;
        this.exigirEscaneoLimpio = exigirEscaneoLimpio;
    }

    public Desenlace ejecutar(MensajeIngesta mensaje, Logger log) {
        UUID id = mensaje.solicitudId();
        String ruta = mensaje.rutaBlob();

        if (!PoliticaSas.rutaPerteneceA(ruta, id)) {
            // Mensaje inconsistente: no se reintenta (reintentar no lo arregla).
            log.warning(() -> "Mensaje descartado: la ruta no pertenece a la solicitud. solicitudId=" + id
                    + " rutaBlob=" + ruta);
            return Desenlace.OMITIDO;
        }

        Optional<SolicitudIngesta> encontrada = repositorio.obtener(id);
        if (encontrada.isEmpty()) {
            log.warning(() -> "Solicitud inexistente en Dataverse; se omite. solicitudId=" + id);
            return Desenlace.OMITIDO;
        }
        SolicitudIngesta solicitud = encontrada.get();

        if (solicitud.yaArchivada()) {
            log.info(() -> "Solicitud ya archivada; se omite. solicitudId=" + id);
            borrarSinFallar(ruta, log);
            return Desenlace.YA_ARCHIVADO;
        }
        if (solicitud.estado() == EstadoSolicitud.RECHAZADO) {
            log.info(() -> "Solicitud rechazada; se omite. solicitudId=" + id);
            return Desenlace.OMITIDO;
        }

        if (exigirEscaneoLimpio) {
            ResultadoEscaneo escaneo = almacen.resultadoEscaneo(ruta)
                    .map(ResultadoEscaneo::desdeTexto)
                    .orElse(ResultadoEscaneo.DESCONOCIDO);
            if (escaneo == ResultadoEscaneo.MALICIOSO) {
                repositorio.marcarRechazado(id, "Malware detectado por el antivirus");
                borrarSinFallar(ruta, log);
                return Desenlace.RECHAZADO;
            }
            if (escaneo == ResultadoEscaneo.DESCONOCIDO) {
                log.warning(() -> "Escaneo pendiente o sin resultado; se omite hasta el próximo intento. solicitudId="
                        + id + " rutaBlob=" + ruta);
                return Desenlace.OMITIDO;
            }
        }

        String objectId;
        try {
            repositorio.actualizarEstado(id, EstadoSolicitud.PROCESANDO);
            solicitud = completarCliente(solicitud, log);

            objectId = archivo.buscarPorSolicitud(solicitud).orElse(null);
            if (objectId == null) {
                long tamano = almacen.tamano(ruta);
                try (InputStream contenido = almacen.abrir(ruta)) {
                    objectId = archivo.archivar(solicitud, contenido, tamano, Map.of());
                }
                final String creado = objectId;
                log.info(() -> "Documento creado en Content Manager. solicitudId=" + id + " objectId=" + creado);
            } else {
                final String existente = objectId;
                log.info(() -> "El documento ya existía en Content Manager. solicitudId=" + id
                        + " objectId=" + existente);
            }
            repositorio.marcarArchivado(id, objectId);
        } catch (Exception e) {
            log.log(Level.SEVERE, "Falla al procesar la ingesta. solicitudId=" + id + " rutaBlob=" + ruta, e);
            try {
                repositorio.marcarError(id, describir(e));
            } catch (RuntimeException e2) {
                e.addSuppressed(e2);
            }
            throw new IngestaFallidaException("Falla al procesar la solicitud " + id, e);
        }

        // La fila ya quedó ARCHIVADO: si el borrado falla, lo limpia la política de ciclo de vida.
        borrarSinFallar(ruta, log);
        return Desenlace.ARCHIVADO;
    }

    private SolicitudIngesta completarCliente(SolicitudIngesta solicitud, Logger log) {
        if (!solicitud.tieneIdCliente() || !solicitud.faltaDatosCliente()) {
            return solicitud;
        }
        Optional<Cliente> cliente = resolutor.resolver(solicitud.idCliente());
        if (cliente.isEmpty()) {
            log.warning(() -> "Cliente no encontrado en AS400. solicitudId=" + solicitud.id());
            return solicitud;
        }
        repositorio.registrarCliente(solicitud.id(), cliente.get());
        return solicitud.conCliente(cliente.get());
    }

    private void borrarSinFallar(String ruta, Logger log) {
        try {
            almacen.borrar(ruta);
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "No se pudo borrar el blob; lo limpiará el ciclo de vida. rutaBlob=" + ruta, e);
        }
    }

    private static String describir(Exception e) {
        String mensaje = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
        return mensaje.length() > 4000 ? mensaje.substring(0, 4000) : mensaje;
    }
}
