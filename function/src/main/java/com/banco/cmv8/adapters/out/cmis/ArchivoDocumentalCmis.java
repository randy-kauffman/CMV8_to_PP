package com.banco.cmv8.adapters.out.cmis;

import com.banco.cmv8.domain.DocumentoAArchivar;
import com.banco.cmv8.domain.catalogo.DefinicionTipoCmis;
import com.banco.cmv8.domain.port.ArchivoDocumental;
import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.client.api.ObjectType;
import org.apache.chemistry.opencmis.client.api.QueryResult;
import org.apache.chemistry.opencmis.client.api.Repository;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.SessionFactory;
import org.apache.chemistry.opencmis.client.api.Tree;
import org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.definitions.Choice;
import org.apache.chemistry.opencmis.commons.definitions.DocumentTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyStringDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.BindingType;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.exceptions.CmisBaseException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisConnectionException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;

import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * IBM Content Manager V8 por CMIS 1.1, Browser binding (JSON), con Apache Chemistry OpenCMIS (ADR-003 §4.2 y §6).
 *
 * <p>v2: el tipo, la carpeta y las propiedades llegan en cada {@link DocumentoAArchivar} (catálogo descubierto),
 * no en la configuración. El contenido viaja en streaming desde el InputStream del blob (S4b). El catálogo se
 * descubre con {@code getTypeDescendants} portando las reglas de CMCourier: solo propiedades escribibles y sin
 * {@code cmis:name} ni {@code cmis:objectTypeId}, que los pone este adaptador.
 */
public class ArchivoDocumentalCmis implements ArchivoDocumental {

    private static final Logger LOG = Logger.getLogger(ArchivoDocumentalCmis.class.getName());
    private static final String MIME_POR_DEFECTO = "application/octet-stream";

    /** Las pone el código, nunca el catálogo: si el maker pudiera mapearlas, podría cambiar el tipo o el nombre. */
    static final Set<String> PROPIEDADES_DEL_CODIGO = Set.of(PropertyIds.NAME, PropertyIds.OBJECT_TYPE_ID);

    public record ParametrosCmis(String url, String usuario, String clave, Optional<String> repositorio) {

        public ParametrosCmis {
            repositorio = repositorio == null ? Optional.empty() : repositorio;
        }

        /** Sin la clave. */
        @Override
        public String toString() {
            return "ParametrosCmis[url=" + url + ", usuario=" + usuario + ", repositorio=" + repositorio + "]";
        }
    }

    /** Sesión compartida por instancia del worker (OpenCMIS Session es thread-safe y cachea tipos). */
    private static volatile Session sesion;

    private final ParametrosCmis parametros;

    public ArchivoDocumentalCmis(ParametrosCmis parametros) {
        this.parametros = parametros;
    }

    @Override
    public String archivar(DocumentoAArchivar documento, InputStream contenido, long tamano) {
        return conSesion(s -> {
            Folder carpeta = carpeta(s, documento.carpeta());

            Map<String, Object> propiedades = new HashMap<>(documento.propiedades());
            propiedades.put(PropertyIds.OBJECT_TYPE_ID, documento.tipoCmisId());
            propiedades.put(PropertyIds.NAME, documento.nombre());

            String mime = documento.mimeType() == null || documento.mimeType().isBlank()
                    ? MIME_POR_DEFECTO : documento.mimeType();
            ContentStream flujo = new ContentStreamImpl(documento.nombre(), BigInteger.valueOf(tamano), mime, contenido);
            VersioningState versionado = documento.versionable() ? VersioningState.MAJOR : VersioningState.NONE;

            Document creado = carpeta.createDocument(propiedades, flujo, versionado);
            return creado.getId();
        });
    }

