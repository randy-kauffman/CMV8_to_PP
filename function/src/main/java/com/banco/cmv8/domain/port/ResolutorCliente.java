package com.banco.cmv8.domain.port;

import com.banco.cmv8.domain.Cliente;

import java.util.Optional;

/** Resolución de datos de cliente (AS400). */
public interface ResolutorCliente {

    Optional<Cliente> resolver(String idCliente);
}
