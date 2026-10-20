package com.banco.cmv8.adapters.out.dataverse;

import com.banco.cmv8.application.AccesoDenegadoException;
import com.banco.cmv8.application.NoAutenticadoException;
import com.banco.cmv8.application.SolicitudNoEncontradaException;
import com.banco.cmv8.config.Json;
import com.banco.cmv8.domain.Adjunto;
import com.banco.cmv8.domain.ClaveAdjunto;
import com.banco.cmv8.domain.ContextoLlamador;
import com.banco.cmv8.domain.EstadoIngesta;
import com.banco.cmv8.domain.ValidacionException;
import com.banco.cmv8.domain.port.RepositorioAdjuntos;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Tablas de adjuntos de cualquier solución vía Web API (ADR-003 §1.1, §4.1). No conoce ninguna tabla: el entity
 * set y las columnas se resuelven por metadata una vez por tabla y por proceso, y las columnas reservadas se
 * derivan del prefijo del publisher. La autorización la decide Dataverse suplantando al llamador con
 * {@code CallerObjectId}: el usuario de aplicación necesita {@code prvActOnBehalfOfAnotherUser}.
 */
public class RepositorioAdjuntosDataverse implements RepositorioAdjuntos {

    private static final Logger LOG = Logger.getLogger(RepositorioAdjuntosDataverse.class.getName());

    private static final String ENCABEZADO_SUPLANTACION = "CallerObjectId";

    private final ClienteDataverse cliente;
    private final Map<String, String> conjuntoPorTabla = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> columnasPorTabla = new ConcurrentHashMap<>();

    public RepositorioAdjuntosDataverse(ClienteDataverse cliente) {
        this.cliente = cliente;
    }

    @Override
    public Adjunto registrarRutaSuplantando(ClaveAdjunto clave, String ruta, ContextoLlamador llamador) {
        String oid = oidObligatorio(llamador);
        ObjectNode cuerpo = Json.MAPPER.createObjectNode().put(clave.columna(Adjunto.COL_RUTA), ruta);
        // If-Match: * evita que el PATCH cree la fila por upsert; return=representation trae la fila completa y
        // así la autorización y la lectura cuestan una sola request (ADR-003 §8).
        Map<String, String> encabezados = Map.of(
                "If-Match", "*",
                ENCABEZADO_SUPLANTACION, oid,
                "Prefer", "return=representation");
        ClienteDataverse.Respuesta r = cliente.enviar("PATCH", fila(clave), cuerpo.toString(), encabezados);
        if (r.estado() == 403) {
            LOG.info(() -> "Dataverse negó escritura suplantada (HTTP 403). adjunto=" + clave + " oid=" + oid);
            throw new AccesoDenegadoException("Sin permiso de escritura sobre el adjunto");
        }
        if (r.estado() == 404) {
            throw new SolicitudNoEncontradaException("El adjunto no existe");
        }
        ClienteDataverse.exigirExito(r, "Registrar ruta de " + clave);
        if (r.cuerpo() == null || r.cuerpo().isBlank()) {
            // Sin representación no hay estado que verificar: mejor fallar que autorizar a ciegas.
            throw new DataverseException("El PATCH de " + clave + " no devolvió la fila (HTTP " + r.estado() + ")",
                    r.estado());
        }
        return mapear(clave, leer(r.cuerpo()));
    }

    @Override
    public Optional<Adjunto> obtenerSuplantando(ClaveAdjunto clave, ContextoLlamador llamador) {
        String oid = oidObligatorio(llamador);
        ClienteDataverse.Respuesta r = cliente.get(fila(clave), Map.of(ENCABEZADO_SUPLANTACION, oid));
        if (r.estado() == 403) {
            LOG.info(() -> "Dataverse negó lectura suplantada (HTTP 403). adjunto=" + clave + " oid=" + oid);
            throw new AccesoDenegadoException("Sin permiso de lectura sobre el adjunto");
        }
        return mapearLectura(clave, r);
    }

    @Override
    public Optional<Adjunto> obtener(ClaveAdjunto clave) {
        return mapearLectura(clave, cliente.get(fila(clave), Map.of()));
    }

