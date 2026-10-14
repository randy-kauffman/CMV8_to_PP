package com.banco.cmv8.config;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.banco.cmv8.adapters.out.as400.FuenteConexionesAs400;
import com.banco.cmv8.adapters.out.as400.ResolutorClienteJt400;
import com.banco.cmv8.adapters.out.blob.AlmacenAdjuntosBlob;
import com.banco.cmv8.adapters.out.blob.EmisorSasBlob;
import com.banco.cmv8.adapters.out.cmis.ArchivoDocumentalCmis;
import com.banco.cmv8.adapters.out.cmis.MapeoPropiedadesCmis;
import com.banco.cmv8.adapters.out.dataverse.AutorizadorPermisivo;
import com.banco.cmv8.adapters.out.dataverse.AutorizadorSolicitudDataverse;
import com.banco.cmv8.adapters.out.dataverse.ClienteDataverse;
import com.banco.cmv8.adapters.out.dataverse.RepositorioSolicitudesDataverse;
import com.banco.cmv8.adapters.out.entra.IntercambiadorTokenObo;
import com.banco.cmv8.adapters.out.memoria.RepositorioSinDataverse;
import com.banco.cmv8.application.EmitirSasLectura;
import com.banco.cmv8.application.EmitirSasSubida;
import com.banco.cmv8.application.ProcesarIngesta;
import com.banco.cmv8.application.ReencolarEstancadas;
import com.banco.cmv8.application.RegistrarResultadoEscaneo;
import com.banco.cmv8.application.ResolverCliente;
import com.banco.cmv8.application.VinculoUsuario;
import com.banco.cmv8.domain.PoliticaSas;
import com.banco.cmv8.domain.port.AlmacenAdjuntos;
import com.banco.cmv8.domain.port.ArchivoDocumental;
import com.banco.cmv8.domain.port.AutorizadorSolicitud;
import com.banco.cmv8.domain.port.EmisorSas;
import com.banco.cmv8.domain.port.RepositorioSolicitudes;
import com.banco.cmv8.domain.port.ResolutorCliente;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Raíz de composición: conecta adaptadores y casos de uso. Todo es perezoso y singleton por proceso,
 * para que un endpoint que no usa CMIS o AS400 no falle por falta de esa configuración.
 */
public final class Fabrica {

    public static final String VERSION = "0.1.0";

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

    // ------------------------------------------------------------------ infraestructura
    private final Perezoso<TokenCredential> credencial;
    private final Perezoso<PoliticaSas> politica;
    private final Perezoso<BlobServiceClient> servicioBlob;
    private final Perezoso<BlobContainerClient> contenedor;
    private final Perezoso<ClienteDataverse> clienteDataverse;

    // ------------------------------------------------------------------ puertos
    private final Perezoso<RepositorioSolicitudes> repositorio;
    private final Perezoso<AutorizadorSolicitud> autorizador;
    private final Perezoso<EmisorSas> emisorSas;
    private final Perezoso<AlmacenAdjuntos> almacen;
    private final Perezoso<ArchivoDocumental> archivo;
    private final Perezoso<ResolutorCliente> resolutor;
    private final Perezoso<VinculoUsuario> vinculoUsuario;

    // ------------------------------------------------------------------ casos de uso
    private final Perezoso<EmitirSasSubida> emitirSasSubida;
    private final Perezoso<EmitirSasLectura> emitirSasLectura;
    private final Perezoso<ProcesarIngesta> procesarIngesta;
    private final Perezoso<ReencolarEstancadas> reencolarEstancadas;
    private final Perezoso<RegistrarResultadoEscaneo> registrarResultadoEscaneo;
    private final Perezoso<ResolverCliente> resolverCliente;

