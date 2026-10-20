package com.banco.cmv8.config;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.banco.cmv8.adapters.out.as400.ConsultasAs400;
import com.banco.cmv8.adapters.out.as400.FuenteConexionesAs400;
import com.banco.cmv8.adapters.out.as400.ResolutorAs400Jt400;
import com.banco.cmv8.adapters.out.blob.AlmacenAdjuntosBlob;
import com.banco.cmv8.adapters.out.blob.EmisorSasBlob;
import com.banco.cmv8.adapters.out.cmis.ArchivoDocumentalCmis;
import com.banco.cmv8.adapters.out.dataverse.CatalogoTiposDataverse;
import com.banco.cmv8.adapters.out.dataverse.ClienteDataverse;
import com.banco.cmv8.adapters.out.dataverse.RepositorioAdjuntosDataverse;
import com.banco.cmv8.adapters.out.entra.IntercambiadorTokenObo;
import com.banco.cmv8.adapters.out.tabla.AgendaDepuracionTabla;
import com.banco.cmv8.adapters.out.tabla.RegistroIngestasTabla;
import com.banco.cmv8.application.ArchivarAlmacenado;
import com.banco.cmv8.application.ConsultarAs400;
import com.banco.cmv8.application.Depurar;
import com.banco.cmv8.application.EmitirSasLectura;
import com.banco.cmv8.application.EmitirSasLote;
import com.banco.cmv8.application.ExtenderRetencion;
import com.banco.cmv8.application.IngestarArchivo;
import com.banco.cmv8.application.ProcesarIngesta;
import com.banco.cmv8.application.Reanudar;
import com.banco.cmv8.application.ReencolarEstancadas;
import com.banco.cmv8.application.RegistrarResultadoEscaneo;
import com.banco.cmv8.application.VinculoUsuario;
import com.banco.cmv8.application.catalogo.CatalogoEnCache;
import com.banco.cmv8.application.catalogo.Conflictos;
import com.banco.cmv8.application.catalogo.DescubrirTipos;
import com.banco.cmv8.application.catalogo.ReanudarConflicto;
import com.banco.cmv8.application.catalogo.ReglasDescubrimiento;
import com.banco.cmv8.application.catalogo.ResolverPropiedades;
import com.banco.cmv8.application.catalogo.ValidarTabla;
import com.banco.cmv8.application.catalogo.ValidarTipo;
import com.banco.cmv8.application.catalogo.VigilarCatalogo;
import com.banco.cmv8.domain.PoliticaSas;
import com.banco.cmv8.domain.port.AgendaDepuracion;
import com.banco.cmv8.domain.port.AlmacenAdjuntos;
import com.banco.cmv8.domain.port.ArchivoDocumental;
import com.banco.cmv8.domain.port.CatalogoTipos;
import com.banco.cmv8.domain.port.EmisorSas;
import com.banco.cmv8.domain.port.RegistroIngestas;
import com.banco.cmv8.domain.port.RepositorioAdjuntos;
import com.banco.cmv8.domain.port.ResolutorAs400;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Raíz de composición de la v2 (ADR-003): conecta adaptadores y casos de uso. Todo es perezoso y singleton por
 * proceso, para que un endpoint que no usa CMIS o AS400 no falle por falta de esa configuración.
 */
public final class Fabrica {

    public static final String VERSION = "0.2.0";

    private static final Logger LOG = Logger.getLogger(Fabrica.class.getName());
    private static final Fabrica INSTANCIA = new Fabrica(Configuracion.desdeEntorno());

    public static Fabrica get() {
        return INSTANCIA;
    }

    /** Valor perezoso thread-safe. */
    private static final class Perezoso<T> {
        private final Supplier<T> proveedor;
        private volatile T valor;

        Perezoso(Supplier<T> proveedor) {
            this.proveedor = proveedor;
        }

        T get() {
            T v = valor;
            if (v == null) {
                synchronized (this) {
                    v = valor;
                    if (v == null) {
                        v = proveedor.get();
                        valor = v;
                    }
                }
            }
            return v;
        }
    }

    private final Configuracion config;
    private final Clock reloj = Clock.systemUTC();

