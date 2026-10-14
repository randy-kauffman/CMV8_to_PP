package com.banco.cmv8.domain.port;

import com.banco.cmv8.domain.ContextoLlamador;
import com.banco.cmv8.domain.TokenUsuario;

/**
 * Obtiene un token de Azure Storage en nombre del usuario que llamó al broker (S1b, SAS ligada al usuario).
 * El PCF lo envía como {@code Authorization: Bearer} junto con la SAS: la SAS sola no alcanza ({@code sduoid})
 * y el token solo tampoco (el usuario no tiene RBAC sobre el contenedor).
 */
public interface IntercambiadorTokenUsuario {

    /**
     * @param llamador debe traer {@code objectId} y {@code bearerToken} (el token con el que llamó al broker).
     * @throws com.banco.cmv8.application.IntercambioTokenException si Entra rechaza el intercambio.
     */
    TokenUsuario tokenStorage(ContextoLlamador llamador);
}
