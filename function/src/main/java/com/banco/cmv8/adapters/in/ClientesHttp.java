package com.banco.cmv8.adapters.in;

import com.banco.cmv8.config.Fabrica;
import com.banco.cmv8.config.Json;
import com.banco.cmv8.domain.Cliente;
import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;

import java.util.Optional;
import java.util.logging.Logger;

/** Resolución de cliente en AS400 (S6). POST para no dejar el id de cliente en logs de URL. */
public class ClientesHttp {

    @FunctionName("ResolverCliente")
    public HttpResponseMessage resolver(
            @HttpTrigger(name = "req", methods = {HttpMethod.POST}, authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "clientes/resolver") HttpRequestMessage<Optional<String>> req,
            ExecutionContext ctx) {
        Logger log = ctx.getLogger();
        try {
            String id;
            try {
                JsonNode cuerpo = Json.MAPPER.readTree(req.getBody().orElse(""));
                id = cuerpo == null ? null : cuerpo.path("id").asText(null);
            } catch (Exception e) {
                return RespuestasHttp.error(req, HttpStatus.BAD_REQUEST, "Cuerpo JSON inválido");
            }
            Optional<Cliente> cliente = Fabrica.get().resolverCliente().ejecutar(id);
            if (cliente.isEmpty()) {
                return RespuestasHttp.error(req, HttpStatus.NOT_FOUND, "Cliente no encontrado");
            }
            return RespuestasHttp.json(req, HttpStatus.OK, cliente.get());
        } catch (RuntimeException e) {
            return RespuestasHttp.desdeExcepcion(req, e, log);
        }
    }
}
