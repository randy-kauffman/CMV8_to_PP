package com.banco.cmv8.application;

import com.banco.cmv8.application.catalogo.CatalogoEnCache;
import com.banco.cmv8.application.catalogo.Conflictos;
import com.banco.cmv8.application.catalogo.ResolverPropiedades;
import com.banco.cmv8.domain.Adjunto;
import com.banco.cmv8.domain.ClaveAdjunto;
import com.banco.cmv8.domain.DocumentoAArchivar;
import com.banco.cmv8.domain.EstadoIngesta;
import com.banco.cmv8.domain.MensajeIngesta;
import com.banco.cmv8.domain.RegistroIngesta;
import com.banco.cmv8.domain.ResultadoEscaneo;
import com.banco.cmv8.domain.catalogo.Conflicto;
import com.banco.cmv8.domain.catalogo.DefinicionTipoCmis;
import com.banco.cmv8.domain.catalogo.TipoDocumento;
import com.banco.cmv8.domain.port.AgendaDepuracion;
import com.banco.cmv8.domain.port.AlmacenAdjuntos;
import com.banco.cmv8.domain.port.ArchivoDocumental;
import com.banco.cmv8.domain.port.RegistroIngestas;
import com.banco.cmv8.domain.port.RepositorioAdjuntos;

import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Worker (ADR-003 §4.2): lee el registro operativo y el catálogo en caché (cero requests a Dataverse para
 * decidir), escanea, resuelve y valida las propiedades contra la definición VIVA del tipo antes de tocar
 * Content Manager, y según el tipo archiva, almacena o detiene. Dataverse recibe un solo PATCH con el resultado.
 *
 * <p>Idempotente: descarta Archivado y Rechazado; si el documento ya existe en CM (caída entre archivar y
 * marcar) reutiliza su objectId. Si el PATCH final falla porque la fila ya no existe, borra el documento recién
 * creado en CM (compensación, ADR-003 §9).
 */
public class ProcesarIngesta {

    public enum Desenlace { ARCHIVADO, ALMACENADO, YA_ARCHIVADO, RECHAZADO, OBSERVADA, DETENIDA, OMITIDO }

    private final RegistroIngestas registros;
    private final RepositorioAdjuntos repositorio;
    private final CatalogoEnCache catalogo;
    private final AlmacenAdjuntos almacen;
    private final ArchivoDocumental archivo;
    private final ResolverPropiedades resolver;
    private final Conflictos conflictos;
    private final AgendaDepuracion agenda;
    private final String contenedorIngesta;
    private final String contenedorAlmacenados;
    private final boolean exigirEscaneoLimpio;
    private final boolean wormHabilitado;
    private final boolean wormBloquear;
    private final int observadaDias;
    private final Clock reloj;
    private final Map<String, Set<String>> columnasPorTabla = new ConcurrentHashMap<>();

    public ProcesarIngesta(RegistroIngestas registros, RepositorioAdjuntos repositorio, CatalogoEnCache catalogo,
                           AlmacenAdjuntos almacen, ArchivoDocumental archivo, ResolverPropiedades resolver,
                           Conflictos conflictos, AgendaDepuracion agenda, String contenedorIngesta,
                           String contenedorAlmacenados, boolean exigirEscaneoLimpio, boolean wormHabilitado,
                           boolean wormBloquear, int observadaDias, Clock reloj) {
        this.registros = registros;
        this.repositorio = repositorio;
        this.catalogo = catalogo;
        this.almacen = almacen;
        this.archivo = archivo;
        this.resolver = resolver;
        this.conflictos = conflictos;
        this.agenda = agenda;
        this.contenedorIngesta = contenedorIngesta;
        this.contenedorAlmacenados = contenedorAlmacenados;
        this.exigirEscaneoLimpio = exigirEscaneoLimpio;
        this.wormHabilitado = wormHabilitado;
        this.wormBloquear = wormBloquear;
        this.observadaDias = observadaDias;
        this.reloj = reloj;
    }

