package com.banco.cmv8.domain;

/**
 * Resultado de Defender for Storage (campo scanResultType del evento
 * Microsoft.Security.MalwareScanningResult y etiqueta de índice "Malware Scanning scan result").
 */
public enum ResultadoEscaneo {
    LIMPIO,
    MALICIOSO,
    DESCONOCIDO;

    public static final String TEXTO_LIMPIO = "No threats found";
    public static final String TEXTO_MALICIOSO = "Malicious";

    public static ResultadoEscaneo desdeTexto(String texto) {
        if (texto == null) {
            return DESCONOCIDO;
        }
        String t = texto.trim();
        if (TEXTO_LIMPIO.equalsIgnoreCase(t)) {
            return LIMPIO;
        }
        if (TEXTO_MALICIOSO.equalsIgnoreCase(t)) {
            return MALICIOSO;
        }
        return DESCONOCIDO;
    }
}