    @Override
    public Optional<String> buscarPorNombre(String tipoCmisId, String carpeta, String nombre) {
        try {
            return conSesion(s -> {
                Folder destino = carpeta(s, carpeta);
                TypeDefinition tipo = s.getTypeDefinition(tipoCmisId);
                String consulta = consultaPorNombre(tipo.getQueryName(), nombre, destino.getId());
                for (QueryResult r : s.query(consulta, false).getPage(1)) {
                    Object id = r.getPropertyValueById(PropertyIds.OBJECT_ID);
                    if (id != null) {
                        return Optional.of(id.toString());
                    }
                }
                return Optional.empty();
            });
        } catch (CmisConnectionException e) {
            throw e;
        } catch (CmisBaseException e) {
            // Mejor esfuerzo (como en v1): si CM no soporta la consulta, se sigue con la creación. Sin el nombre
            // del archivo en el log: puede traer datos del cliente.
            LOG.log(Level.WARNING, "No se pudo consultar CM por nombre en el tipo " + tipoCmisId, e);
            return Optional.empty();
        }
    }

    @Override
    public List<DefinicionTipoCmis> descubrirTipos() {
        return conSesion(s -> {
            List<TypeDefinition> tipos = new ArrayList<>();
            aplanar(s.getTypeDescendants(null, -1, true), tipos);
            List<DefinicionTipoCmis> documentos = new ArrayList<>();
            for (TypeDefinition tipo : tipos) {
                if (esDocumento(tipo)) {
                    documentos.add(convertir(tipo));
                }
            }
            LOG.info(() -> "Descubiertos " + documentos.size() + " tipos de documento de " + tipos.size() + " tipos CMIS");
            return documentos;
        });
    }

    @Override
    public Optional<DefinicionTipoCmis> definicionTipo(String tipoCmisId) {
        return conSesion(s -> {
            try {
                ObjectType tipo = s.getTypeDefinition(tipoCmisId);
                return esDocumento(tipo) ? Optional.of(convertir(tipo)) : Optional.<DefinicionTipoCmis>empty();
            } catch (CmisObjectNotFoundException e) {
                return Optional.empty();
            }
        });
    }

    @Override
    public boolean existeCarpeta(String ruta) {
        return conSesion(s -> {
            try {
                return s.getObjectByPath(ruta) instanceof Folder;
            } catch (CmisObjectNotFoundException e) {
                return false;
            }
        });
    }