    public Desenlace ejecutar(MensajeIngesta mensaje, Logger log) {
        ClaveAdjunto clave = mensaje.clave();
        if (!clave.esSuRuta(mensaje.rutaBlob())) {
            log.warning(() -> "Mensaje descartado: la ruta no pertenece al adjunto. adjunto=" + clave + " ruta=" + mensaje.rutaBlob());
            return Desenlace.OMITIDO;
        }
        Optional<RegistroIngesta> encontrado = registros.obtener(clave);
        if (encontrado.isEmpty()) {
            // Sin registro operativo no hubo SAS del broker: blob ajeno al flujo (o registro perdido). Se omite y
            // queda en el log; el ciclo de vida del contenedor lo limpia.
            log.warning(() -> "Adjunto sin registro operativo; se omite. adjunto=" + clave);
            return Desenlace.OMITIDO;
        }
        RegistroIngesta registro = encontrado.get();
        if (registro.estado().esFinal()) {
            log.info(() -> "Adjunto en estado final " + registro.estado() + "; se omite. adjunto=" + clave);
            if (registro.estado() == EstadoIngesta.ARCHIVADO) {
                borrarSinFallar(mensaje.contenedor(), mensaje.rutaBlob(), log);
                return Desenlace.YA_ARCHIVADO;
            }
            return Desenlace.OMITIDO;
        }
        if (registro.estado() == EstadoIngesta.DETENIDA) {
            log.info(() -> "Adjunto detenido por conflicto de catálogo; no se procesa hasta resolverlo. adjunto=" + clave);
            return Desenlace.DETENIDA;
        }
        String contenedor = mensaje.contenedor();
        String ruta = mensaje.rutaBlob();
        if (!almacen.existe(contenedor, ruta)) {
            log.warning(() -> "El blob no existe; se omite. adjunto=" + clave + " contenedor=" + contenedor);
            return Desenlace.OMITIDO;
        }
        if (exigirEscaneoLimpio && contenedor.equals(contenedorIngesta)) {
            ResultadoEscaneo escaneo = almacen.resultadoEscaneo(contenedor, ruta)
                    .map(ResultadoEscaneo::desdeTexto).orElse(ResultadoEscaneo.DESCONOCIDO);
            if (escaneo == ResultadoEscaneo.MALICIOSO) {
                return rechazar(registro, "Malware detectado por el antivirus", contenedor, ruta, log);
            }
            if (escaneo == ResultadoEscaneo.DESCONOCIDO) {
                log.warning(() -> "Escaneo pendiente; se omite hasta el próximo intento. adjunto=" + clave);
                return Desenlace.OMITIDO;
            }
        }
        Optional<TipoDocumento> tipoOpt = catalogo.tipo(registro.tipoDocumentoId());
        if (tipoOpt.isEmpty()) {
            return observar(registro, List.of("El adjunto no tiene un tipo de documento válido del catálogo"), log);
        }
        TipoDocumento tipo = tipoOpt.get();

        registro = registro.intentoMas(reloj.instant());
        registros.guardar(registro);
        try {
            if (tipo.tieneItemType()) {
                // Valida AHORA aunque el archivado sea diferido: el usuario se entera hoy, no en meses (M6).
                ResolverPropiedades.Resultado r = resolver.resolver(tipo, clave, registro.valores(),
                        registro.nombreArchivo(), registro.contentType(), registro.tamanoBytes(),
                        columnas(clave.tabla()), archivo.definicionTipo(tipo.tipoCmisId()));
                if (tipo.carpeta() != null && !tipo.carpeta().isBlank() && !archivo.existeCarpeta(tipo.carpeta())) {
                    r.conflictos().add(Conflicto.bloqueante(Conflicto.Clase.CARPETA_INEXISTENTE, tipo.id(), null,
                            "La carpeta " + tipo.carpeta() + " no existe en Content Manager"));
                }
                r.avisos().forEach(a -> conflictos.registrar(a, log));
                if (r.bloqueada()) {
                    return detener(registro, r.conflictos().stream().filter(Conflicto::bloquea).toList(), log);
                }
                if (r.observada()) {
                    return observar(registro, r.observaciones(), log);
                }
                if (tipo.vaAContentManager() && !tipo.esDiferido() && contenedor.equals(contenedorIngesta)) {
                    return archivar(registro, tipo, r.propiedades(), contenedor, ruta, log);
                }
                if (tipo.vaAContentManager() && contenedor.equals(contenedorAlmacenados)) {
                    // Archivar explícito de un diferido (o promoción): ya está en almacenados.
                    return archivar(registro, tipo, r.propiedades(), contenedor, ruta, log);
                }
            }
            // Solo blob, o diferido en espera: se queda en almacenados.
            return almacenar(registro, tipo, contenedor, ruta, log);
        } catch (IngestaFallidaException e) {
            throw e;
        } catch (Exception e) {
            log.log(Level.SEVERE, "Falla al procesar la ingesta. adjunto=" + clave, e);
            marcarError(registro, describir(e), log);
            throw new IngestaFallidaException("Falla al procesar el adjunto " + clave, e);
        }
    }