    // ------------------------------------------------------------------ infraestructura
    private final Perezoso<TokenCredential> credencial;
    private final Perezoso<PoliticaSas> politica;
    private final Perezoso<BlobServiceClient> servicioBlob;
    private final Perezoso<ClienteDataverse> clienteDataverse;

    // ------------------------------------------------------------------ puertos
    private final Perezoso<RepositorioAdjuntos> repositorio;
    private final Perezoso<CatalogoTipos> catalogo;
    private final Perezoso<RegistroIngestas> registros;
    private final Perezoso<AgendaDepuracion> agenda;
    private final Perezoso<EmisorSas> emisorSas;
    private final Perezoso<AlmacenAdjuntos> almacen;
    private final Perezoso<ArchivoDocumental> archivo;
    private final Perezoso<ResolutorAs400> as400;
    private final Perezoso<VinculoUsuario> vinculoUsuario;

    // ------------------------------------------------------------------ apoyo
    private final Perezoso<CatalogoEnCache> cache;
    private final Perezoso<Conflictos> conflictos;
    private final Perezoso<ResolverPropiedades> resolver;

    // ------------------------------------------------------------------ casos de uso
    private final Perezoso<EmitirSasLote> emitirSasLote;
    private final Perezoso<EmitirSasLectura> emitirSasLectura;
    private final Perezoso<ProcesarIngesta> procesarIngesta;
    private final Perezoso<RegistrarResultadoEscaneo> registrarResultadoEscaneo;
    private final Perezoso<ReencolarEstancadas> reencolarEstancadas;
    private final Perezoso<ArchivarAlmacenado> archivarAlmacenado;
    private final Perezoso<Reanudar> reanudar;
    private final Perezoso<IngestarArchivo> ingestarArchivo;
    private final Perezoso<Depurar> depurar;
    private final Perezoso<ExtenderRetencion> extenderRetencion;
    private final Perezoso<DescubrirTipos> descubrirTipos;
    private final Perezoso<ValidarTipo> validarTipo;
    private final Perezoso<ValidarTabla> validarTabla;
    private final Perezoso<ReanudarConflicto> reanudarConflicto;
    private final Perezoso<VigilarCatalogo> vigilarCatalogo;
    private final Perezoso<ConsultarAs400> consultarAs400;

