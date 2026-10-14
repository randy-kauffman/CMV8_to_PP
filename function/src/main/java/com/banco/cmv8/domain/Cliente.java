package com.banco.cmv8.domain;

/** Datos del cliente resueltos desde AS400. */
public record Cliente(
        String idCliente,
        String idCliente2,
        String nombre,
        String afiliadoPadre,
        String idDueno) {
}
