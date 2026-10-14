package com.banco.cmv8.adapters.out.cmis;

import com.banco.cmv8.domain.SolicitudIngesta;
import com.banco.cmv8.domain.port.ArchivoDocumental;
import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.client.api.QueryResult;
import org.apache.chemistry.opencmis.client.api.Repository;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.SessionFactory;
import org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.definitions.DocumentTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.BindingType;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.exceptions.CmisBaseException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisConnectionException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;

import java.io.InputStream;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * IBM Content Manager V8 por CMIS 1.1, Browser binding (JSON), con Apache Chemistry OpenCMIS.
 * El contenido viaja en streaming desde el InputStream del blob (S4b).
 *
 * <p>Nombre del documento: si CMIS_PROPIEDADES_JSON mapea alguna propiedad a ${solicitudId}, el nombre es
 * el nombre de archivo y la idempotencia se resuelve consultando esa propiedad. Si no, el nombre lleva el
 * solicitudId como prefijo para que la búsqueda por nombre no confunda archivos homónimos de otras solicitudes.
 */
public class ArchivoDocumentalCmis implements ArchivoDocumental {

    private static final Logger LOG = Logger.getLogger(ArchivoDocumentalCmis.class.getName());
    private static final String MIME_POR_DEFECTO = "application/octet-stream";

    public record ParametrosCmis(String url, String usuario, String clave, Optional<String> repositorio,
                                 String carpeta, String tipo) {

        @Override
        public String toString() {
            return "ParametrosCmis[url=" + url + ", usuario=" + usuario + ", repositorio=" + repositorio
                    + ", carpeta=" + carpeta + ", tipo=" + tipo + "]";
        }
    }

    /** Sesión compartida por instancia del worker (OpenCMIS Session es thread-safe). */
    private static volatile Session sesion;

    private final ParametrosCmis parametros;
    private final MapeoPropiedadesCmis mapeo;
    private volatile Boolean tipoVersionable;

    public ArchivoDocumentalCmis(ParametrosCmis parametros, MapeoPropiedadesCmis mapeo) {
        this.parametros = parametros;
        this.mapeo = mapeo;
    }

    @Override
    public String archivar(SolicitudIngesta solicitud, InputStream contenido, long tamano,
                           Map<String, Object> propiedadesAdicionales) {
        try {
            Session s = sesion();
            Folder carpeta = carpeta(s);

            Map<String, Object> propiedades = new HashMap<>(mapeo.resolver(solicitud));
            propiedades.putAll(propiedadesAdicionales);
            propiedades.put(PropertyIds.OBJECT_TYPE_ID, parametros.tipo());
            String nombre = nombreDocumento(solicitud);
            propiedades.put(PropertyIds.NAME, nombre);

            String mime = solicitud.contentType() == null || solicitud.contentType().isBlank()
                    ? MIME_POR_DEFECTO : solicitud.contentType();
            ContentStream flujo = new ContentStreamImpl(nombre, BigInteger.valueOf(tamano), mime, contenido);
            VersioningState versionado = esVersionable(s) ? VersioningState.MAJOR : VersioningState.NONE;

            Document documento = carpeta.createDocument(propiedades, flujo, versionado);
            return documento.getId();
        } catch (CmisConnectionException e) {
            reiniciarSesion();
            throw e;
        }
    }

    @Override
    public Optional<String> buscarPorSolicitud(SolicitudIngesta solicitud) {
        try {
            Session s = sesion();
            Folder carpeta = carpeta(s);
            TypeDefinition tipo = s.getTypeDefinition(parametros.tipo());
            String condicion = mapeo.propiedadSolicitud()
                    .map(p -> nombreConsulta(tipo, p) + " = '" + escapar(solicitud.id().toString()) + "'")
                    .orElseGet(() -> PropertyIds.NAME + " = '" + escapar(nombreDocumento(solicitud)) + "'");
            String consulta = "SELECT " + PropertyIds.OBJECT_ID + " FROM " + tipo.getQueryName()
                    + " WHERE " + condicion + " AND IN_FOLDER('" + escapar(carpeta.getId()) + "')";
            for (QueryResult r : s.query(consulta, false).getPage(1)) {
                Object id = r.getPropertyValueById(PropertyIds.OBJECT_ID);
                if (id != null) {
                    return Optional.of(id.toString());
                }
            }
            return Optional.empty();
        } catch (CmisConnectionException e) {
            reiniciarSesion();
            throw e;
        } catch (CmisBaseException e) {
            // Búsqueda de mejor esfuerzo: si CM no soporta la consulta, se sigue con la creación.
            LOG.log(Level.WARNING, "No se pudo consultar CM por la solicitud " + solicitud.id(), e);
            return Optional.empty();
        }
    }