    Fabrica(Configuracion config) {
        this.config = config;
        if (config.modoAutorizacion() == Configuracion.ModoAutorizacion.NINGUNA) {
            LOG.warning("AUTORIZACION_MODO=ninguna ya no existe en v2: Dataverse decide siempre (ADR-003). Se ignora.");
        }
        credencial = new Perezoso<>(() -> {
            DefaultAzureCredentialBuilder b = new DefaultAzureCredentialBuilder();
            config.azureClientId().ifPresent(b::managedIdentityClientId);
            return b.build();
        });
        politica = new Perezoso<>(() -> new PoliticaSas(config.tamanoMaximoBytes(), reloj));
        servicioBlob = new Perezoso<>(() -> new BlobServiceClientBuilder()
                .endpoint(config.blobEndpoint())
                .credential(credencial.get())
                .buildClient());
        clienteDataverse = new Perezoso<>(() -> new ClienteDataverse(credencial.get(), config.dataverseUrl()));

        repositorio = new Perezoso<>(() -> new RepositorioAdjuntosDataverse(clienteDataverse.get()));
        catalogo = new Perezoso<>(() -> new CatalogoTiposDataverse(clienteDataverse.get()));
        registros = new Perezoso<>(() -> RegistroIngestasTabla.crear(config.tableEndpoint(), config.tablaIngesta(),
                credencial.get()));
        agenda = new Perezoso<>(() -> AgendaDepuracionTabla.crear(config.tableEndpoint(), config.tablaDepuracion(),
                credencial.get()));
        emisorSas = new Perezoso<>(() -> new EmisorSasBlob(servicioBlob.get(), config.blobEndpoint(), politica.get(),
                config.sasLigadaUsuario()));
        almacen = new Perezoso<>(() -> new AlmacenAdjuntosBlob(servicioBlob.get()));
        // Diferidos: la configuración de CMIS y AS400 se exige en la primera llamada real, no al armar los casos de uso.
        Perezoso<ArchivoDocumental> archivoReal = new Perezoso<>(() -> new ArchivoDocumentalCmis(
                new ArchivoDocumentalCmis.ParametrosCmis(config.cmisUrl(), config.cmisUsuario(), config.cmisClave(),
                        config.cmisRepositorio())));
        archivo = new Perezoso<>(() -> Diferidos.archivo(archivoReal::get));
        // Un pool por proceso: el arranque en frío paga una conexión; las invocaciones calientes, ~120 ms por consulta.
        Perezoso<ResolutorAs400> as400Real = new Perezoso<>(() -> new ResolutorAs400Jt400(
                FuenteConexionesAs400.crear(
                        new FuenteConexionesAs400.ParametrosAs400(config.as400Host(), config.as400Usuario(),
                                config.as400Clave(), config.as400Seguro()),
                        new FuenteConexionesAs400.ParametrosPool(config.as400PoolMaximo(), 1,
                                config.as400PoolVidaMaxima())),
                ConsultasAs400.parsear(config.as400ConsultasJson(), config.as400SqlCliente())));
        as400 = new Perezoso<>(() -> Diferidos.as400(as400Real::get));
        vinculoUsuario = new Perezoso<>(this::crearVinculoUsuario);

        cache = new Perezoso<>(() -> new CatalogoEnCache(catalogo.get(), config.catalogoCache(), reloj));
        conflictos = new Perezoso<>(() -> new Conflictos(catalogo.get(), registros.get()));
        resolver = new Perezoso<>(() -> new ResolverPropiedades(as400.get()));

        emitirSasLote = new Perezoso<>(() -> new EmitirSasLote(politica.get(), repositorio.get(), registros.get(),
                cache.get(), resolver.get(), emisorSas.get(), vinculoUsuario.get(), config.contenedor(),
                config.vigenciaSasSubida(), config.bloqueBytes(), config.loteMaximo(), reloj));
        consultarAs400 = new Perezoso<>(() -> new ConsultarAs400(as400.get()));
        emitirSasLectura = new Perezoso<>(() -> new EmitirSasLectura(repositorio.get(), registros.get(), almacen.get(),
                emisorSas.get(), vinculoUsuario.get(), config.contenedor(), config.contenedorAlmacenados(),
                config.vigenciaSasLectura()));
        procesarIngesta = new Perezoso<>(() -> new ProcesarIngesta(registros.get(), repositorio.get(), cache.get(),
                almacen.get(), archivo.get(), resolver.get(), conflictos.get(), agenda.get(), config.contenedor(),
                config.contenedorAlmacenados(), config.escaneoRequerido(), config.wormHabilitado(),
                config.wormBloquear(), config.observadaDias(), reloj));
        registrarResultadoEscaneo = new Perezoso<>(() -> new RegistrarResultadoEscaneo(registros.get(),
                repositorio.get(), almacen.get(), config.contenedor(), reloj));
        reencolarEstancadas = new Perezoso<>(() -> new ReencolarEstancadas(registros.get(), almacen.get(),
                config.antiguedadEstancada()));
        archivarAlmacenado = new Perezoso<>(() -> new ArchivarAlmacenado(repositorio.get(), registros.get(),
                cache.get(), vinculoUsuario.get()));
        reanudar = new Perezoso<>(() -> new Reanudar(repositorio.get(), registros.get(), agenda.get(),
                vinculoUsuario.get()));
        ingestarArchivo = new Perezoso<>(() -> new IngestarArchivo(politica.get(), repositorio.get(), registros.get(),
                cache.get(), almacen.get(), config.contenedor(), reloj));
        depurar = new Perezoso<>(() -> new Depurar(agenda.get(), registros.get(), repositorio.get(), almacen.get(), reloj));
        extenderRetencion = new Perezoso<>(() -> new ExtenderRetencion(repositorio.get(), registros.get(), agenda.get(),
                almacen.get(), cache.get(), catalogo.get(), config.wormHabilitado(), config.wormBloquear(), reloj));
        descubrirTipos = new Perezoso<>(() -> new DescubrirTipos(archivo.get(), catalogo.get(), cache.get(),
                new ReglasDescubrimiento(config.cmisPropiedadIdCorto(), config.cmisCarpetaPlantilla())));
        validarTipo = new Perezoso<>(() -> new ValidarTipo(catalogo.get(), cache.get(), archivo.get(), as400.get()));
        validarTabla = new Perezoso<>(() -> new ValidarTabla(repositorio.get(), cache.get()));
        reanudarConflicto = new Perezoso<>(() -> new ReanudarConflicto(catalogo.get(), cache.get(), registros.get()));
        vigilarCatalogo = new Perezoso<>(() -> new VigilarCatalogo(cache.get(), archivo.get(), as400.get(),
                conflictos.get()));
    }

