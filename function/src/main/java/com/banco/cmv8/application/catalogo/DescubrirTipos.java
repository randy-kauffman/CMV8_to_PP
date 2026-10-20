package com.banco.cmv8.application.catalogo;

import com.banco.cmv8.application.AccesoDenegadoException;
import com.banco.cmv8.domain.ContextoLlamador;
import com.banco.cmv8.domain.catalogo.DefinicionTipoCmis;
import com.banco.cmv8.domain.catalogo.PropiedadCm;
import com.banco.cmv8.domain.catalogo.TipoDocumento;
import com.banco.cmv8.domain.port.ArchivoDocumental;
import com.banco.cmv8.domain.port.CatalogoTipos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Operaciones DescubrirTipos y ActualizarTipos (ADR-003 §4.4 y §6): el catálogo no se tipea, se descubre de
 * Content Manager. Escribe solo lo descubierto; conserva las decisiones humanas; un tipo tocado vuelve a
 * "no revisado" con la lista de cambios; lo que el servidor dejó de publicar se marca ausente, no se borra;
 * una carpeta fijada a mano no se pisa. ActualizarTipos es lo mismo acotado a los tipos en uso.
 */
public class DescubrirTipos {

    public record Resumen(int nuevos, int actualizados, int sinCambios, int ausentes, List<String> avisos) {
    }

    private final ArchivoDocumental archivo;
    private final CatalogoTipos catalogo;
    private final CatalogoEnCache cache;
    private final ReglasDescubrimiento reglas;

    public DescubrirTipos(ArchivoDocumental archivo, CatalogoTipos catalogo, CatalogoEnCache cache,
                          ReglasDescubrimiento reglas) {
        this.archivo = archivo;
        this.catalogo = catalogo;
        this.cache = cache;
        this.reglas = reglas;
    }

