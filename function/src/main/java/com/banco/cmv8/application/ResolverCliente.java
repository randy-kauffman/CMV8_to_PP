package com.banco.cmv8.application;

import com.banco.cmv8.domain.Cliente;
import com.banco.cmv8.domain.ValidacionException;
import com.banco.cmv8.domain.port.ResolutorCliente;

import java.util.Optional;

/** Caso de uso: resolver un cliente en AS400 para la pantalla "Nueva solicitud". */
public class ResolverCliente {

    private static final int LARGO_MAXIMO_ID = 50;

    private final ResolutorCliente resolutor;

    public ResolverCliente(ResolutorCliente resolutor) {
        this.resolutor = resolutor;
    }

    public Optional<Cliente> ejecutar(String idCliente) {
        if (idCliente == null || idCliente.isBlank()) {
            throw new ValidacionException("El id de cliente es obligatorio");
        }
        String id = idCliente.trim();
        if (id.length() > LARGO_MAXIMO_ID) {
            throw new ValidacionException("El id de cliente supera " + LARGO_MAXIMO_ID + " caracteres");
        }
        return resolutor.resolver(id);
    }
}
