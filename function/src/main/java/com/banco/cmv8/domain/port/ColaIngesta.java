package com.banco.cmv8.domain.port;

import com.banco.cmv8.domain.MensajeIngesta;

/** Cola de trabajo del worker. */
public interface ColaIngesta {

    void encolar(MensajeIngesta mensaje);
}