    // ------------------------------------------------------------------ desenlaces

    private Desenlace archivar(RegistroIngesta registro, TipoDocumento tipo, Map<String, Object> propiedades,
                               String contenedor, String ruta, Logger log) throws Exception {
        ClaveAdjunto clave = registro.clave();
        if (tipo.carpeta() == null || tipo.carpeta().isBlank()) {
            throw new IllegalStateException("El tipo " + tipo.nombre() + " no tiene carpeta destino en Content Manager");
        }
        String nombre = clave.id() + "_" + registro.nombreArchivo();
        String objectId = archivo.buscarPorNombre(tipo.tipoCmisId(), tipo.carpeta(), nombre).orElse(null);
        boolean creadoAhora = false;
        if (objectId == null) {
            long tamano = almacen.tamano(contenedor, ruta);
            try (InputStream contenido = almacen.abrir(contenedor, ruta)) {
                objectId = archivo.archivar(new DocumentoAArchivar(tipo.tipoCmisId(), tipo.carpeta(), nombre,
                        registro.contentType(), tipo.versionable(), propiedades), contenido, tamano);
            }
            creadoAhora = true;
            final String creado = objectId;
            log.info(() -> "Documento creado en Content Manager. adjunto=" + clave + " objectId=" + creado);
        } else {
            final String existente = objectId;
            log.info(() -> "El documento ya existía en Content Manager. adjunto=" + clave + " objectId=" + existente);
        }

        String rutaFinal = null;
        String contenedorFinal = contenedor;
        LocalDate depuracion = null;
        if (tipo.retieneTrasArchivar()) {
            if (contenedor.equals(contenedorIngesta)) {
                almacen.mover(contenedorIngesta, ruta, contenedorAlmacenados, ruta);
            }
            contenedorFinal = contenedorAlmacenados;
            rutaFinal = ruta;
            depuracion = fechaDepuracion(tipo);
            aplicarInmutabilidad(tipo, rutaFinal, depuracion, log);
        }
        Map<String, Object> cambios = new LinkedHashMap<>();
        cambios.put(Adjunto.COL_ESTADO, EstadoIngesta.ARCHIVADO);
        cambios.put(Adjunto.COL_CMIS, objectId);
        cambios.put(Adjunto.COL_RUTA, rutaFinal);
        cambios.put(Adjunto.COL_MENSAJE, null);
        try {
            repositorio.actualizar(clave, cambios);
        } catch (SolicitudNoEncontradaException | AccesoDenegadoException e) {
            // La fila desapareció o cambió en la ventana entre la SAS y el proceso: no debe quedar el documento en CM.
            if (creadoAhora) {
                compensar(objectId, clave, log);
            }
            throw e;
        }
        RegistroIngesta nuevo = registro.conEstado(EstadoIngesta.ARCHIVADO).conCmisObjectId(objectId)
                .conUbicacion(contenedorFinal, rutaFinal == null ? ruta : rutaFinal).conFechaDepuracion(depuracion)
                .conMensaje(null);
        registros.guardar(nuevo);
        if (depuracion != null) {
            agenda.programar(depuracion, clave);
        }
        if (rutaFinal == null) {
            borrarSinFallar(contenedor, ruta, log);
        }
        return Desenlace.ARCHIVADO;
    }