    /** SAS ligada al usuario (S1b): el broker devuelve también el token de Storage del usuario (OBO). */
    private VinculoUsuario crearVinculoUsuario() {
        if (!config.sasLigadaUsuario()) {
            return VinculoUsuario.desactivado();
        }
        // El token debe sobrevivir a la SAS más larga que acompaña, con 5 min de margen.
        Duration vigenciaMinima = (config.vigenciaSasSubida().compareTo(config.vigenciaSasLectura()) >= 0
                ? config.vigenciaSasSubida() : config.vigenciaSasLectura()).plusMinutes(5);
        return VinculoUsuario.activo(crearIntercambiadorObo(vigenciaMinima));
    }

    /**
     * Credencial del broker para el OBO: la identidad administrada (credencial federada en la app registration)
     * salvo que haya un secreto configurado, que es el camino de desarrollo local.
     */
    private IntercambiadorTokenObo crearIntercambiadorObo(Duration vigenciaMinima) {
        Optional<String> secreto = config.brokerClientSecret();
        if (secreto.isPresent()) {
            LOG.warning("BROKER_CLIENT_SECRET presente: OBO con secreto de cliente (solo desarrollo local). "
                    + "En Azure, quitarlo y usar la credencial federada de la identidad administrada.");
            return IntercambiadorTokenObo.conSecreto(config.brokerClientId(), config.brokerTenantId(), secreto.get(),
                    vigenciaMinima);
        }
        String identidad = config.azureClientId().orElseThrow(() -> new IllegalStateException(
                "OBO sin credencial: falta AZURE_CLIENT_ID (identidad administrada con credencial federada en la app "
                        + "del broker) o, solo en local, BROKER_CLIENT_SECRET"));
        LOG.info("OBO del broker con la identidad administrada (credencial federada), sin secreto");
        return IntercambiadorTokenObo.conIdentidadAdministrada(config.brokerClientId(), config.brokerTenantId(),
                identidad, vigenciaMinima);
    }

    public Configuracion configuracion() {
        return config;
    }

    public EmitirSasLote emitirSasLote() {
        return emitirSasLote.get();
    }

    public EmitirSasLectura emitirSasLectura() {
        return emitirSasLectura.get();
    }

    public ProcesarIngesta procesarIngesta() {
        return procesarIngesta.get();
    }

    public RegistrarResultadoEscaneo registrarResultadoEscaneo() {
        return registrarResultadoEscaneo.get();
    }

    public ReencolarEstancadas reencolarEstancadas() {
        return reencolarEstancadas.get();
    }

    public ArchivarAlmacenado archivarAlmacenado() {
        return archivarAlmacenado.get();
    }

    public Reanudar reanudar() {
        return reanudar.get();
    }

    public IngestarArchivo ingestarArchivo() {
        return ingestarArchivo.get();
    }

    public Depurar depurar() {
        return depurar.get();
    }

    public ExtenderRetencion extenderRetencion() {
        return extenderRetencion.get();
    }

    public DescubrirTipos descubrirTipos() {
        return descubrirTipos.get();
    }

    public ValidarTipo validarTipo() {
        return validarTipo.get();
    }

    public ValidarTabla validarTabla() {
        return validarTabla.get();
    }

    public ReanudarConflicto reanudarConflicto() {
        return reanudarConflicto.get();
    }

    public VigilarCatalogo vigilarCatalogo() {
        return vigilarCatalogo.get();
    }

    public ConsultarAs400 consultarAs400() {
        return consultarAs400.get();
    }
}
