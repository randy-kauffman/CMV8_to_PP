package com.banco.cmv8.adapters.out.dataverse;

import com.banco.cmv8.config.Json;
import com.banco.cmv8.domain.Cliente;
import com.banco.cmv8.domain.EstadoSolicitud;
import com.banco.cmv8.domain.SolicitudIngesta;
import com.banco.cmv8.domain.port.RepositorioSolicitudes;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.banco.cmv8.adapters.out.dataverse.NombresDataverse.*;

/** Tabla SolicitudIngesta vía Web API, con la identidad de la aplicación (sin suplantación). */
public class RepositorioSolicitudesDataverse implements RepositorioSolicitudes {

    private static final int TOPE_ESTANCADAS = 50;

    private final ClienteDataverse cliente;

    public RepositorioSolicitudesDataverse(ClienteDataverse cliente) {
        this.cliente = cliente;
    }

    private static String fila(UUID id) {
        return CONJUNTO + "(" + id + ")";
    }

    @Override
    public Optional<SolicitudIngesta> obtener(UUID id) {
        ClienteDataverse.Respuesta r = cliente.get(fila(id) + "?$select=" + SELECT_COMPLETO, Map.of());
        if (r.estado() == 404) {
            return Optional.empty();
        }
        ClienteDataverse.exigirExito(r, "Leer solicitud " + id);
        return Optional.of(mapear(leer(r.cuerpo())));
    }

    @Override
    public void actualizarEstado(UUID id, EstadoSolicitud estado) {
        ObjectNode cuerpo = Json.MAPPER.createObjectNode().put(ESTADO, estado.codigo());
        actualizar(id, cuerpo, "Actualizar estado");
    }

    @Override
    public void registrarRutaBlob(UUID id, String rutaBlob) {
        ObjectNode cuerpo = Json.MAPPER.createObjectNode().put(RUTA_BLOB, rutaBlob);
        actualizar(id, cuerpo, "Registrar ruta de blob");
    }

    @Override
    public void registrarCliente(UUID id, Cliente c) {
        ObjectNode cuerpo = Json.MAPPER.createObjectNode()
                .put(ID_CLIENTE, c.idCliente())
                .put(ID_CLIENTE2, c.idCliente2())
                .put(NOMBRE_CLIENTE, c.nombre())
                .put(AFILIADO_PADRE, c.afiliadoPadre())
                .put(ID_DUENO, c.idDueno());
        actualizar(id, cuerpo, "Registrar cliente");
    }

    @Override
    public void marcarArchivado(UUID id, String cmisObjectId) {
        ObjectNode cuerpo = Json.MAPPER.createObjectNode()
                .put(ESTADO, EstadoSolicitud.ARCHIVADO.codigo())
                .put(CMIS_OBJECT_ID, cmisObjectId)
                .putNull(MENSAJE_ERROR);
        actualizar(id, cuerpo, "Marcar archivado");
    }

    @Override
    public void marcarRechazado(UUID id, String motivo) {
        ObjectNode cuerpo = Json.MAPPER.createObjectNode()
                .put(ESTADO, EstadoSolicitud.RECHAZADO.codigo())
                .put(MENSAJE_ERROR, recortar(motivo));
        actualizar(id, cuerpo, "Marcar rechazado");
    }

    @Override
    public void marcarError(UUID id, String mensaje) {
        ObjectNode cuerpo = Json.MAPPER.createObjectNode()
                .put(ESTADO, EstadoSolicitud.ERROR.codigo())
                .put(MENSAJE_ERROR, recortar(mensaje));
        actualizar(id, cuerpo, "Marcar error");
    }

    @Override
    public List<SolicitudIngesta> listarEstancadas(EstadoSolicitud estado, Duration antiguedad) {
        String limite = DateTimeFormatter.ISO_INSTANT.format(
                Instant.now().minus(antiguedad).truncatedTo(ChronoUnit.SECONDS));
        String filtro = ESTADO + " eq " + estado.codigo() + " and " + MODIFICADO + " lt " + limite;
        String ruta = CONJUNTO
                + "?$select=" + SELECT_COMPLETO
                + "&$filter=" + ClienteDataverse.codificar(filtro)
                + "&$orderby=" + ClienteDataverse.codificar(MODIFICADO + " asc")
                + "&$top=" + TOPE_ESTANCADAS;
        ClienteDataverse.Respuesta r = ClienteDataverse.exigirExito(cliente.get(ruta, Map.of()), "Listar estancadas");
        List<SolicitudIngesta> lista = new ArrayList<>();
        for (JsonNode n : leer(r.cuerpo()).path("value")) {
            lista.add(mapear(n));
        }
        return lista;
    }

    private void actualizar(UUID id, ObjectNode cuerpo, String operacion) {
        ClienteDataverse.exigirExito(cliente.patch(fila(id), cuerpo.toString()), operacion + " " + id);
    }

    private static String recortar(String texto) {
        if (texto == null) {
            return null;
        }
        return texto.length() > LARGO_MENSAJE_ERROR ? texto.substring(0, LARGO_MENSAJE_ERROR) : texto;
    }

    private static JsonNode leer(String json) {
        try {
            return Json.MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new DataverseException("Respuesta de Dataverse no es JSON válido", e);
        }
    }

    static SolicitudIngesta mapear(JsonNode n) {
        return new SolicitudIngesta(
                UUID.fromString(n.path(ID).asText()),
                texto(n, NOMBRE_ARCHIVO),
                n.hasNonNull(TAMANO_BYTES) ? n.get(TAMANO_BYTES).asLong() : null,
                texto(n, CONTENT_TYPE),
                n.hasNonNull(ESTADO) ? EstadoSolicitud.desdeCodigo(n.get(ESTADO).asInt()) : EstadoSolicitud.CREADA,
                texto(n, RUTA_BLOB),
                texto(n, CMIS_OBJECT_ID),
                texto(n, ID_CLIENTE),
                texto(n, ID_CLIENTE2),
                texto(n, NOMBRE_CLIENTE),
                texto(n, AFILIADO_PADRE),
                texto(n, ID_DUENO),
                texto(n, MENSAJE_ERROR));
    }

    private static String texto(JsonNode n, String campo) {
        return n.hasNonNull(campo) ? n.get(campo).asText() : null;
    }
}
