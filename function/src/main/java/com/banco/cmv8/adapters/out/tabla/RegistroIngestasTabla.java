package com.banco.cmv8.adapters.out.tabla;

import com.azure.core.credential.TokenCredential;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.TableClientBuilder;
import com.azure.data.tables.models.ListEntitiesOptions;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableEntityUpdateMode;
import com.azure.data.tables.models.TableServiceException;
import com.banco.cmv8.config.Json;
import com.banco.cmv8.domain.ClaveAdjunto;
import com.banco.cmv8.domain.EstadoIngesta;
import com.banco.cmv8.domain.RegistroIngesta;
import com.banco.cmv8.domain.port.RegistroIngestas;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Azure Table {@code ingesta} (ADR-003 §3) con la identidad administrada. Cada adjunto es una fila
 * (PartitionKey = tabla lógica, RowKey = id); la deduplicación de conflictos vive en la misma tabla, en la
 * partición {@link #PARTICION_CONFLICTO}, para no pedir un segundo permiso ni otra tabla en Bicep.
 *
 * <p>Solo se loguean claves (tabla, id, clave de conflicto): las columnas pueden traer datos de clientes.
 */
public class RegistroIngestasTabla implements RegistroIngestas {

    private static final Logger LOG = Logger.getLogger(RegistroIngestasTabla.class.getName());

    /**
     * Partición de las filas de deduplicación de conflictos. No choca con una tabla de adjuntos porque los
     * nombres lógicos de Dataverse siempre llevan prefijo de publisher ({@code xxx_}).
     */
    public static final String PARTICION_CONFLICTO = "conflicto";

    // Columnas de la fila de ingesta.
    static final String COL_ESTADO = "estado";
    static final String COL_TIPO_DOCUMENTO = "tipoDocumentoId";
    static final String COL_CONTENEDOR = "contenedor";
    static final String COL_RUTA = "ruta";
    static final String COL_SOLICITANTE = "solicitanteOid";
    static final String COL_NOMBRE = "nombreArchivo";
    static final String COL_TAMANO = "tamanoBytes";
    static final String COL_CONTENT_TYPE = "contentType";
    static final String COL_VALORES = "valoresJson";
    static final String COL_CMIS = "cmisObjectId";
    static final String COL_INTENTOS = "intentos";
    static final String COL_MENSAJE = "mensaje";
    static final String COL_CONFLICTO_CLAVE = "conflictoClave";
    static final String COL_FECHA_DEPURACION = "fechaDepuracion";
    static final String COL_SAS_EMITIDA = "sasEmitidaEn";
    static final String COL_SUBIDO = "subidoEn";
    static final String COL_PROCESADO = "procesadoEn";

    // Columna de la fila de conflicto.
    static final String COL_CONFLICTO_ID = "conflictoId";

    /** Una propiedad string de Azure Table admite hasta 64 KiB (32 K caracteres UTF-16). */
    static final int LARGO_MAXIMO_STRING = 32 * 1024;

    /** Azure Table devuelve como mucho 1000 entidades por página. */
    private static final int PAGINA_MAXIMA = 1000;

    private static final TypeReference<Map<String, Object>> TIPO_VALORES = new TypeReference<>() {
    };

    private final TableClient tabla;
    private final Clock reloj;

    public RegistroIngestasTabla(TableClient tabla) {
        this(tabla, Clock.systemUTC());
    }

    /** El reloj se inyecta para que el corte de antigüedad del Watchdog sea verificable en pruebas. */
    public RegistroIngestasTabla(TableClient tabla, Clock reloj) {
        this.tabla = Objects.requireNonNull(tabla, "tabla");
        this.reloj = Objects.requireNonNull(reloj, "reloj");
    }

    /** Construye el cliente con la credencial del proceso (identidad administrada en Azure). */
    public static RegistroIngestasTabla crear(String endpoint, String nombreTabla, TokenCredential credencial) {
        return new RegistroIngestasTabla(clienteTabla(endpoint, nombreTabla, credencial));
    }

    /** Cliente de una tabla con Entra ID; sin claves de cuenta (Storage Table Data Contributor acotado). */
    static TableClient clienteTabla(String endpoint, String nombreTabla, TokenCredential credencial) {
        return new TableClientBuilder()
                .endpoint(endpoint)
                .tableName(nombreTabla)
                .credential(credencial)
                .buildClient();
    }

    /** true si la excepción es un 404 (entidad o tabla inexistente). */
    static boolean esNoEncontrado(TableServiceException e) {
        return e.getResponse() != null && e.getResponse().getStatusCode() == 404;
    }

    /** Literal de cadena OData: las comillas simples se duplican para que el valor no pueda cerrar el literal. */
    static String literal(String valor) {
        return "'" + valor.replace("'", "''") + "'";
    }

    // ------------------------------------------------------------------ registros de ingesta

    @Override
    public void guardar(RegistroIngesta registro) {
        // Replace y no Merge: una columna que pasó a nula tiene que desaparecer de la fila.
        tabla.upsertEntityWithResponse(aEntidad(registro), TableEntityUpdateMode.REPLACE, null, null);
    }

    @Override
    public Optional<RegistroIngesta> obtener(ClaveAdjunto clave) {
        try {
            return Optional.of(desdeEntidad(tabla.getEntity(clave.tabla(), clave.id().toString())));
        } catch (TableServiceException e) {
            if (esNoEncontrado(e)) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public List<RegistroIngesta> listarEstancados(Set<EstadoIngesta> estados, Duration antiguedad, int tope) {
        if (estados == null || estados.isEmpty() || tope <= 0) {
            return List.of();
        }
        Instant corte = reloj.instant().minus(antiguedad);
        return listar(filtroEstancados(estados, corte), tope);
    }

    /**
     * Usa el {@code Timestamp} del sistema (lo pone el servicio en cada escritura), así que "estancado" significa
     * "nadie escribió la fila desde el corte". El orden de los estados es por código para que el filtro sea estable.
     */
    static String filtroEstancados(Set<EstadoIngesta> estados, Instant corte) {
        String porEstado = estados.stream()
                .sorted(Comparator.comparingInt(EstadoIngesta::codigo))
                .map(e -> COL_ESTADO + " eq " + e.codigo())
                .collect(Collectors.joining(" or ", "(", ")"));
        return porEstado
                + " and Timestamp lt datetime'" + corte.truncatedTo(ChronoUnit.SECONDS) + "'"
                + " and PartitionKey ne " + literal(PARTICION_CONFLICTO);
    }

    /**
     * Es un barrido de la tabla (Azure Table no indexa columnas), aceptable porque solo corre al resolver un
     * conflicto, que es un evento raro y administrativo.
     */
    @Override
    public List<RegistroIngesta> listarPorConflicto(String conflictoClave) {
        Objects.requireNonNull(conflictoClave, "conflictoClave");
        return listar(COL_CONFLICTO_CLAVE + " eq " + literal(conflictoClave), Integer.MAX_VALUE);
    }

    private List<RegistroIngesta> listar(String filtro, int tope) {
        ListEntitiesOptions opciones = new ListEntitiesOptions()
                .setFilter(filtro)
                .setTop(Math.min(tope, PAGINA_MAXIMA));
        // setTop es el tamaño de página, no un límite total: el corte real lo da limit().
        List<RegistroIngesta> resultado = new ArrayList<>();
        tabla.listEntities(opciones, null, null).stream()
                .filter(e -> !PARTICION_CONFLICTO.equals(e.getPartitionKey()))
                .limit(tope)
                .forEach(e -> {
                    try {
                        resultado.add(desdeEntidad(e));
                    } catch (RuntimeException ex) {
                        // Una fila corrupta no debe frenar al Watchdog ni al reencolado del resto.
                        LOG.warning(() -> "Fila de ingesta ilegible, se omite: " + e.getPartitionKey() + "/"
                                + e.getRowKey() + " (" + ex.getClass().getSimpleName() + ")");
                    }
                });
        return resultado;
    }

    // ------------------------------------------------------------------ deduplicación de conflictos

    @Override
    public Optional<UUID> conflictoRegistrado(String conflictoClave) {
        try {
            TableEntity e = tabla.getEntity(PARTICION_CONFLICTO, codificarRowKey(conflictoClave));
            String id = texto(e, COL_CONFLICTO_ID);
            return id == null ? Optional.empty() : Optional.of(UUID.fromString(id));
        } catch (TableServiceException e) {
            if (esNoEncontrado(e)) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public void registrarConflicto(String conflictoClave, UUID conflictoId) {
        Objects.requireNonNull(conflictoId, "conflictoId");
        TableEntity e = new TableEntity(PARTICION_CONFLICTO, codificarRowKey(conflictoClave))
                .addProperty(COL_CONFLICTO_ID, conflictoId.toString());
        tabla.upsertEntityWithResponse(e, TableEntityUpdateMode.REPLACE, null, null);
    }

    @Override
    public void borrarConflicto(String conflictoClave) {
        try {
            tabla.deleteEntity(PARTICION_CONFLICTO, codificarRowKey(conflictoClave));
        } catch (TableServiceException e) {
            if (!esNoEncontrado(e)) {
                throw e;
            }
        }
    }

    /**
     * RowKey no admite {@code / \ # ?} ni caracteres de control (U+0000–U+001F, U+007F–U+009F). Se escapan como
     * {@code %XX} (hexadecimal en mayúsculas) y también el propio {@code %}, para que la codificación sea
     * reversible e inyectiva: dos claves distintas nunca comparten fila.
     */
    static String codificarRowKey(String clave) {
        Objects.requireNonNull(clave, "conflictoClave");
        StringBuilder sb = new StringBuilder(clave.length() + 8);
        for (int i = 0; i < clave.length(); i++) {
            char c = clave.charAt(i);
            if (c == '%' || c == '/' || c == '\\' || c == '#' || c == '?' || c <= 0x1F || (c >= 0x7F && c <= 0x9F)) {
                sb.append('%').append(String.format("%02X", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Inversa de {@link #codificarRowKey(String)}. */
    static String decodificarRowKey(String rowKey) {
        StringBuilder sb = new StringBuilder(rowKey.length());
        for (int i = 0; i < rowKey.length(); i++) {
            char c = rowKey.charAt(i);
            if (c == '%') {
                if (i + 2 >= rowKey.length()) {
                    throw new IllegalArgumentException("Escape incompleto en RowKey de conflicto");
                }
                sb.append((char) Integer.parseInt(rowKey.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ mapeo

    /** Las columnas nulas se omiten: con upsert Replace, ausente equivale a nula. */
    static TableEntity aEntidad(RegistroIngesta r) {
        Map<String, Object> p = new HashMap<>();
        p.put(COL_ESTADO, r.estado().codigo());
        poner(p, COL_TIPO_DOCUMENTO, r.tipoDocumentoId() == null ? null : r.tipoDocumentoId().toString());
        p.put(COL_CONTENEDOR, r.contenedor());
        p.put(COL_RUTA, r.ruta());
        poner(p, COL_SOLICITANTE, r.solicitanteOid());
        poner(p, COL_NOMBRE, r.nombreArchivo());
        poner(p, COL_TAMANO, r.tamanoBytes());
        poner(p, COL_CONTENT_TYPE, r.contentType());
        if (!r.valores().isEmpty()) {
            p.put(COL_VALORES, valoresAJson(r));
        }
        poner(p, COL_CMIS, r.cmisObjectId());
        p.put(COL_INTENTOS, r.intentos());
        poner(p, COL_MENSAJE, r.mensaje());
        poner(p, COL_CONFLICTO_CLAVE, r.conflictoClave());
        poner(p, COL_FECHA_DEPURACION, r.fechaDepuracion() == null ? null : r.fechaDepuracion().toString());
        poner(p, COL_SAS_EMITIDA, utc(r.sasEmitidaEn()));
        poner(p, COL_SUBIDO, utc(r.subidoEn()));
        poner(p, COL_PROCESADO, utc(r.procesadoEn()));
        return new TableEntity(r.clave().tabla(), r.clave().id().toString()).setProperties(p);
    }

    static RegistroIngesta desdeEntidad(TableEntity e) {
        ClaveAdjunto clave = new ClaveAdjunto(e.getPartitionKey(), UUID.fromString(e.getRowKey()));
        Integer estado = entero(e, COL_ESTADO);
        if (estado == null) {
            throw new IllegalStateException("Fila de ingesta sin estado: " + clave);
        }
        String tipo = texto(e, COL_TIPO_DOCUMENTO);
        String fecha = texto(e, COL_FECHA_DEPURACION);
        Integer intentos = entero(e, COL_INTENTOS);
        return new RegistroIngesta(
                clave,
                EstadoIngesta.desdeCodigo(estado),
                tipo == null ? null : UUID.fromString(tipo),
                texto(e, COL_CONTENEDOR),
                texto(e, COL_RUTA),
                texto(e, COL_SOLICITANTE),
                texto(e, COL_NOMBRE),
                largo(e, COL_TAMANO),
                texto(e, COL_CONTENT_TYPE),
                valoresDesdeJson(texto(e, COL_VALORES), clave),
                texto(e, COL_CMIS),
                intentos == null ? 0 : intentos,
                texto(e, COL_MENSAJE),
                texto(e, COL_CONFLICTO_CLAVE),
                fecha == null ? null : LocalDate.parse(fecha),
                instante(e, COL_SAS_EMITIDA),
                instante(e, COL_SUBIDO),
                instante(e, COL_PROCESADO));
    }

    private static void poner(Map<String, Object> p, String columna, Object valor) {
        if (valor != null) {
            p.put(columna, valor);
        }
    }

    private static OffsetDateTime utc(Instant instante) {
        return instante == null ? null : OffsetDateTime.ofInstant(instante, ZoneOffset.UTC);
    }

    private static String valoresAJson(RegistroIngesta r) {
        String json;
        try {
            json = Json.MAPPER.writeValueAsString(r.valores());
        } catch (JsonProcessingException e) {
            // Sin la causa: su mensaje podría citar valores de la fila.
            throw new IllegalStateException("No se pudieron serializar los valores de " + r.clave());
        }
        if (json.length() > LARGO_MAXIMO_STRING) {
            throw new IllegalArgumentException("Los valores de " + r.clave() + " superan el máximo de una columna de "
                    + "Azure Table (" + json.length() + " caracteres)");
        }
        return json;
    }

    private static Map<String, Object> valoresDesdeJson(String json, ClaveAdjunto clave) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<String, Object> leidos;
        try {
            leidos = Json.MAPPER.readValue(json, TIPO_VALORES);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("valoresJson ilegible en " + clave);
        }
        if (leidos == null) {
            return Map.of();
        }
        // El record copia con Map.copyOf, que no admite valores nulos.
        Map<String, Object> sinNulos = new HashMap<>();
        leidos.forEach((k, v) -> {
            if (v != null) {
                sinNulos.put(k, v);
            }
        });
        return sinNulos;
    }

    static String texto(TableEntity e, String columna) {
        Object v = e.getProperty(columna);
        return v == null ? null : v.toString();
    }

    /** Tolera Integer, Long o texto: el tipo EDM depende de quién escribió la fila. */
    private static Integer entero(TableEntity e, String columna) {
        Object v = e.getProperty(columna);
        if (v == null) {
            return null;
        }
        return v instanceof Number n ? Integer.valueOf(n.intValue()) : Integer.valueOf(v.toString());
    }

    private static Long largo(TableEntity e, String columna) {
        Object v = e.getProperty(columna);
        if (v == null) {
            return null;
        }
        return v instanceof Number n ? Long.valueOf(n.longValue()) : Long.valueOf(v.toString());
    }

    private static Instant instante(TableEntity e, String columna) {
        Object v = e.getProperty(columna);
        if (v == null) {
            return null;
        }
        if (v instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (v instanceof Instant i) {
            return i;
        }
        return OffsetDateTime.parse(v.toString()).toInstant();
    }
}
