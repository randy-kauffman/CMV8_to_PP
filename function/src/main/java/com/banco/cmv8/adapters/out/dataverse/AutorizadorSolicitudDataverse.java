package com.banco.cmv8.adapters.out.dataverse;

import com.banco.cmv8.config.Json;
import com.banco.cmv8.domain.ContextoLlamador;
import com.banco.cmv8.domain.port.AutorizadorSolicitud;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import static com.banco.cmv8.adapters.out.dataverse.NombresDataverse.*;

/**
 * "Dataverse decide": el acceso del usuario a la fila se evalúa con el modelo de seguridad de Dataverse.
 *
 * <ul>
 *   <li>Lectura: GET de la fila suplantando al usuario con el encabezado CallerObjectId. 200 = puede leer.</li>
 *   <li>Escritura: además, RetrievePrincipalAccess sobre la fila para el systemuser del llamador
 *       debe incluir WriteAccess.</li>
 * </ul>
 * El application user necesita el privilegio de suplantación (prvActOnBehalfOfAnotherUser).
 */
public class AutorizadorSolicitudDataverse implements AutorizadorSolicitud {

    private static final Logger LOG = Logger.getLogger(AutorizadorSolicitudDataverse.class.getName());

    private final ClienteDataverse cliente;
    private final Map<UUID, UUID> systemUserPorOid = new ConcurrentHashMap<>();

    public AutorizadorSolicitudDataverse(ClienteDataverse cliente) {
        this.cliente = cliente;
    }

    @Override
    public boolean puedeLeer(UUID solicitudId, ContextoLlamador llamador) {
        Optional<UUID> oid = oidValido(llamador);
        if (oid.isEmpty()) {
            return false;
        }
        ClienteDataverse.Respuesta r = cliente.get(
                CONJUNTO + "(" + solicitudId + ")?$select=" + ID + "," + STATECODE,
                Map.of("CallerObjectId", oid.get().toString()));
        if (r.estado() == 200) {
            return true;
        }
        if (r.estado() == 401 || r.estado() == 403 || r.estado() == 404) {
            LOG.info(() -> "Dataverse negó lectura (HTTP " + r.estado() + "). solicitudId=" + solicitudId
                    + " oid=" + oid.get());
            return false;
        }
        ClienteDataverse.exigirExito(r, "Verificar lectura de " + solicitudId);
        return false;
    }

    @Override
    public boolean puedeEscribir(UUID solicitudId, ContextoLlamador llamador) {
        if (!puedeLeer(solicitudId, llamador)) {
            return false;
        }
        UUID oid = oidValido(llamador).orElseThrow();
        Optional<UUID> systemUser = systemUser(oid);
        if (systemUser.isEmpty()) {
            LOG.warning(() -> "No existe systemuser para el oid " + oid);
            return false;
        }
        String objetivo = "{'@odata.id':'" + CONJUNTO + "(" + solicitudId + ")'}";
        String ruta = "systemusers(" + systemUser.get() + ")/Microsoft.Dynamics.CRM.RetrievePrincipalAccess(Target=@t)"
                + "?@t=" + ClienteDataverse.codificar(objetivo);
        ClienteDataverse.Respuesta r = ClienteDataverse.exigirExito(cliente.get(ruta, Map.of()),
                "RetrievePrincipalAccess de " + solicitudId);
        String derechos = leer(r.cuerpo()).path("AccessRights").asText("");
        return derechos.contains("WriteAccess");
    }

    private Optional<UUID> systemUser(UUID oid) {
        UUID cacheado = systemUserPorOid.get(oid);
        if (cacheado != null) {
            return Optional.of(cacheado);
        }
        String filtro = "azureactivedirectoryobjectid eq " + oid;
        ClienteDataverse.Respuesta r = ClienteDataverse.exigirExito(
                cliente.get("systemusers?$select=systemuserid&$filter=" + ClienteDataverse.codificar(filtro), Map.of()),
                "Buscar systemuser");
        JsonNode valores = leer(r.cuerpo()).path("value");
        if (!valores.isArray() || valores.isEmpty()) {
            return Optional.empty();
        }
        UUID id = UUID.fromString(valores.get(0).path("systemuserid").asText());
        systemUserPorOid.put(oid, id);
        return Optional.of(id);
    }

    /** El oid se interpola en URLs: solo se acepta si es un GUID. */
    private static Optional<UUID> oidValido(ContextoLlamador llamador) {
        return llamador.objectId().flatMap(o -> {
            try {
                return Optional.of(UUID.fromString(o));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        });
    }

    private static JsonNode leer(String json) {
        try {
            return Json.MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new DataverseException("Respuesta de Dataverse no es JSON válido", e);
        }
    }
}
