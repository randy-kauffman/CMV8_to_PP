package com.banco.cmv8.application;

import com.banco.cmv8.application.catalogo.CatalogoEnCache;
import com.banco.cmv8.application.catalogo.ResolverPropiedades;
import com.banco.cmv8.domain.Adjunto;
import com.banco.cmv8.domain.ClaveAdjunto;
import com.banco.cmv8.domain.ContextoLlamador;
import com.banco.cmv8.domain.PoliticaSas;
import com.banco.cmv8.domain.RegistroIngesta;
import com.banco.cmv8.domain.SasEmitida;
import com.banco.cmv8.domain.TokenUsuario;
import com.banco.cmv8.domain.ValidacionException;
import com.banco.cmv8.domain.catalogo.TipoDocumento;
import com.banco.cmv8.domain.port.EmisorSas;
import com.banco.cmv8.domain.port.RegistroIngestas;
import com.banco.cmv8.domain.port.RepositorioAdjuntos;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Caso de uso: SAS de subida para N adjuntos de la misma tabla (ADR-003 §4.1). Un solo token OBO de Storage,
 * porque es del usuario y no del archivo, y N SAS. Por cada adjunto, UNA llamada a Dataverse suplantando al
 * usuario (PATCH de la ruta con representación): el 403 es el chequeo de escritura y la respuesta trae la fila.
 * Un adjunto que falla no arrastra al resto: su error viaja en el resultado.
 */
public class EmitirSasLote {

    public static final int BLOQUE_BYTES = 4 * 1024 * 1024;

    public record Item(UUID id, String nombreArchivo, Long tamanoBytes, String contentType) {
    }

    public record Peticion(String tabla, List<Item> items) {
    }

    /** uploadUrl es secreto: el toString no lo incluye. */
    public record ItemResultado(UUID id, String uploadUrl, String rutaBlob, OffsetDateTime expiraUtc, String error,
                                int codigoError) {
        public static ItemResultado ok(UUID id, String url, String ruta, OffsetDateTime expira) {
            return new ItemResultado(id, url, ruta, expira, null, 0);
        }
        public static ItemResultado fallo(UUID id, String error, int codigo) {
            return new ItemResultado(id, null, null, null, error, codigo);
        }
        public boolean exitoso() {
            return error == null;
        }
        @Override
        public String toString() {
            return "ItemResultado[id=" + id + ", rutaBlob=" + rutaBlob + ", error=" + error + "]";
        }
    }

    /** tokenStorage es secreto: el toString no lo incluye. */
    public record Resultado(List<ItemResultado> items, int bloqueBytes, String versionServicio, String tokenStorage,
                            OffsetDateTime tokenStorageExpiraUtc) {
        @Override
        public String toString() {
            return "Resultado[items=" + items.size() + ", tokenStorage=" + (tokenStorage == null ? "-" : "***") + "]";
        }
    }

    private final PoliticaSas politica;
    private final RepositorioAdjuntos repositorio;
    private final RegistroIngestas registros;
    private final CatalogoEnCache catalogo;
    private final ResolverPropiedades resolver;
    private final EmisorSas emisor;
    private final VinculoUsuario vinculo;
    private final String contenedorIngesta;
    private final Duration vigencia;
    private final int bloqueBytes;
    private final int loteMaximo;
    private final Clock reloj;

    public EmitirSasLote(PoliticaSas politica, RepositorioAdjuntos repositorio, RegistroIngestas registros,
                         CatalogoEnCache catalogo, ResolverPropiedades resolver, EmisorSas emisor, VinculoUsuario vinculo,
                         String contenedorIngesta, Duration vigencia, int bloqueBytes, int loteMaximo, Clock reloj) {
        this.politica = politica;
        this.repositorio = repositorio;
        this.registros = registros;
        this.catalogo = catalogo;
        this.resolver = resolver;
        this.emisor = emisor;
        this.vinculo = vinculo;
        this.contenedorIngesta = contenedorIngesta;
        this.vigencia = vigencia;
        this.bloqueBytes = bloqueBytes;
        this.loteMaximo = loteMaximo;
        this.reloj = reloj;
    }