    private String nombreDocumento(SolicitudIngesta solicitud) {
        String archivo = solicitud.nombreArchivo() == null || solicitud.nombreArchivo().isBlank()
                ? "documento" : solicitud.nombreArchivo();
        return mapeo.propiedadSolicitud().isPresent() ? archivo : solicitud.id() + "_" + archivo;
    }

    /** queryName de la propiedad según la definición del tipo; si no la expone, el id. */
    private static String nombreConsulta(TypeDefinition tipo, String propiedadId) {
        Map<String, PropertyDefinition<?>> definiciones = tipo.getPropertyDefinitions();
        PropertyDefinition<?> definicion = definiciones == null ? null : definiciones.get(propiedadId);
        return definicion != null && definicion.getQueryName() != null ? definicion.getQueryName() : propiedadId;
    }

    private Folder carpeta(Session s) {
        CmisObject objeto = s.getObjectByPath(parametros.carpeta());
        if (!(objeto instanceof Folder carpeta)) {
            throw new IllegalStateException("CMIS_FOLDER_PATH no es una carpeta: " + parametros.carpeta());
        }
        return carpeta;
    }

    private boolean esVersionable(Session s) {
        Boolean cache = tipoVersionable;
        if (cache == null) {
            TypeDefinition tipo = s.getTypeDefinition(parametros.tipo());
            cache = tipo instanceof DocumentTypeDefinition doc && Boolean.TRUE.equals(doc.isVersionable());
            tipoVersionable = cache;
        }
        return cache;
    }

    /** Escapa literales de CMIS QL: barra invertida y comilla simple. */
    static String escapar(String valor) {
        return valor.replace("\\", "\\\\").replace("'", "\\'");
    }

    private Session sesion() {
        Session actual = sesion;
        if (actual == null) {
            synchronized (ArchivoDocumentalCmis.class) {
                actual = sesion;
                if (actual == null) {
                    actual = crearSesion();
                    sesion = actual;
                }
            }
        }
        return actual;
    }

    private static void reiniciarSesion() {
        synchronized (ArchivoDocumentalCmis.class) {
            sesion = null;
        }
    }

    private Session crearSesion() {
        Map<String, String> p = new HashMap<>();
        p.put(SessionParameter.BINDING_TYPE, BindingType.BROWSER.value());
        p.put(SessionParameter.BROWSER_URL, parametros.url());
        p.put(SessionParameter.USER, parametros.usuario());
        p.put(SessionParameter.PASSWORD, parametros.clave());
        p.put(SessionParameter.CONNECT_TIMEOUT, "30000");
        // Lectura larga: 100 MB hacia CM por un túnel puede tardar varios minutos.
        p.put(SessionParameter.READ_TIMEOUT, "1800000");

        SessionFactory fabrica = SessionFactoryImpl.newInstance();
        if (parametros.repositorio().isPresent()) {
            p.put(SessionParameter.REPOSITORY_ID, parametros.repositorio().get());
            LOG.info(() -> "Abriendo sesión CMIS. " + parametros);
            return fabrica.createSession(p);
        }
        List<Repository> repositorios = fabrica.getRepositories(p);
        if (repositorios.isEmpty()) {
            throw new IllegalStateException("El servidor CMIS no expone repositorios");
        }
        LOG.info(() -> "Abriendo sesión CMIS con el primer repositorio (" + repositorios.get(0).getId() + "). "
                + parametros);
        return repositorios.get(0).createSession();
    }
}
