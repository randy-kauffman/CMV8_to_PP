package com.banco.cmv8.adapters.in;

import com.banco.cmv8.config.Fabrica;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.BindingName;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;

import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/** POST /api/as400/consultas/{nombre} con los parámetros como JSON plano → la primera fila, o 404. */
public class ConsultasHttp {

    @FunctionName("ConsultarAs400")
    @SuppressWarnings("unchecked")
    public HttpResponseMessage consultar(
            @HttpTrigger(name = "req", methods = {HttpMethod.POST}, authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "as400/consultas/{nombre}") HttpRequestMessage<Optional<String>> req,
            @BindingName("nombre") String nombre,
            ExecutionContext ctx) {
        Logger log = ctx.getLogger();
        try {
            Map<String, Object> parametros = SasHttp.leer(req.getBody().orElse("{}"), Map.class);
            if (parametros == null) {
                return RespuestasHttp.error(req, HttpStatus.BAD_REQUEST, "Cuerpo JSON inválido");
            }
            Optional<Map<String, Object>> fila = Fabrica.get().consultarAs400().ejecutar(nombre, parametros,
                    LectorLlamador.desde(req.getHeaders()), log);
            return fila.map(f -> RespuestasHttp.json(req, HttpStatus.OK, f))
                    .orElseGet(() -> RespuestasHttp.error(req, HttpStatus.NOT_FOUND, "Sin datos para la consulta"));
        } catch (RuntimeException e) {
            return RespuestasHttp.desdeExcepcion(req, e, log);
        }
    }
}
