package com.banco.cmv8.adapters.out.blob;

import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceVersion;
import com.azure.storage.blob.models.UserDelegationKey;
import com.azure.storage.blob.options.BlobGetUserDelegationKeyOptions;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import com.azure.storage.common.sas.SasProtocol;
import com.banco.cmv8.domain.ClaveAdjunto;
import com.banco.cmv8.domain.ContextoLlamador;
import com.banco.cmv8.domain.PoliticaSas;
import com.banco.cmv8.domain.SasEmitida;
import com.banco.cmv8.domain.port.EmisorSas;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Emite SAS de delegación de usuario con la identidad administrada (Shared Key está deshabilitado).
 *
 * <ul>
 *   <li>Un blob, HTTPS, vigencia corta (PoliticaSas).</li>
 *   <li>Contenedor explícito por llamada (ADR-003 §7): la subida va a ingesta; la lectura, a ingesta o a
 *       almacenados. La firma cubre {@code <contenedor>/<ruta>}.</li>
 *   <li>scid = id del adjunto tomado de la ruta {@code <tabla>/<id>/<archivo>} (correlación en los logs de
 *       Storage).</li>
 *   <li>saoid: no se usa (ver el comentario en {@code emitir}).</li>
 *   <li>sduoid = oid del llamador si SAS_LIGADA_USUARIO=true (S1b): la SAS solo sirve junto con un token
 *       Entra de ese usuario. Requiere versión de servicio 2025-07-05 o superior.</li>
 * </ul>
 */
public class EmisorSasBlob implements EmisorSas {

    /** La clave se pide por 2 h y se reutiliza mientras cubra la SAS con margen. */
    private static final Duration VIGENCIA_CLAVE = Duration.ofHours(2);
    private static final Duration MARGEN_CLAVE = Duration.ofMinutes(5);
    private static final String SIN_TENANT = "-";

    private final BlobServiceClient servicio;
    private final String blobEndpoint;
    private final PoliticaSas politica;
    private final boolean ligadaUsuario;
    private final Map<String, UserDelegationKey> clavesPorTenant = new ConcurrentHashMap<>();

    public EmisorSasBlob(BlobServiceClient servicio, String blobEndpoint, PoliticaSas politica, boolean ligadaUsuario) {
        this.servicio = servicio;
        this.blobEndpoint = blobEndpoint.endsWith("/") ? blobEndpoint : blobEndpoint + "/";
        this.politica = politica;
        this.ligadaUsuario = ligadaUsuario;
    }

    @Override
    public SasEmitida emitirSubida(String contenedor, String rutaBlob, Duration vigencia, ContextoLlamador llamador) {
        BlobSasPermission permisos = new BlobSasPermission()
                .setCreatePermission(true)
                .setWritePermission(true);
        return emitir(contenedor, rutaBlob, vigencia, llamador, permisos);
    }

    @Override
    public SasEmitida emitirLectura(String contenedor, String rutaBlob, Duration vigencia, ContextoLlamador llamador) {
        BlobSasPermission permisos = new BlobSasPermission().setReadPermission(true);
        return emitir(contenedor, rutaBlob, vigencia, llamador, permisos);
    }

    private SasEmitida emitir(String contenedor, String rutaBlob, Duration vigencia, ContextoLlamador llamador,
                              BlobSasPermission permisos) {
        OffsetDateTime inicio = politica.calcularInicio();
        OffsetDateTime expira = politica.calcularExpiracion(vigencia);

        boolean ligar = ligadaUsuario && llamador.objectId().isPresent();
        Optional<String> tenantDelegado = ligar ? llamador.tenantId() : Optional.empty();
        UserDelegationKey clave = clave(expira, tenantDelegado);

        BlobServiceSasSignatureValues valores = new BlobServiceSasSignatureValues(expira, permisos)
                .setStartTime(inicio)
                .setProtocol(SasProtocol.HTTPS_ONLY);
        ClaveAdjunto.deRuta(rutaBlob).ifPresent(c -> valores.setCorrelationId(c.id().toString()));
        // NO se usa saoid: Storage exige que la identidad firmante tenga runAsSuperUser (Blob Data Owner) para
        // aceptarlo, y rompería el mínimo privilegio (Contributor en el contenedor). Verificado en S2/S7: 403
        // AuthorizationPermissionMismatch. La correlación de auditoría va por scid + traza del llamador.
        if (ligar) {
            valores.setDelegatedUserObjectId(llamador.objectId().get());
        }

        BlobClient blob = servicio.getBlobContainerClient(contenedor).getBlobClient(rutaBlob);
        String sas = blob.generateUserDelegationSas(valores, clave);
        String url = urlBlob(blobEndpoint, contenedor, rutaBlob) + "?" + sas;
        return new SasEmitida(url, expira, BlobServiceVersion.getLatest().getVersion());
    }

    /** {@code <endpoint>/<contenedor>/<ruta>}: el mismo recurso que firma la SAS (sr=b). */
    static String urlBlob(String blobEndpoint, String contenedor, String rutaBlob) {
        String base = blobEndpoint.endsWith("/") ? blobEndpoint : blobEndpoint + "/";
        return base + contenedor + "/" + rutaBlob;
    }

    private UserDelegationKey clave(OffsetDateTime expiraSas, Optional<String> tenantDelegado) {
        String llave = tenantDelegado.orElse(SIN_TENANT);
        UserDelegationKey actual = clavesPorTenant.get(llave);
        if (actual != null && actual.getSignedExpiry().isAfter(expiraSas.plus(MARGEN_CLAVE))) {
            return actual;
        }
        OffsetDateTime ahora = OffsetDateTime.now(ZoneOffset.UTC);
        BlobGetUserDelegationKeyOptions opciones = new BlobGetUserDelegationKeyOptions(ahora.plus(VIGENCIA_CLAVE))
                .setStartsOn(ahora.minus(PoliticaSas.MARGEN_RELOJ));
        tenantDelegado.ifPresent(opciones::setDelegatedUserTenantId);
        UserDelegationKey nueva = servicio.getUserDelegationKeyWithResponse(opciones, null, Context.NONE).getValue();
        clavesPorTenant.put(llave, nueva);
        return nueva;
    }
}