    private Desenlace almacenar(RegistroIngesta registro, TipoDocumento tipo, String contenedor, String ruta, Logger log) {
        ClaveAdjunto clave = registro.clave();
        if (contenedor.equals(contenedorIngesta)) {
            almacen.mover(contenedorIngesta, ruta, contenedorAlmacenados, ruta);
        }
        // Diferido en espera: sin fecha hasta que se archive. Solo-blob: la retención del tipo.
        LocalDate depuracion = tipo.esDiferido() ? null : fechaDepuracion(tipo);
        if (!tipo.esDiferido()) {
            // Un diferido inmutable recibe la política al archivarse, cuando ya tiene fecha.
            aplicarInmutabilidad(tipo, ruta, depuracion, log);
        }
        Map<String, Object> cambios = new LinkedHashMap<>();
        cambios.put(Adjunto.COL_ESTADO, EstadoIngesta.ALMACENADO);
        cambios.put(Adjunto.COL_RUTA, ruta);
        cambios.put(Adjunto.COL_MENSAJE, null);
        repositorio.actualizar(clave, cambios);
        RegistroIngesta nuevo = registro.conEstado(EstadoIngesta.ALMACENADO).conUbicacion(contenedorAlmacenados, ruta)
                .conFechaDepuracion(depuracion).conMensaje(null);
        registros.guardar(nuevo);
        if (depuracion != null) {
            agenda.programar(depuracion, clave);
        }
        log.info(() -> "Adjunto almacenado en Blob. adjunto=" + clave + " diferido=" + tipo.esDiferido()
                + " depuracion=" + depuracion);
        return Desenlace.ALMACENADO;
    }

    private Desenlace observar(RegistroIngesta registro, List<String> observaciones, Logger log) {
        ClaveAdjunto clave = registro.clave();
        String detalle = String.join("; ", observaciones);
        LocalDate vence = LocalDate.now(reloj).plusDays(observadaDias);
        Map<String, Object> cambios = new LinkedHashMap<>();
        cambios.put(Adjunto.COL_ESTADO, EstadoIngesta.OBSERVADA);
        cambios.put(Adjunto.COL_MENSAJE, detalle);
        repositorio.actualizar(clave, cambios);
        registros.guardar(registro.conEstado(EstadoIngesta.OBSERVADA).conMensaje(detalle).conFechaDepuracion(vence));
        agenda.programar(vence, clave);
        log.info(() -> "Adjunto observado; espera corrección del usuario hasta " + vence + ". adjunto=" + clave
                + " detalle=" + detalle);
        return Desenlace.OBSERVADA;
    }

    private Desenlace detener(RegistroIngesta registro, List<Conflicto> bloqueantes, Logger log) {
        ClaveAdjunto clave = registro.clave();
        Conflicto principal = bloqueantes.get(0);
        UUID conflictoId = conflictos.registrarConDetenido(principal, log);
        for (Conflicto otro : bloqueantes.subList(1, bloqueantes.size())) {
            conflictos.registrar(otro, log);
        }
        String detalle = "Conflicto de catálogo: " + principal.detalle() + ". Ya avisamos al administrador; no necesitas hacer nada.";
        Map<String, Object> cambios = new LinkedHashMap<>();
        cambios.put(Adjunto.COL_ESTADO, EstadoIngesta.DETENIDA);
        cambios.put(Adjunto.COL_MENSAJE, detalle);
        repositorio.actualizar(clave, cambios);
        registros.guardar(registro.conEstado(EstadoIngesta.DETENIDA).conMensaje(detalle).conConflicto(principal.clave()));
        log.warning(() -> "Adjunto detenido por conflicto de catálogo. adjunto=" + clave + " conflictoId=" + conflictoId);
        return Desenlace.DETENIDA;
    }