    @Override
    public void borrar(String cmisObjectId) {
        conSesion(s -> {
            try {
                s.delete(s.createObjectId(cmisObjectId), true);
            } catch (CmisObjectNotFoundException e) {
                // Compensación idempotente: si ya no está, el objetivo está cumplido.
                LOG.fine(() -> "El documento a compensar ya no existe en CM: " + cmisObjectId);
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ conversión (pura, probada aparte)

    /** Recorre el árbol de {@code typeDescendants} en profundidad, padre antes que hijos. */
    static void aplanar(List<Tree<ObjectType>> arboles, List<TypeDefinition> destino) {
        if (arboles == null) {
            return;
        }
        for (Tree<ObjectType> arbol : arboles) {
            if (arbol.getItem() != null) {
                destino.add(arbol.getItem());
            }
            aplanar(arbol.getChildren(), destino);
        }
    }

    /** Carpetas, relaciones, políticas e items no son destino de archivado: se omiten del catálogo. */
    static boolean esDocumento(TypeDefinition tipo) {
        return tipo != null && tipo.getBaseTypeId() == BaseTypeId.CMIS_DOCUMENT;
    }

    static DefinicionTipoCmis convertir(TypeDefinition tipo) {
        boolean versionable = tipo instanceof DocumentTypeDefinition doc && Boolean.TRUE.equals(doc.isVersionable());
        List<DefinicionTipoCmis.Propiedad> propiedades = new ArrayList<>();
        Map<String, PropertyDefinition<?>> definiciones = tipo.getPropertyDefinitions();
        if (definiciones != null) {
            for (PropertyDefinition<?> definicion : definiciones.values()) {
                if (esEscribible(definicion)) {
                    propiedades.add(convertir(definicion));
                }
            }
        }
        return new DefinicionTipoCmis(tipo.getId(), tipo.getLocalName(), tipo.getDisplayName(), versionable,
                tipo.getParentTypeId(), propiedades);
    }

    /** Regla de CMCourier: solo readwrite u oncreate; las del sistema y las que pone el código, afuera. */
    static boolean esEscribible(PropertyDefinition<?> definicion) {
        if (definicion == null || definicion.getId() == null || PROPIEDADES_DEL_CODIGO.contains(definicion.getId())) {
            return false;
        }
        Updatability u = definicion.getUpdatability();
        return u == Updatability.READWRITE || u == Updatability.ONCREATE;
    }

    static DefinicionTipoCmis.Propiedad convertir(PropertyDefinition<?> definicion) {
        String tipoDato = definicion.getPropertyType() == null
                ? null : definicion.getPropertyType().value().toLowerCase(Locale.ROOT);
        Integer largoMaximo = null;
        if (definicion instanceof PropertyStringDefinition cadena && cadena.getMaxLength() != null) {
            BigInteger maximo = cadena.getMaxLength();
            largoMaximo = maximo.bitLength() < 32 ? maximo.intValue() : Integer.MAX_VALUE;
        }
        List<String> lista = new ArrayList<>();
        aplanarOpciones(definicion.getChoices(), lista);
        return new DefinicionTipoCmis.Propiedad(
                definicion.getId(),
                definicion.getDisplayName(),
                tipoDato,
                Boolean.TRUE.equals(definicion.isRequired()),
                primerDefault(definicion.getDefaultValue()),
                largoMaximo,
                lista,
                Boolean.TRUE.equals(definicion.isInherited()));
    }

    private static String primerDefault(List<?> defaults) {
        if (defaults == null || defaults.isEmpty() || defaults.get(0) == null) {
            return null;
        }
        return comoTexto(defaults.get(0));
    }

    /** Las listas de CMIS pueden ser jerárquicas: se aplanan; cada opción vale por su nombre visible o su valor. */
    private static <T> void aplanarOpciones(List<Choice<T>> opciones, List<String> destino) {
        if (opciones == null) {
            return;
        }
        for (Choice<T> opcion : opciones) {
            if (opcion.getDisplayName() != null && !opcion.getDisplayName().isBlank()) {
                destino.add(opcion.getDisplayName());
            } else if (opcion.getValue() != null && !opcion.getValue().isEmpty() && opcion.getValue().get(0) != null) {
                destino.add(comoTexto(opcion.getValue().get(0)));
            }
            aplanarOpciones(opcion.getChoice(), destino);
        }
    }

    /** Fechas en ISO-8601 UTC (el toString de GregorianCalendar no es legible); el resto, su texto. */
    private static String comoTexto(Object valor) {
        if (valor instanceof Calendar fecha) {
            return fecha.toInstant().toString();
        }
        return valor.toString();
    }

    static String consultaPorNombre(String queryNameTipo, String nombre, String carpetaId) {
        return "SELECT " + PropertyIds.OBJECT_ID + " FROM " + queryNameTipo
                + " WHERE " + PropertyIds.NAME + " = '" + escapar(nombre) + "'"
                + " AND IN_FOLDER('" + escapar(carpetaId) + "')";
    }

    /** Escapa literales de CMIS QL (CMIS 1.1 §2.1.14.2.4.4): barra invertida y comilla simple. */
    static String escapar(String valor) {
        return valor.replace("\\", "\\\\").replace("'", "\\'");
    }

    // ------------------------------------------------------------------ sesión

    private static Folder carpeta(Session s, String ruta) {
        CmisObject objeto = s.getObjectByPath(ruta);
        if (!(objeto instanceof Folder carpeta)) {
            throw new IllegalStateException("La ruta CMIS no es una carpeta: " + ruta);
        }
        return carpeta;
    }

    /** Una caída de conexión descarta la sesión compartida para que la próxima llamada abra una nueva. */
    private <T> T conSesion(Function<Session, T> operacion) {
        try {
            return operacion.apply(sesion());
        } catch (CmisConnectionException e) {
            reiniciarSesion();
            throw e;
        }
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