    public Resultado ejecutar(Peticion peticion, ContextoLlamador llamador, Logger log) {
        if (peticion == null || peticion.items() == null || peticion.items().isEmpty()) {
            throw new ValidacionException("El lote no tiene adjuntos");
        }
        if (peticion.items().size() > loteMaximo) {
            throw new ValidacionException("El lote supera el máximo de " + loteMaximo + " adjuntos");
        }
        // Valida el nombre de tabla (regex) antes de tocar nada.
        new ClaveAdjunto(peticion.tabla(), UUID.randomUUID());
        vinculo.exigirIdentidad(llamador);
        // OBO una sola vez: si Entra no entrega el token, ninguna fila cambia.
        Optional<TokenUsuario> token = vinculo.tokenStorage(llamador);

        List<ItemResultado> resultados = new ArrayList<>();
        Set<UUID> vistos = new HashSet<>();
        String version = null;
        for (Item item : peticion.items()) {
            if (item == null || item.id() == null) {
                resultados.add(ItemResultado.fallo(null, "Falta el id del adjunto", 400));
                continue;
            }
            if (!vistos.add(item.id())) {
                resultados.add(ItemResultado.fallo(item.id(), "Adjunto repetido en el lote", 400));
                continue;
            }
            ClaveAdjunto clave = new ClaveAdjunto(peticion.tabla(), item.id());
            try {
                Emitida e = emitirUno(clave, item, llamador, log);
                version = version == null ? e.versionServicio() : version;
                resultados.add(e.item());
            } catch (ValidacionException e) {
                resultados.add(ItemResultado.fallo(item.id(), e.getMessage(), 400));
            } catch (AccesoDenegadoException e) {
                log.warning(() -> "SAS denegada. adjunto=" + clave + " llamador=" + llamador);
                resultados.add(ItemResultado.fallo(item.id(), e.getMessage(), 403));
            } catch (SolicitudNoEncontradaException e) {
                resultados.add(ItemResultado.fallo(item.id(), e.getMessage(), 404));
            } catch (EstadoInvalidoException e) {
                resultados.add(ItemResultado.fallo(item.id(), e.getMessage(), 409));
            } catch (IntercambioTokenException | NoAutenticadoException e) {
                throw e; // de todo el lote, no de un item
            } catch (RuntimeException e) {
                // Falla técnica de una dependencia (AS400, Storage): este item no sale, el resto sigue.
                log.log(java.util.logging.Level.SEVERE, "Falla técnica al emitir la SAS. adjunto=" + clave, e);
                resultados.add(ItemResultado.fallo(item.id(), "Falla técnica al emitir la SAS; reintentá más tarde", 502));
            }
        }
        final int ok = (int) resultados.stream().filter(ItemResultado::exitoso).count();
        log.info(() -> "Lote de SAS emitido. tabla=" + peticion.tabla() + " ok=" + ok + " de=" + resultados.size()
                + " ligadaUsuario=" + token.isPresent() + " llamador=" + llamador);
        return new Resultado(resultados, bloqueBytes, version, token.map(TokenUsuario::valor).orElse(null),
                token.map(TokenUsuario::expira).orElse(null));
    }

    private record Emitida(ItemResultado item, String versionServicio) {
    }

    private Emitida emitirUno(ClaveAdjunto clave, Item item, ContextoLlamador llamador, Logger log) {
        politica.validarTamano(item.tamanoBytes());
        String ruta = politica.construirRuta(clave, item.nombreArchivo());
        // La única llamada de autorización: escribe la ruta suplantando; 403 → no puede; 404 → no existe.
        // Tradeoff declarado (ADR-003 §9): la ruta se escribe ANTES de conocer el estado. Si la fila no admite
        // subida, la ruta de Dataverse queda desalineada hasta el próximo resultado; la verdad operativa (ruta real
        // del blob) vive en la Azure Table y es la que usan el worker, la lectura y la depuración.
        Adjunto adjunto = repositorio.registrarRutaSuplantando(clave, ruta, llamador);
        if (!adjunto.estado().admiteSubida()) {
            throw new EstadoInvalidoException("El adjunto está en estado " + adjunto.estado()
                    + " y no admite una nueva subida");
        }
        if (!adjunto.tieneTipo()) {
            throw new ValidacionException("El adjunto no tiene tipo de documento");
        }
        Optional<TipoDocumento> tipo = catalogo.tipo(adjunto.tipoDocumentoId());
        if (tipo.isEmpty()) {
            throw new ValidacionException("El tipo de documento del adjunto no existe en el catálogo");
        }
        if (!tipo.get().enUso()) {
            throw new ValidacionException("El tipo de documento " + tipo.get().nombre() + " no está en uso");
        }
        // Existencia contra el AS400 antes de subir, solo para las consultas marcadas como rápidas (M8).
        List<String> previas = resolver.validarAntesDeSubir(tipo.get(), clave, adjunto.valores());
        if (!previas.isEmpty()) {
            throw new ValidacionException(String.join("; ", previas));
        }
        SasEmitida sas = emisor.emitirSubida(contenedorIngesta, ruta, vigencia, llamador);
        registros.guardar(RegistroIngesta.alEmitirSas(adjunto, contenedorIngesta, ruta,
                llamador.objectId().orElse(null), reloj.instant()));
        log.info(() -> "SAS de subida emitida. adjunto=" + clave + " rutaBlob=" + ruta + " expira=" + sas.expira());
        return new Emitida(ItemResultado.ok(clave.id(), sas.url(), ruta, sas.expira()), sas.versionServicio());
    }
}