    /** @param soloEnUso true = ActualizarTipos (solo los tipos en uso); false = DescubrirTipos (todo el árbol) */
    public Resumen ejecutar(boolean soloEnUso, ContextoLlamador llamador, Logger log) {
        if (!catalogo.puedeAdministrar(llamador)) {
            throw new AccesoDenegadoException("Solo el administrador documental puede descubrir o actualizar tipos");
        }
        List<DefinicionTipoCmis> publicados = archivo.descubrirTipos();
        Map<String, DefinicionTipoCmis> porId = new LinkedHashMap<>();
        publicados.forEach(t -> porId.put(t.tipoId(), t));

        Map<String, TipoDocumento> guardados = new HashMap<>();
        for (TipoDocumento t : catalogo.listar()) {
            if (t.origen() == TipoDocumento.OrigenTipo.DESCUBIERTO && t.tipoCmisId() != null) {
                guardados.put(t.tipoCmisId(), t);
            }
        }
        // Códigos compartidos: quién se queda con el código.
        Map<String, List<DefinicionTipoCmis>> porCodigo = new HashMap<>();
        Map<String, String> codigoDe = new HashMap<>();
        for (DefinicionTipoCmis t : publicados) {
            reglas.codigoCorto(t).ifPresent(c -> {
                codigoDe.put(t.tipoId(), c);
                porCodigo.computeIfAbsent(c, k -> new ArrayList<>()).add(t);
            });
        }

        int nuevos = 0;
        int actualizados = 0;
        int sinCambios = 0;
        List<String> avisos = new ArrayList<>();
        for (DefinicionTipoCmis vivo : publicados) {
            TipoDocumento guardado = guardados.get(vivo.tipoId());
            if (soloEnUso && (guardado == null || !guardado.enUso())) {
                continue;
            }
            String codigo = codigoDe.get(vivo.tipoId());
            if (codigo != null && porCodigo.get(codigo).size() > 1
                    && !ReglasDescubrimiento.esGanador(codigo, vivo, porCodigo.get(codigo))) {
                avisos.add("ID corto compartido " + codigo + ": " + vivo.tipoId() + " queda sin código; gana otro tipo");
                codigo = null;
            }
            if (guardado == null) {
                TipoDocumento nuevo = new TipoDocumento(null, codigo, vivo.nombreVisible(),
                        TipoDocumento.OrigenTipo.DESCUBIERTO, vivo.tipoId(), vivo.localName(), vivo.versionable(),
                        false, reglas.carpetaDerivada(vivo), false, TipoDocumento.Destino.CONTENT_MANAGER,
                        TipoDocumento.Momento.AL_SUBIR, null, false, false, false, List.of());
                UUID id = catalogo.guardarTipoDescubierto(nuevo, List.of(), true);
                catalogo.guardarPropiedadesDescubiertas(id, vivo.propiedades().stream().map(reglas::propiedadNueva).toList());
                nuevos++;
                continue;
            }
            List<String> cambios = reglas.cambios(guardado, vivo);
            if (guardado.ausenteEnServidor()) {
                cambios.add(0, "volvió a publicarse en el servidor");
            }
            if (cambios.isEmpty() && !guardado.ausenteEnServidor()) {
                sinCambios++;
                continue;
            }
            TipoDocumento actualizado = new TipoDocumento(guardado.id(), guardado.codigo(), vivo.nombreVisible(),
                    TipoDocumento.OrigenTipo.DESCUBIERTO, vivo.tipoId(), vivo.localName(), vivo.versionable(),
                    guardado.enUso(), guardado.carpetaManual() ? guardado.carpeta() : reglas.carpetaDerivada(vivo),
                    guardado.carpetaManual(), guardado.destino(), guardado.momento(), guardado.diasRetencion(),
                    guardado.inmutable(), false, false, guardado.propiedades());
            catalogo.guardarTipoDescubierto(actualizado, cambios, false);
            List<PropiedadCm> propiedades = new ArrayList<>();
            for (DefinicionTipoCmis.Propiedad p : vivo.propiedades()) {
                Optional<PropiedadCm> actual = guardado.propiedades().stream()
                        .filter(x -> x.propiedadId().equals(p.id())).findFirst();
                propiedades.add(actual.map(a -> reglas.propiedadActualizada(a, p)).orElseGet(() -> reglas.propiedadNueva(p)));
            }
            catalogo.guardarPropiedadesDescubiertas(guardado.id(), propiedades);
            actualizados++;
        }

        int ausentes = 0;
        if (!soloEnUso) {
            for (TipoDocumento guardado : guardados.values()) {
                if (!porId.containsKey(guardado.tipoCmisId()) && !guardado.ausenteEnServidor()) {
                    TipoDocumento ausente = new TipoDocumento(guardado.id(), guardado.codigo(), guardado.nombre(),
                            TipoDocumento.OrigenTipo.DESCUBIERTO, guardado.tipoCmisId(), guardado.localName(),
                            guardado.versionable(), guardado.enUso(), guardado.carpeta(), guardado.carpetaManual(),
                            guardado.destino(), guardado.momento(), guardado.diasRetencion(), guardado.inmutable(),
                            false, true, guardado.propiedades());
                    catalogo.guardarTipoDescubierto(ausente, List.of("el servidor ya no publica este tipo"), false);
                    ausentes++;
                }
            }
        }
        cache.invalidar();
        final Resumen resumen = new Resumen(nuevos, actualizados, sinCambios, ausentes, avisos);
        log.info(() -> (soloEnUso ? "ActualizarTipos" : "DescubrirTipos") + ": publicados=" + publicados.size()
                + " nuevos=" + resumen.nuevos() + " actualizados=" + resumen.actualizados() + " sinCambios="
                + resumen.sinCambios() + " ausentes=" + resumen.ausentes() + " avisos=" + resumen.avisos().size()
                + " llamador=" + llamador);
        return resumen;
    }
}
