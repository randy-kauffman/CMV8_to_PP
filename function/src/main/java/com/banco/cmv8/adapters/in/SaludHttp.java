package com.banco.cmv8.adapters.in;

import com.banco.cmv8.config.Fabrica;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Sonda de vida. No toca dependencias externas. */
public class SaludHttp {

    @FunctionName("Salud")
    public HttpResponseMessage salud(
            @HttpTrigger(name = "req", methods = {HttpMethod.GET}, authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "salud") HttpRequestMessage<Optional<String>> req,
            ExecutionContext ctx) {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("estado", "ok");
        cuerpo.put("version", Fabrica.VERSION);
        return RespuestasHttp.json(req, HttpStatus.OK, cuerpo);
    }
}
