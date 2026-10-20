package com.banco.cmv8.domain.port;

import com.banco.cmv8.domain.ContextoLlamador;
import com.banco.cmv8.domain.SasEmitida;

import java.time.Duration;

/**
 * SAS de delegación de usuario para UN blob, ligada al usuario ({@code sduoid}) cuando la política lo exige.
 * La subida siempre va al contenedor de ingesta; la lectura puede ser de ingesta o de almacenados.
 */
public interface EmisorSas {

    SasEmitida emitirSubida(String contenedor, String ruta, Duration vigencia, ContextoLlamador llamador);

    SasEmitida emitirLectura(String contenedor, String ruta, Duration vigencia, ContextoLlamador llamador);
}
