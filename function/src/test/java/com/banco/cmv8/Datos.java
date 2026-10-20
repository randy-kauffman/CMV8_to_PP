package com.banco.cmv8;

import com.banco.cmv8.domain.Adjunto;
import com.banco.cmv8.domain.ClaveAdjunto;
import com.banco.cmv8.domain.ContextoLlamador;
import com.banco.cmv8.domain.EstadoIngesta;
import com.banco.cmv8.domain.RegistroIngesta;
import com.banco.cmv8.domain.catalogo.DefinicionTipoCmis;
import com.banco.cmv8.domain.catalogo.PropiedadCm;
import com.banco.cmv8.domain.catalogo.TipoDocumento;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Datos de prueba compartidos por los tests de dominio y aplicación. */
public final class Datos {

    public static final String TABLA = "cred_adjuntocredito";
    public static final String INGESTA = "cmv8-ingesta";
    public static final String ALMACENADOS = "cmv8-almacenados";
    public static final Instant AHORA = Instant.parse("2026-10-19T12:00:00Z");
    public static final Clock RELOJ = Clock.fixed(AHORA, ZoneOffset.UTC);
    public static final LocalDate HOY = LocalDate.of(2026, 10, 19);
    public static final String NOMBRE = "contrato.pdf";
    public static final String MIME = "application/pdf";
    public static final String TIPO_CMIS = "clb:Credito";
    public static final String CARPETA = "/Creditos";
    public static final Logger LOG = Logger.getLogger("cmv8.pruebas");

    static {
        LOG.setLevel(Level.OFF);
        LOG.setUseParentHandlers(false);
    }

    private Datos() {
    }

    public static ClaveAdjunto clave() {
        return new ClaveAdjunto(TABLA, UUID.randomUUID());
    }

    public static String ruta(ClaveAdjunto clave) {
        return clave.ruta(NOMBRE);
    }

    public static ContextoLlamador llamador() {
        return new ContextoLlamador(Optional.of("oid-1"), Optional.of("tid-1"), Optional.of("bearer-1"), Optional.empty());
    }

    public static ContextoLlamador sinOid() {
        return new ContextoLlamador(Optional.empty(), Optional.of("tid-1"), Optional.of("bearer-1"), Optional.empty());
    }

    public static Adjunto adjunto(ClaveAdjunto clave, EstadoIngesta estado, UUID tipo, String ruta,
                                  Map<String, Object> valores) {
        return new Adjunto(clave, NOMBRE, 1024L, MIME, estado, ruta, null, tipo, null, valores, AHORA);
    }

    public static RegistroIngesta registro(ClaveAdjunto clave, EstadoIngesta estado, UUID tipo, String contenedor,
                                           Map<String, Object> valores) {
        return new RegistroIngesta(clave, estado, tipo, contenedor, ruta(clave), "oid-1", NOMBRE, 1024L, MIME, valores,
                null, 0, null, null, null, AHORA, null, null);
    }

    public static RegistroIngesta registro(ClaveAdjunto clave, EstadoIngesta estado, LocalDate fechaDepuracion) {
        return registro(clave, estado, UUID.randomUUID(), ALMACENADOS, Map.of()).conFechaDepuracion(fechaDepuracion);
    }

    /** Tipo descubierto que va a CM al subir, sin retención, revisado y en uso. */
    public static TipoDocumento tipoCm(UUID id, PropiedadCm... propiedades) {
        return tipo(id, TipoDocumento.Momento.AL_SUBIR, null, false, propiedades);
    }

    public static TipoDocumento tipo(UUID id, TipoDocumento.Momento momento, Integer dias, boolean inmutable,
                                     PropiedadCm... propiedades) {
        return new TipoDocumento(id, "PT95", "PT95 - Crédito", TipoDocumento.OrigenTipo.DESCUBIERTO, TIPO_CMIS, "Credito",
                true, true, CARPETA, false, TipoDocumento.Destino.CONTENT_MANAGER, momento, dias, inmutable, true, false,
                Arrays.asList(propiedades));
    }

    /** Tipo local (Solo Blob), sin ItemType. */
    public static TipoDocumento tipoLocal(UUID id, Integer dias) {
        return new TipoDocumento(id, "LOC", "Local", TipoDocumento.OrigenTipo.LOCAL, null, null, false, true, null,
                false, null, null, dias, false, true, false, List.of());
    }

    public static PropiedadCm propiedad(String propiedadId, boolean obligatoria, PropiedadCm.OrigenValor origen,
                                        String nombreLogico, String consulta, String literal, String patron) {
        return new PropiedadCm(UUID.randomUUID(), propiedadId, PropiedadCm.nombreCanonico(propiedadId), "string",
                obligatoria, null, null, null, false, PropiedadCm.Decision.USAR, origen, nombreLogico, consulta, literal,
                patron, null);
    }

    public static PropiedadCm columna(String propiedadId, String nombreLogico) {
        return propiedad(propiedadId, true, PropiedadCm.OrigenValor.COLUMNA, nombreLogico, null, null, null);
    }

    public static DefinicionTipoCmis.Propiedad viva(String id, String tipoDato, boolean obligatoria, Integer largo) {
        return new DefinicionTipoCmis.Propiedad(id, PropiedadCm.nombreCanonico(id), tipoDato, obligatoria, null, largo,
                null, false);
    }

    public static DefinicionTipoCmis definicion(DefinicionTipoCmis.Propiedad... propiedades) {
        return new DefinicionTipoCmis(TIPO_CMIS, "Credito", "PT95 - Crédito", true, "cmis:document",
                Arrays.asList(propiedades));
    }
}
