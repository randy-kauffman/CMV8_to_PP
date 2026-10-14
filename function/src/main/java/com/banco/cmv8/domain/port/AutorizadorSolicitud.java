package com.banco.cmv8.domain.port;

import com.banco.cmv8.domain.ContextoLlamador;

import java.util.UUID;

/** Decide si el llamador tiene acceso a la fila de la solicitud (Dataverse decide). */
public interface AutorizadorSolicitud {

    boolean puedeEscribir(UUID solicitudId, ContextoLlamador llamador);

    boolean puedeLeer(UUID solicitudId, ContextoLlamador llamador);
}