    private Desenlace rechazar(RegistroIngesta registro, String motivo, String contenedor, String ruta, Logger log) {
        ClaveAdjunto clave = registro.clave();
        Map<String, Object> cambios = new LinkedHashMap<>();
        cambios.put(Adjunto.COL_ESTADO, EstadoIngesta.RECHAZADO);
        cambios.put(Adjunto.COL_MENSAJE, motivo);
        cambios.put(Adjunto.COL_RUTA, null);
        repositorio.actualizar(clave, cambios);
        registros.guardar(registro.conEstado(EstadoIngesta.RECHAZADO).conMensaje(motivo));
        borrarSinFallar(contenedor, ruta, log);
        log.warning(() -> "Adjunto rechazado. adjunto=" + clave + " motivo=" + motivo);
        return Desenlace.RECHAZADO;
    }

    private void marcarError(RegistroIngesta registro, String mensaje, Logger log) {
        // Por separado: si la fila de Dataverse ya no existe (compensación), el registro operativo igual tiene que
        // salir de Procesando, o el Watchdog lo reencolaría para siempre (ADR-003 §4.2).
        try {
            Map<String, Object> cambios = new LinkedHashMap<>();
            cambios.put(Adjunto.COL_ESTADO, EstadoIngesta.ERROR);
            cambios.put(Adjunto.COL_MENSAJE, mensaje);
            repositorio.actualizar(registro.clave(), cambios);
        } catch (RuntimeException e2) {
            log.log(Level.WARNING, "No se pudo marcar el error en Dataverse. adjunto=" + registro.clave(), e2);
        }
        try {
            registros.guardar(registro.conEstado(EstadoIngesta.ERROR).conMensaje(mensaje));
        } catch (RuntimeException e2) {
            log.log(Level.WARNING, "No se pudo marcar el error en el registro operativo. adjunto=" + registro.clave(), e2);
        }
    }

    // ------------------------------------------------------------------ apoyo

    private LocalDate fechaDepuracion(TipoDocumento tipo) {
        return tipo.retencion().map(d -> LocalDate.now(reloj).plusDays(d)).orElse(null);
    }

    private void aplicarInmutabilidad(TipoDocumento tipo, String ruta, LocalDate hasta, Logger log) {
        if (!tipo.inmutable()) {
            return;
        }
        if (!wormHabilitado) {
            log.warning(() -> "El tipo " + tipo.nombre() + " es inmutable pero WORM_HABILITADO=false; se omite la política");
            return;
        }
        if (hasta == null) {
            throw new IllegalStateException("El tipo " + tipo.nombre() + " es inmutable y no tiene días de retención");
        }
        almacen.ponerInmutable(contenedorAlmacenados, ruta, hasta.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                wormBloquear);
    }

    private void compensar(String objectId, ClaveAdjunto clave, Logger log) {
        try {
            archivo.borrar(objectId);
            log.warning(() -> "Documento borrado de Content Manager porque la fila ya no admite el resultado. adjunto="
                    + clave + " objectId=" + objectId);
        } catch (RuntimeException e) {
            log.log(Level.SEVERE, "No se pudo compensar en Content Manager. adjunto=" + clave + " objectId=" + objectId, e);
        }
    }

    /**
     * Metadata de la tabla, una vez por tabla por instancia. No rompe "cero requests para decidir": Learn excluye
     * las operaciones de metadata del sistema del entitlement de Power Platform requests.
     */
    private Set<String> columnas(String tabla) {
        Set<String> enCache = columnasPorTabla.get(tabla);
        if (enCache != null) {
            return enCache;
        }
        try {
            Set<String> leidas = repositorio.columnas(tabla);
            if (!leidas.isEmpty()) {
                columnasPorTabla.put(tabla, leidas);
            }
            return leidas;
        } catch (RuntimeException e) {
            // Sin metadata no se distingue "columna inexistente" de "vacía"; la falla no se cachea.
            return Set.of();
        }
    }

    private void borrarSinFallar(String contenedor, String ruta, Logger log) {
        try {
            almacen.borrar(contenedor, ruta);
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "No se pudo borrar el blob; lo limpiará el ciclo de vida. ruta=" + ruta, e);
        }
    }

    private static String describir(Exception e) {
        String mensaje = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
        return mensaje.length() > 4000 ? mensaje.substring(0, 4000) : mensaje;
    }
}
