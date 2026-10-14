package com.banco.cmv8.domain.port;

import com.banco.cmv8.domain.ContextoLlamador;
import com.banco.cmv8.domain.SasEmitida;

import java.time.Duration;

/** Emite SAS de delegación de usuario para UN blob. */
public interface EmisorSas {

    /** SAS de creación + escritura (Put Block / Put Block List). */
    SasEmitida emitirSubida(String rutaBlob, Duration vigencia, ContextoLlamador llamador);

    /** SAS de solo lectura. */
    SasEmitida emitirLectura(String rutaBlob, Duration vigencia, ContextoLlamador llamador);
}