    Fabrica(Configuracion config) {
        this.config = config;

        credencial = new Perezoso<>(() -> {
            DefaultAzureCredentialBuilder b = new DefaultAzureCredentialBuilder();
            config.azureClientId().ifPresent(b::managedIdentityClientId);
            return b.build();
        });
        politica = new Perezoso<>(() -> new PoliticaSas(config.tamanoMaximoBytes(), Clock.systemUTC()));
        servicioBlob = new Perezoso<>(() -> new BlobServiceClientBuilder()
                .endpoint(config.blobEndpoint())
                .credential(credencial.get())
                .buildClient());
        contenedor = new Perezoso<>(() -> servicioBlob.get().getBlobContainerClient(config.contenedor()));
        clienteDataverse = new Perezoso<>(() -> new ClienteDataverse(credencial.get(), config.dataverseUrl()));

        repositorio = new Perezoso<>(this::crearRepositorio);
        autorizador = new Perezoso<>(this::crearAutorizador);
        emisorSas = new Perezoso<>(() -> new EmisorSasBlob(servicioBlob.get(), config.blobEndpoint(),
                config.contenedor(), politica.get(), config.sasLigadaUsuario()));
        almacen = new Perezoso<>(() -> new AlmacenAdjuntosBlob(contenedor.get()));
        archivo = new Perezoso<>(() -> new ArchivoDocumentalCmis(
                new ArchivoDocumentalCmis.ParametrosCmis(config.cmisUrl(), config.cmisUsuario(), config.cmisClave(),
                        config.cmisRepositorio(), config.cmisCarpeta(), config.cmisTipo()),
                new MapeoPropiedadesCmis(config.cmisPropiedadesJson())));
        // Un pool por proceso: el arranque en frío paga una conexión; las invocaciones calientes, ~120 ms por consulta.
        resolutor = new Perezoso<>(() -> new ResolutorClienteJt400(
                FuenteConexionesAs400.crear(
                        new FuenteConexionesAs400.ParametrosAs400(config.as400Host(), config.as400Usuario(),
                                config.as400Clave(), config.as400Seguro()),
                        new FuenteConexionesAs400.ParametrosPool(config.as400PoolMaximo(), 1,
                                config.as400PoolVidaMaxima())),
                config.as400SqlCliente()));

        vinculoUsuario = new Perezoso<>(this::crearVinculoUsuario);

        emitirSasSubida = new Perezoso<>(() -> new EmitirSasSubida(politica.get(), autorizador.get(),
                repositorio.get(), emisorSas.get(), config.vigenciaSasSubida(), config.bloqueBytes(),
                vinculoUsuario.get()));
        emitirSasLectura = new Perezoso<>(() -> new EmitirSasLectura(autorizador.get(), repositorio.get(),
                almacen.get(), emisorSas.get(), config.vigenciaSasLectura(), vinculoUsuario.get()));
        procesarIngesta = new Perezoso<>(() -> new ProcesarIngesta(repositorio.get(), almacen.get(), archivo.get(),
                resolutor.get(), config.escaneoRequerido()));
        reencolarEstancadas = new Perezoso<>(() -> new ReencolarEstancadas(repositorio.get(),
                config.antiguedadEstancada()));
        registrarResultadoEscaneo = new Perezoso<>(() -> new RegistrarResultadoEscaneo(repositorio.get(),
                almacen.get()));
        resolverCliente = new Perezoso<>(() -> new ResolverCliente(resolutor.get()));
    }

    private RepositorioSolicitudes crearRepositorio() {
        // "ninguna" significa sin Dataverse en absoluto: ni autorización ni registro de solicitudes.
        if (config.modoAutorizacion() == Configuracion.ModoAutorizacion.NINGUNA) {
            LOG.warning("AUTORIZACION_MODO=ninguna: repositorio sustituto en memoria, Dataverse no se consulta (solo spike S1)");
            return new RepositorioSinDataverse();
        }
        return new RepositorioSolicitudesDataverse(clienteDataverse.get());
    }

    /** S1b: con SAS_LIGADA_USUARIO=true el broker devuelve también el token de Storage del usuario (OBO). */
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

    private AutorizadorSolicitud crearAutorizador() {
        if (config.modoAutorizacion() == Configuracion.ModoAutorizacion.NINGUNA) {
            LOG.warning("AUTORIZACION_MODO=ninguna: NO se verifica acceso en Dataverse (solo spike S1)");
            return new AutorizadorPermisivo();
        }
        return new AutorizadorSolicitudDataverse(clienteDataverse.get());
    }

    public Configuracion configuracion() {
        return config;
    }

    public EmitirSasSubida emitirSasSubida() {
        return emitirSasSubida.get();
    }

    public EmitirSasLectura emitirSasLectura() {
        return emitirSasLectura.get();
    }

    public ProcesarIngesta procesarIngesta() {
        return procesarIngesta.get();
    }

    public ReencolarEstancadas reencolarEstancadas() {
        return reencolarEstancadas.get();
    }

    public RegistrarResultadoEscaneo registrarResultadoEscaneo() {
        return registrarResultadoEscaneo.get();
    }

    public ResolverCliente resolverCliente() {
        return resolverCliente.get();
    }
}