    @Override
    public void actualizar(ClaveAdjunto clave, Map<String, Object> cambios) {
        if (cambios == null || cambios.isEmpty()) {
            return;
        }
        String columnaMensaje = clave.columna(Adjunto.COL_MENSAJE);
        ObjectNode cuerpo = Json.MAPPER.createObjectNode();
        cambios.forEach((nombre, valor) -> {
            String columna = clave.columna(nombre);
            if (valor == null) {
                cuerpo.putNull(columna);
            } else if (valor instanceof EstadoIngesta e) {
                cuerpo.put(columna, e.codigo());
            } else if (valor instanceof String s) {
                cuerpo.put(columna, columna.equals(columnaMensaje) ? recortar(s, NombresDataverse.LARGO_MEMO) : s);
            } else {
                // Números, booleanos, UUID e instantes: el MAPPER compartido ya serializa fechas en ISO-8601.
                cuerpo.set(columna, Json.MAPPER.valueToTree(valor));
            }
        });
        ClienteDataverse.Respuesta r = cliente.patch(fila(clave), cuerpo.toString());
        if (r.estado() == 404) {
            // El worker compensa (borra lo creado en CM) cuando la fila ya no existe (ADR-003 §4.2).
            throw new SolicitudNoEncontradaException("El adjunto no existe");
        }
        ClienteDataverse.exigirExito(r, "Actualizar " + clave);
    }

    @Override
    public Set<String> columnas(String tabla) {
        validarTabla(tabla);
        Set<String> cacheadas = columnasPorTabla.get(tabla);
        if (cacheadas != null) {
            return cacheadas;
        }
        ClienteDataverse.Respuesta r = cliente.get(
                "EntityDefinitions(LogicalName='" + tabla + "')/Attributes?$select=LogicalName", Map.of());
        if (r.estado() == 404) {
            // Tabla inexistente: ValidarTabla lo informa como columnas faltantes. No se cachea por si la crean.
            return Set.of();
        }
        ClienteDataverse.exigirExito(r, "Leer columnas de " + tabla);
        Set<String> nombres = new HashSet<>();
        for (JsonNode a : leer(r.cuerpo()).path("value")) {
            String nombre = a.path("LogicalName").asText("");
            if (!nombre.isEmpty()) {
                nombres.add(nombre);
            }
        }
        Set<String> inmutable = Set.copyOf(nombres);
        columnasPorTabla.put(tabla, inmutable);
        return inmutable;
    }

    // ---------- metadata ----------

    private String fila(ClaveAdjunto clave) {
        return conjunto(clave.tabla()) + "(" + clave.id() + ")";
    }

    private String conjunto(String tabla) {
        String cacheado = conjuntoPorTabla.get(tabla);
        if (cacheado != null) {
            return cacheado;
        }
        String resuelto = resolverConjunto(cliente, tabla);
        conjuntoPorTabla.put(tabla, resuelto);
        return resuelto;
    }

    /**
     * EntitySetName de una tabla por metadata: la pluralización la decide Dataverse (no siempre es nombre + "s").
     * Compartido con el catálogo; el caché lo lleva cada llamador.
     */
    static String resolverConjunto(ClienteDataverse cliente, String tabla) {
        validarTabla(tabla);
        ClienteDataverse.Respuesta r = cliente.get(
                "EntityDefinitions(LogicalName='" + tabla + "')?$select=EntitySetName", Map.of());
        if (r.estado() == 404) {
            throw new SolicitudNoEncontradaException("La tabla no existe");
        }
        ClienteDataverse.exigirExito(r, "Resolver entity set de " + tabla);
        String conjunto = leer(r.cuerpo()).path("EntitySetName").asText("");
        if (conjunto.isEmpty()) {
            throw new DataverseException("La metadata de " + tabla + " no trae EntitySetName", r.estado());
        }
        LOG.fine(() -> "Entity set de " + tabla + ": " + conjunto);
        return conjunto;
    }

    /** El nombre de tabla se interpola en URLs de metadata: se revalida aunque ClaveAdjunto ya lo haga. */
    private static void validarTabla(String tabla) {
        if (tabla == null || !ClaveAdjunto.TABLA_VALIDA.matcher(tabla).matches()) {
            throw new ValidacionException("Nombre de tabla inválido");
        }
    }

    // ---------- mapeo ----------

