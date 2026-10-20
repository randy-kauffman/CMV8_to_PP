package com.banco.cmv8.application.catalogo;

import com.banco.cmv8.application.AccesoDenegadoException;
import com.banco.cmv8.application.SolicitudNoEncontradaException;
import com.banco.cmv8.domain.ContextoLlamador;
import com.banco.cmv8.domain.catalogo.TipoDocumento;
import com.banco.cmv8.domain.port.ArchivoDocumental;
import com.banco.cmv8.domain.port.CatalogoTipos;
import com.banco.cmv8.domain.port.ResolutorAs400;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Operación ValidarTipo (ADR-003 §4.4): el "check" para el administrador, con tres severidades. Cruza Content
 * Manager en vivo con el catálogo. No escribe conflictos: informa. Sin id valida todos los tipos en uso
 * (alcance "mapped" de CMCourier: lo único que puede romper una corrida de hoy).
 */
public class ValidarTipo {

    public record Informe(List<AnalizadorCatalogo.Hallazgo> hallazgos) {
        public long criticos() {
            return hallazgos.stream().filter(h -> h.nivel() == AnalizadorCatalogo.Nivel.CRITICAL).count();
        }
        public long avisos() {
            return hallazgos.stream().filter(h -> h.nivel() == AnalizadorCatalogo.Nivel.WARNING).count();
        }
    }

    private final CatalogoTipos catalogo;
    private final CatalogoEnCache cache;
    private final ArchivoDocumental archivo;
    private final ResolutorAs400 as400;

    public ValidarTipo(CatalogoTipos catalogo, CatalogoEnCache cache, ArchivoDocumental archivo, ResolutorAs400 as400) {
        this.catalogo = catalogo;
        this.cache = cache;
        this.archivo = archivo;
        this.as400 = as400;
    }

    public Informe ejecutar(Optional<UUID> tipoId, ContextoLlamador llamador, Logger log) {
        if (!catalogo.puedeAdministrar(llamador)) {
            throw new AccesoDenegadoException("Solo el administrador documental puede validar el catálogo");
        }
        cache.invalidar();
        List<TipoDocumento> tipos = tipoId.isPresent()
                ? List.of(cache.tipo(tipoId.get()).orElseThrow(() -> new SolicitudNoEncontradaException("El tipo no existe")))
                : cache.tiposEnUso();
        List<AnalizadorCatalogo.Hallazgo> hallazgos = new ArrayList<>();
        for (TipoDocumento tipo : tipos) {
            if (!tipo.tieneItemType()) {
                continue;
            }
            Optional<Boolean> carpetaOk = tipo.carpeta() == null || tipo.carpeta().isBlank()
                    ? Optional.empty() : Optional.of(archivo.existeCarpeta(tipo.carpeta()));
            hallazgos.addAll(AnalizadorCatalogo.analizar(tipo, archivo.definicionTipo(tipo.tipoCmisId()), carpetaOk,
                    as400.consultasDeclaradas()));
        }
        Informe informe = new Informe(hallazgos);
        log.info(() -> "ValidarTipo: tipos=" + tipos.size() + " criticos=" + informe.criticos() + " avisos="
                + informe.avisos() + " llamador=" + llamador);
        return informe;
    }
}
