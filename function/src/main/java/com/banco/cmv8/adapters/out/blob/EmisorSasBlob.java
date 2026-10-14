package com.banco.cmv8.adapters.out.blob;

import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceVersion;
import com.azure.storage.blob.models.UserDelegationKey;
import com.azure.storage.blob.options.BlobGetUserDelegationKeyOptions;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import com.azure.storage.common.sas.SasProtocol;
import com.banco.cmv8.domain.ContextoLlamador;
import com.banco.cmv8.domain.PoliticaSas;
import com.banco.cmv8.domain.SasEmitida;
import com.banco.cmv8.domain.port.EmisorSas;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Emite SAS de delegación de usuario con la identidad administrada (Shared Key está deshabilitado).
 *
 * <ul>
 *   <li>Un blob, HTTPS, vigencia corta (PoliticaSas).</li>
 *   <li>scid = solicitudId (correlación en los logs de Storage).</li>
 *   <li>saoid = oid del llamador cuando llega (auditoría).</li>
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
    private final BlobContainerClient contenedor;
    private final String blobEndpoint;
    private final String nombreContenedor;
    private final PoliticaSas politica;
    private final boolean ligadaUsuario;
    private final Map<String, UserDelegationKey> clavesPorTenant = new ConcurrentHashMap<>();

    public EmisorSasBlob(BlobServiceClient servicio, String blobEndpoint, String nombreContenedor,
                         PoliticaSas politica, boolean ligadaUsuario) {
        this.servicio = servicio;
        this.contenedor = servicio.getBlobContainerClient(nombreContenedor);
        this.blobEndpoint = blobEndpoint.endsWith("/") ? blobEndpoint : blobEndpoint + "/";
        this.nombreContenedor = nombreContenedor;
        this.politica = politica;
        this.ligadaUsuario = ligadaUsuario;
    }

    @Override
    public SasEmitida emitirSubida(String rutaBlob, Duration vigencia, ContextoLlamador llamador) {
        BlobSasPermission permisos = new BlobSasPermission()
                .setCreatePermission(true)
                .setWritePermission(true);
        return emitir(rutaBlob, vigencia, llamador, permisos);
    }

    @Override
    public SasEmitida emitirLectura(String rutaBlob, Duration vigencia, ContextoLlamador llamador) {
        BlobSasPermission permisos = new BlobSasPermission().setReadPermission(true);
        return emitir(rutaBlob, vigencia, llamador, permisos);
    }

    private SasEmitida emitir(String rutaBlob, Duration vigencia, ContextoLlamador llamador,
                              BlobSasPermission permisos) {
        OffsetDateTime inicio = politica.calcularInicio();
        OffsetDateTime expira = politica.calcularExpiracion(vigencia);

        boolean ligar = ligadaUsuario && llamador.objectId().isPresent();
        Optional<String> tenantDelegado = ligar ? llamador.tenantId() : Optional.empty();
        UserDelegationKey clave = clave(expira, tenantDelegado);

        BlobServiceSasSignatureValues valores = new BlobServiceSasSignatureValues(expira, permisos)
                .setStartTime(inicio)
                .setProtocol(SasProtocol.HTTPS_ONLY);
        Optional<UUID> solicitudId = PoliticaSas.solicitudDeRuta(rutaBlob);
        solicitudId.ifPresent(id -> valores.setCorrelationId(id.toString()));
        // NO se usa saoid: Storage exige que la identidad firmante tenga runAsSuperUser (Blob Data Owner) para
        // aceptarlo, y rompería el mínimo privilegio (Contributor en el contenedor). Verificado en S2/S7: 403
        // AuthorizationPermissionMismatch. La correlación de auditoría va por scid + traza del llamador.
        if (ligar) {
            valores.setDelegatedUserObjectId(llamador.objectId().get());
        }

        BlobClient blob = contenedor.getBlobClient(rutaBlob);
        String sas = blob.generateUserDelegationSas(valores, clave);
        String url = blobEndpoint + nombreContenedor + "/" + rutaBlob + "?" + sas;
        return new SasEmitida(url, expira, BlobServiceVersion.getLatest().getVersion());
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
