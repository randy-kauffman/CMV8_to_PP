package com.banco.cmv8.adapters.out.dataverse;

import com.banco.cmv8.config.Json;
import com.banco.cmv8.domain.catalogo.Conflicto;
import com.banco.cmv8.domain.catalogo.ConflictoRegistrado;
import com.banco.cmv8.domain.catalogo.PropiedadCm;
import com.banco.cmv8.domain.catalogo.TipoDocumento;
import com.banco.cmv8.domain.port.CatalogoTipos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;
import java.util.logging.Logger;

import static com.banco.cmv8.adapters.out.dataverse.NombresDataverse.*;

/**
 * Catálogo de la solución base (ADR-003 §1.2) vía Web API con el usuario de aplicación. Lo descubierto lo escribe
 * este adaptador; lo humano (en uso, destino, momento, retención, inmutable, origen del valor) nunca se toca, así
 * una actualización desde Content Manager no pisa decisiones del administrador.
 */
public class CatalogoTiposDataverse implements CatalogoTipos {

    private static final Logger LOG = Logger.getLogger(CatalogoTiposDataverse.class.getName());

    private static final String EXPAND_PROPIEDADES =
            "$expand=" + NAV_TIPO_PROPIEDADES + "($select=" + SELECT_PROPIEDAD + ")";

    private final ClienteDataverse cliente;
    private final Clock reloj;
    private final Map<String, String> conjuntoPorTabla = new ConcurrentHashMap<>();

    public CatalogoTiposDataverse(ClienteDataverse cliente) {
        this(cliente, Clock.systemUTC());
    }

    public CatalogoTiposDataverse(ClienteDataverse cliente, Clock reloj) {
        this.cliente = Objects.requireNonNull(cliente, "cliente");
        this.reloj = Objects.requireNonNull(reloj, "reloj");
    }

    // ---------- tipos ----------

    @Override
    public List<TipoDocumento> listar() {
        List<TipoDocumento> tipos = new ArrayList<>();
        String ruta = conjunto(TABLA_TIPO) + "?$select=" + SELECT_TIPO + "&" + EXPAND_PROPIEDADES;
        while (ruta != null) {
            JsonNode pagina = leer(ClienteDataverse.exigirExito(cliente.get(ruta, Map.of()), "Listar tipos"));
            for (JsonNode n : pagina.path("value")) {
                tipos.add(mapearTipo(n));
            }
            ruta = siguientePagina(pagina);
        }
        return tipos;
    }

    @Override
    public Optional<TipoDocumento> obtener(UUID id) {
        Objects.requireNonNull(id, "id");
        ClienteDataverse.Respuesta r = cliente.get(
                conjunto(TABLA_TIPO) + "(" + id + ")?$select=" + SELECT_TIPO + "&" + EXPAND_PROPIEDADES, Map.of());
        if (r.estado() == 404) {
            return Optional.empty();
        }
        return Optional.of(mapearTipo(leer(ClienteDataverse.exigirExito(r, "Leer tipo " + id))));
    }