    private Optional<Adjunto> mapearLectura(ClaveAdjunto clave, ClienteDataverse.Respuesta r) {
        if (r.estado() == 404) {
            return Optional.empty();
        }
        ClienteDataverse.exigirExito(r, "Leer " + clave);
        return Optional.of(mapear(clave, leer(r.cuerpo())));
    }

    /**
     * La fila se pide sin $select: el valor de un metadato de origen columna puede ser cualquier columna del
     * maker, así que {@code valores} lleva todas las propiedades que no son anotaciones OData.
     */
    static Adjunto mapear(ClaveAdjunto clave, JsonNode n) {
        Map<String, Object> valores = new HashMap<>();
        for (Map.Entry<String, JsonNode> campo : n.properties()) {
            if (campo.getKey().contains("@")) {
                continue; // @odata.context, @odata.etag y anotaciones prop@...
            }
            Object valor = escalar(campo.getValue());
            if (valor != null) {
                valores.put(campo.getKey(), valor);
            }
        }
        String colEstado = clave.columna(Adjunto.COL_ESTADO);
        String colTamano = clave.columna(Adjunto.COL_TAMANO);
        String colTipo = "_" + clave.columna(Adjunto.COL_TIPO) + "_value";
        return new Adjunto(
                clave,
                texto(n, clave.columna(Adjunto.COL_NOMBRE_ARCHIVO)),
                n.hasNonNull(colTamano) ? n.get(colTamano).asLong() : null,
                texto(n, clave.columna(Adjunto.COL_CONTENT_TYPE)),
                estado(n, colEstado),
                texto(n, clave.columna(Adjunto.COL_RUTA)),
                texto(n, clave.columna(Adjunto.COL_CMIS)),
                uuid(n, colTipo),
                texto(n, clave.columna(Adjunto.COL_MENSAJE)),
                valores,
                instante(n, NombresDataverse.MODIFICADO));
    }

    private static EstadoIngesta estado(JsonNode n, String columna) {
        if (!n.hasNonNull(columna)) {
            return EstadoIngesta.CREADA;
        }
        try {
            return EstadoIngesta.desdeCodigo(n.get(columna).asInt());
        } catch (IllegalArgumentException e) {
            throw new DataverseException("Código de estado desconocido en " + columna, e);
        }
    }

    private static Object escalar(JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isBoolean()) {
            return v.booleanValue();
        }
        if (v.isIntegralNumber() && v.canConvertToLong()) {
            return v.longValue();
        }
        if (v.isNumber()) {
            return v.doubleValue();
        }
        if (v.isTextual()) {
            return v.textValue();
        }
        return null; // objetos y colecciones solo aparecen con $expand, que acá no se usa
    }

    private static String texto(JsonNode n, String campo) {
        return n.hasNonNull(campo) ? n.get(campo).asText() : null;
    }

    private static UUID uuid(JsonNode n, String campo) {
        if (!n.hasNonNull(campo)) {
            return null;
        }
        try {
            return UUID.fromString(n.get(campo).asText());
        } catch (IllegalArgumentException e) {
            throw new DataverseException("Valor de lookup inválido en " + campo, e);
        }
    }

    private static Instant instante(JsonNode n, String campo) {
        if (!n.hasNonNull(campo)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(n.get(campo).asText()).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // ---------- utilidades ----------

    /** El oid va en un encabezado que Dataverse usa para suplantar: solo se acepta un GUID. */
    private static String oidObligatorio(ContextoLlamador llamador) {
        String oid = llamador == null ? null : llamador.objectId().orElse(null);
        if (oid == null || oid.isBlank()) {
            throw new NoAutenticadoException("Falta el oid del llamador");
        }
        try {
            return UUID.fromString(oid.trim()).toString();
        } catch (IllegalArgumentException e) {
            throw new NoAutenticadoException("El oid del llamador no es un GUID");
        }
    }

    static String recortar(String texto, int largo) {
        if (texto == null) {
            return null;
        }
        return texto.length() > largo ? texto.substring(0, largo) : texto;
    }

    static JsonNode leer(String json) {
        try {
            return Json.MAPPER.readTree(json == null ? "" : json);
        } catch (JsonProcessingException e) {
            throw new DataverseException("Respuesta de Dataverse no es JSON válido", e);
        }
    }
}
