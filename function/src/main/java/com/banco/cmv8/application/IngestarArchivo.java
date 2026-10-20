package com.banco.cmv8.application;

import com.banco.cmv8.application.catalogo.CatalogoEnCache;
import com.banco.cmv8.domain.Adjunto;
import com.banco.cmv8.domain.ClaveAdjunto;
import com.banco.cmv8.domain.ContextoLlamador;
import com.banco.cmv8.domain.EstadoIngesta;
import com.banco.cmv8.domain.MensajeIngesta;
import com.banco.cmv8.domain.PoliticaSas;
import com.banco.cmv8.domain.RegistroIngesta;
import com.banco.cmv8.domain.ValidacionException;
import com.banco.cmv8.domain.catalogo.TipoDocumento;
import com.banco.cmv8.domain.port.AlmacenAdjuntos;
import com.banco.cmv8.domain.port.ColaIngesta;
import com.banco.cmv8.domain.port.RegistroIngestas;
import com.banco.cmv8.domain.port.RepositorioAdjuntos;

import java.io.InputStream;
import java.time.Clock;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Ingesta desde Power Automate (ADR-003 §4.7): el flujo creó la fila y manda el contenido; el broker suplanta
 * al usuario de la conexión (una request), escribe el blob con la identidad administrada y encola. Desvío
 * declarado del ADR-002: los bytes pasan por la Function. Límite de 100 MB por acción de flujo (Learn).
 */
public class IngestarArchivo {

    private final PoliticaSas politica;
    private final RepositorioAdjuntos repositorio;
    private final RegistroIngestas registros;
    private final CatalogoEnCache catalogo;
    private final AlmacenAdjuntos almacen;
    private final String contenedorIngesta;
    private final Clock reloj;

    public IngestarArchivo(PoliticaSas politica, RepositorioAdjuntos repositorio, RegistroIngestas registros,
                           CatalogoEnCache catalogo, AlmacenAdjuntos almacen, String contenedorIngesta, Clock reloj) {
        this.politica = politica;
        this.repositorio = repositorio;
        this.registros = registros;
        this.catalogo = catalogo;
        this.almacen = almacen;
        this.contenedorIngesta = contenedorIngesta;
        this.reloj = reloj;
    }

    public String ejecutar(ClaveAdjunto clave, String nombreArchivo, String contentType, long tamano,
                           InputStream contenido, ContextoLlamador llamador, ColaIngesta cola, Logger log) {
        if (llamador.objectId().isEmpty()) {
            throw new NoAutenticadoException("La ingesta requiere la identidad Entra de la conexión (falta el oid)");
        }
        politica.validarTamano(tamano);
        String ruta = politica.construirRuta(clave, nombreArchivo);
        Adjunto adjunto = repositorio.registrarRutaSuplantando(clave, ruta, llamador);
        if (!adjunto.estado().admiteSubida()) {
            throw new EstadoInvalidoException("El adjunto está en estado " + adjunto.estado() + " y no admite una subida");
        }
        TipoDocumento tipo = catalogo.tipo(adjunto.tipoDocumentoId())
                .orElseThrow(() -> new ValidacionException("El adjunto no tiene un tipo de documento válido"));
        if (!tipo.enUso()) {
            throw new ValidacionException("El tipo de documento " + tipo.nombre() + " no está en uso");
        }
        String mime = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
        almacen.escribir(contenedorIngesta, ruta, contenido, tamano, mime);
        // Nombre, tamaño y tipo los manda el flujo (la fila puede no tenerlos todavía): el worker nombra el
        // documento de CM con el nombre del registro, que debe coincidir con lo que se escribe en Dataverse.
        RegistroIngesta inicial = RegistroIngesta.alEmitirSas(adjunto, contenedorIngesta, ruta,
                llamador.objectId().orElse(null), reloj.instant());
        RegistroIngesta registro = new RegistroIngesta(inicial.clave(), inicial.estado(), inicial.tipoDocumentoId(),
                inicial.contenedor(), inicial.ruta(), inicial.solicitanteOid(), nombreArchivo, tamano, mime,
                inicial.valores(), null, 0, null, null, null, inicial.sasEmitidaEn(), null, null)
                .subido(reloj.instant());
        registros.guardar(registro);
        repositorio.actualizar(clave, Map.of(Adjunto.COL_ESTADO, EstadoIngesta.SUBIDO,
                Adjunto.COL_NOMBRE_ARCHIVO, nombreArchivo, Adjunto.COL_TAMANO, tamano, Adjunto.COL_CONTENT_TYPE, mime));
        // El blob escrito por la identidad administrada también dispara BlobCreated; encolar acá hace la ingesta
        // inmediata y el worker es idempotente ante el segundo mensaje.
        cola.encolar(new MensajeIngesta(clave, contenedorIngesta, ruta, "flujo"));
        log.info(() -> "Archivo ingresado desde un flujo. adjunto=" + clave + " ruta=" + ruta + " bytes=" + tamano
                + " llamador=" + llamador);
        return ruta;
    }
}