    @Override
    public UUID guardarTipoDescubierto(TipoDocumento tipo, List<String> cambios, boolean esNuevo) {
        Objects.requireNonNull(tipo, "tipo");
        String textoCambios = cambios == null || cambios.isEmpty()
                ? null : RepositorioAdjuntosDataverse.recortar(String.join("\n", cambios), LARGO_MEMO);
        String ahora = iso(reloj.instant());
        ObjectNode cuerpo = Json.MAPPER.createObjectNode()
                .put(TIPO_NOMBRE, RepositorioAdjuntosDataverse.recortar(tipo.nombre(), LARGO_NOMBRE))
                .put(TIPO_LOCAL_NAME, tipo.localName())
                .put(TIPO_VERSIONABLE, tipo.versionable())
                .put(TIPO_AUSENTE, tipo.ausenteEnServidor()) // DescubrirTipos marca lo que el servidor dejó de publicar
                .put(TIPO_CAMBIOS, textoCambios)
                .put(TIPO_DESCUBIERTO_EN, ahora);
        if (esNuevo) {
            cuerpo.put(TIPO_CODIGO, tipo.codigo())
                    .put(TIPO_ORIGEN, TipoDocumento.OrigenTipo.DESCUBIERTO.codigo)
                    .put(TIPO_CMIS_ID, tipo.tipoCmisId())
                    .put(TIPO_REVISADO, false)
                    .put(TIPO_CARPETA, tipo.carpeta())
                    .put(TIPO_ORIGEN_CARPETA, ORIGEN_CARPETA_DERIVADA)
                    .put(TIPO_DESTINO, TipoDocumento.Destino.CONTENT_MANAGER.codigo)
                    .put(TIPO_MOMENTO, TipoDocumento.Momento.AL_SUBIR.codigo);
            UUID id = crear(TABLA_TIPO, TIPO_ID, cuerpo, "Crear tipo " + tipo.tipoCmisId());
            LOG.info(() -> "Tipo descubierto creado. id=" + id);
            return id;
        }
        if (tipo.id() == null) {
            throw new IllegalArgumentException("Un tipo existente necesita id para actualizarse");
        }
        cuerpo.put(TIPO_REVISADO, tipo.revisado());
        if (!tipo.carpetaManual()) {
            cuerpo.put(TIPO_CARPETA, tipo.carpeta()); // una carpeta manual no se pisa (ADR-003 §6)
        }
        ClienteDataverse.exigirExito(cliente.patch(conjunto(TABLA_TIPO) + "(" + tipo.id() + ")", cuerpo.toString()),
                "Actualizar tipo " + tipo.id());
        return tipo.id();
    }

    // ---------- propiedades ----------

    @Override
    public void guardarPropiedadesDescubiertas(UUID tipoId, List<PropiedadCm> propiedades) {
        Objects.requireNonNull(tipoId, "tipoId");
        if (propiedades == null) {
            return;
        }
        for (PropiedadCm p : propiedades) {
            ObjectNode cuerpo = columnasDescubiertas(p);
            Optional<UUID> existente = buscarPropiedad(tipoId, p.propiedadId());
            if (existente.isPresent()) {
                ClienteDataverse.exigirExito(
                        cliente.patch(conjunto(TABLA_PROPIEDAD) + "(" + existente.get() + ")", cuerpo.toString()),
                        "Actualizar propiedad " + existente.get());
            } else {
                // La decisión solo se escribe al crear (sugerida): después es del administrador.
                String nombre = p.nombre() != null ? p.nombre() : PropiedadCm.nombreCanonico(p.propiedadId());
                cuerpo.put(PROP_NOMBRE, RepositorioAdjuntosDataverse.recortar(nombre, LARGO_NOMBRE))
                        .put(PROP_PROPIEDAD_ID, p.propiedadId())
                        .put(PROP_DECISION, p.decision().codigo)
                        .put(PROP_MOMENTO_VALIDACION, PropiedadCm.MomentoValidacion.AL_ARCHIVAR.codigo)
                        .put(NAV_TIPO_DOCUMENTO + "@odata.bind", "/" + conjunto(TABLA_TIPO) + "(" + tipoId + ")");
                crear(TABLA_PROPIEDAD, PROP_ID, cuerpo, "Crear propiedad del tipo " + tipoId);
            }
        }
    }

    /**
     * Solo lo que publica Content Manager. {@code cmv8_nombrevisible} no se escribe porque {@link PropiedadCm}
     * no trae el displayName de CMIS.
     */
    private static ObjectNode columnasDescubiertas(PropiedadCm p) {
        ObjectNode cuerpo = Json.MAPPER.createObjectNode()
                .put(PROP_TIPO_DATO, p.tipoDato())
                .put(PROP_OBLIGATORIA, p.obligatoria())
                .put(PROP_VALOR_DEFECTO, RepositorioAdjuntosDataverse.recortar(p.valorPorDefecto(), LARGO_TEXTO_400))
                .put(PROP_HEREDADA, p.heredada())
                .put(PROP_LISTA_VALORES, p.listaValores().isEmpty()
                        ? null : RepositorioAdjuntosDataverse.recortar(String.join("\n", p.listaValores()), LARGO_MEMO));
        if (p.largoMaximo() == null) {
            cuerpo.putNull(PROP_LARGO_MAXIMO);
        } else {
            cuerpo.put(PROP_LARGO_MAXIMO, p.largoMaximo().intValue());
        }
        return cuerpo;
    }

    private Optional<UUID> buscarPropiedad(UUID tipoId, String propiedadId) {
        String filtro = PROP_PROPIEDAD_ID + " eq '" + propiedadId.replace("'", "''") + "' and "
                + PROP_TIPO_VALOR + " eq " + tipoId;
        String ruta = conjunto(TABLA_PROPIEDAD) + "?$select=" + PROP_ID
                + "&$filter=" + ClienteDataverse.codificar(filtro) + "&$top=1";
        JsonNode valores = leer(ClienteDataverse.exigirExito(cliente.get(ruta, Map.of()),
                "Buscar propiedad del tipo " + tipoId)).path("value");
        if (!valores.isArray() || valores.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(uuid(valores.get(0), PROP_ID));
    }

    // ---------- conflictos ----------

    @Override
    public UUID registrarConflicto(Conflicto conflicto, int adjuntosDetenidos) {
        Objects.requireNonNull(conflicto, "conflicto");
        String resumen = conflicto.clase().name()
                + conflicto.propiedad().map(p -> " · " + PropiedadCm.nombreCanonico(p)).orElse("");
        ObjectNode cuerpo = Json.MAPPER.createObjectNode()
                .put(CONF_NOMBRE, RepositorioAdjuntosDataverse.recortar(resumen, LARGO_NOMBRE))
                .put(CONF_CLASE, conflicto.clase().codigo)
                .put(CONF_SEVERIDAD, conflicto.severidad().codigo)
                .put(CONF_DETALLE, RepositorioAdjuntosDataverse.recortar(conflicto.detalle(), LARGO_MEMO))
                .put(CONF_DETECTADO_EN, iso(reloj.instant()))
                .put(CONF_ESTADO, CONFLICTO_ABIERTO)
                .put(CONF_ADJUNTOS_DETENIDOS, adjuntosDetenidos)
                .put(CONF_CLAVE, RepositorioAdjuntosDataverse.recortar(conflicto.clave(), LARGO_TEXTO_400))
                .put(NAV_TIPO_DOCUMENTO + "@odata.bind",
                        "/" + conjunto(TABLA_TIPO) + "(" + conflicto.tipoDocumentoId() + ")");
        conflicto.propiedad().ifPresent(propiedadId -> {
            Optional<UUID> fila = buscarPropiedad(conflicto.tipoDocumentoId(), propiedadId);
            if (fila.isPresent()) {
                cuerpo.put(NAV_PROPIEDAD_CM + "@odata.bind", "/" + conjunto(TABLA_PROPIEDAD) + "(" + fila.get() + ")");
            } else {
                // Propiedad que el catálogo todavía no conoce (p. ej. catálogo desactualizado): queda en el detalle.
                LOG.info(() -> "Conflicto sin fila de propiedad en el catálogo. tipo=" + conflicto.tipoDocumentoId());
            }
        });
        UUID id = crear(TABLA_CONFLICTO, CONF_ID, cuerpo, "Registrar conflicto");
        LOG.info(() -> "Conflicto registrado. id=" + id + " clase=" + conflicto.clase());
        return id;
    }

    @Override
    public void actualizarAdjuntosDetenidos(UUID conflictoId, int adjuntosDetenidos) {
        ObjectNode cuerpo = Json.MAPPER.createObjectNode().put(CONF_ADJUNTOS_DETENIDOS, adjuntosDetenidos);
        ClienteDataverse.exigirExito(cliente.patch(filaConflicto(conflictoId), cuerpo.toString()),
                "Actualizar adjuntos detenidos de " + conflictoId);
    }

    @Override
    public Optional<ConflictoRegistrado> obtenerConflicto(UUID conflictoId) {
        ClienteDataverse.Respuesta r = cliente.get(filaConflicto(conflictoId) + "?$select=" + SELECT_CONFLICTO, Map.of());
        if (r.estado() == 404) {
            return Optional.empty();
        }
        JsonNode n = leer(ClienteDataverse.exigirExito(r, "Leer conflicto " + conflictoId));
        return Optional.of(new ConflictoRegistrado(
                conflictoId,
                texto(n, CONF_CLAVE),
                uuid(n, CONF_TIPO_VALOR),
                porCodigo(Conflicto.Clase.values(), entero(n, CONF_CLASE), c -> c.codigo),
                Integer.valueOf(CONFLICTO_ABIERTO).equals(entero(n, CONF_ESTADO))));
    }

    @Override
    public void marcarConflictoResuelto(UUID conflictoId) {
        ObjectNode cuerpo = Json.MAPPER.createObjectNode()
                .put(CONF_ESTADO, CONFLICTO_RESUELTO)
                .put(CONF_RESUELTO_EN, iso(reloj.instant()));
        ClienteDataverse.exigirExito(cliente.patch(filaConflicto(conflictoId), cuerpo.toString()),
                "Resolver conflicto " + conflictoId);
    }

    private String filaConflicto(UUID conflictoId) {
        return conjunto(TABLA_CONFLICTO) + "(" + Objects.requireNonNull(conflictoId, "conflictoId") + ")";
    }

    // ---------- mapeo ----------

    static TipoDocumento mapearTipo(JsonNode n) {
        List<PropiedadCm> propiedades = new ArrayList<>();
        for (JsonNode p : n.path(NAV_TIPO_PROPIEDADES)) {
            propiedades.add(mapearPropiedad(p));
        }
        if (n.hasNonNull(NAV_TIPO_PROPIEDADES + "@odata.nextLink")) {
            // Dataverse pagina las colecciones expandidas: avisar en vez de devolver un tipo incompleto en silencio.
            LOG.warning(() -> "Propiedades expandidas paginadas; el tipo quedó incompleto. tipo=" + texto(n, TIPO_ID));
        }
        Integer origenCarpeta = entero(n, TIPO_ORIGEN_CARPETA);
        TipoDocumento.OrigenTipo origen = porCodigo(TipoDocumento.OrigenTipo.values(), entero(n, TIPO_ORIGEN), o -> o.codigo);
        return new TipoDocumento(
                uuid(n, TIPO_ID),
                texto(n, TIPO_CODIGO),
                texto(n, TIPO_NOMBRE),
                origen == null ? TipoDocumento.OrigenTipo.LOCAL : origen, // el default del esquema es Local
                texto(n, TIPO_CMIS_ID),
                texto(n, TIPO_LOCAL_NAME),
                booleano(n, TIPO_VERSIONABLE),
                booleano(n, TIPO_EN_USO),
                texto(n, TIPO_CARPETA),
                origenCarpeta != null && origenCarpeta == ORIGEN_CARPETA_MANUAL,
                porCodigo(TipoDocumento.Destino.values(), entero(n, TIPO_DESTINO), d -> d.codigo),
                porCodigo(TipoDocumento.Momento.values(), entero(n, TIPO_MOMENTO), m -> m.codigo),
                entero(n, TIPO_DIAS_RETENCION),
                booleano(n, TIPO_INMUTABLE),
                booleano(n, TIPO_REVISADO),
                booleano(n, TIPO_AUSENTE),
                propiedades);
    }

    static PropiedadCm mapearPropiedad(JsonNode n) {
        String lista = texto(n, PROP_LISTA_VALORES);
        List<String> valores = lista == null || lista.isBlank() ? List.of()
                : Arrays.stream(lista.split("\\r?\\n")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        return new PropiedadCm(
                uuid(n, PROP_ID),
                texto(n, PROP_PROPIEDAD_ID),
                texto(n, PROP_NOMBRE),
                texto(n, PROP_TIPO_DATO),
                booleano(n, PROP_OBLIGATORIA),
                texto(n, PROP_VALOR_DEFECTO),
                entero(n, PROP_LARGO_MAXIMO),
                valores,
                booleano(n, PROP_HEREDADA),
                porCodigo(PropiedadCm.Decision.values(), entero(n, PROP_DECISION), d -> d.codigo),
                porCodigo(PropiedadCm.OrigenValor.values(), entero(n, PROP_ORIGEN), o -> o.codigo),
                texto(n, PROP_NOMBRE_LOGICO),
                texto(n, PROP_CONSULTA),
                texto(n, PROP_LITERAL),
                texto(n, PROP_PATRON),
                porCodigo(PropiedadCm.MomentoValidacion.values(), entero(n, PROP_MOMENTO_VALIDACION), m -> m.codigo));
    }

    /** Enum por código de choice; nulo si la columna está vacía (el record aplica su default). */
    private static <E> E porCodigo(E[] valores, Integer codigo, ToIntFunction<E> codigoDe) {
        if (codigo == null) {
            return null;
        }
        for (E e : valores) {
            if (codigoDe.applyAsInt(e) == codigo) {
                return e;
            }
        }
        throw new DataverseException("Código de choice desconocido: " + codigo, -1);
    }

    // ---------- utilidades ----------

    private UUID crear(String tabla, String columnaId, ObjectNode cuerpo, String operacion) {
        ClienteDataverse.Respuesta r = ClienteDataverse.exigirExito(
                cliente.crear(conjunto(tabla) + "?$select=" + columnaId, cuerpo.toString()), operacion);
        UUID id = uuid(leer(r), columnaId);
        if (id == null) {
            throw new DataverseException(operacion + ": la respuesta no trae " + columnaId, r.estado());
        }
        return id;
    }

    private String conjunto(String tabla) {
        return conjuntoPorTabla.computeIfAbsent(tabla, t -> RepositorioAdjuntosDataverse.resolverConjunto(cliente, t));
    }

    /** El nextLink es absoluto; ClienteDataverse trabaja con rutas relativas a la base de la API. */
    private static String siguientePagina(JsonNode pagina) {
        String link = texto(pagina, "@odata.nextLink");
        if (link == null) {
            return null;
        }
        String base = "/api/data/" + VERSION_API + "/";
        int i = link.indexOf(base);
        if (i < 0) {
            throw new DataverseException("nextLink inesperado de Dataverse", -1);
        }
        return link.substring(i + base.length());
    }

    private static String iso(Instant instante) {
        return DateTimeFormatter.ISO_INSTANT.format(instante.truncatedTo(ChronoUnit.SECONDS));
    }

    private static JsonNode leer(ClienteDataverse.Respuesta r) {
        return RepositorioAdjuntosDataverse.leer(r.cuerpo());
    }

    private static String texto(JsonNode n, String campo) {
        return n.hasNonNull(campo) ? n.get(campo).asText() : null;
    }

    private static Integer entero(JsonNode n, String campo) {
        return n.hasNonNull(campo) ? n.get(campo).asInt() : null;
    }

    private static boolean booleano(JsonNode n, String campo) {
        return n.hasNonNull(campo) && n.get(campo).asBoolean();
    }

    private static UUID uuid(JsonNode n, String campo) {
        if (!n.hasNonNull(campo)) {
            return null;
        }
        try {
            return UUID.fromString(n.get(campo).asText());
        } catch (IllegalArgumentException e) {
            throw new DataverseException("GUID inválido en " + campo, e);
        }
    }

    /**
     * Compuerta de administración: lectura suplantada de conflictos con {@code $top=1}. Solo el administrador
     * documental y el worker tienen Read sobre esa tabla; un usuario común recibe 403.
     */
    @Override
    public boolean puedeAdministrar(com.banco.cmv8.domain.ContextoLlamador llamador) {
        java.util.Optional<String> oid = llamador == null ? java.util.Optional.empty() : llamador.objectId();
        if (oid.isEmpty()) {
            return false;
        }
        ClienteDataverse.Respuesta r = cliente.get(conjunto(TABLA_CONFLICTO) + "?$select=" + CONF_ID + "&$top=1",
                Map.of("CallerObjectId", oid.get()));
        if (r.estado() == 403 || r.estado() == 401) {
            return false;
        }
        ClienteDataverse.exigirExito(r, "Verificar rol de administrador documental");
        return true;
    }
}
