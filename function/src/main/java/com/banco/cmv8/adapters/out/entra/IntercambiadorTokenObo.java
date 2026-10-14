package com.banco.cmv8.adapters.out.entra;

import com.banco.cmv8.application.IntercambioTokenException;
import com.banco.cmv8.application.NoAutenticadoException;
import com.banco.cmv8.domain.ContextoLlamador;
import com.banco.cmv8.domain.TokenUsuario;
import com.banco.cmv8.domain.port.IntercambiadorTokenUsuario;
import com.microsoft.aad.msal4j.ClientCredentialFactory;
import com.microsoft.aad.msal4j.ConfidentialClientApplication;
import com.microsoft.aad.msal4j.IAuthenticationResult;
import com.microsoft.aad.msal4j.IClientCredential;
import com.microsoft.aad.msal4j.ManagedIdentityApplication;
import com.microsoft.aad.msal4j.ManagedIdentityId;
import com.microsoft.aad.msal4j.ManagedIdentityParameters;
import com.microsoft.aad.msal4j.MsalException;
import com.microsoft.aad.msal4j.MsalServiceException;
import com.microsoft.aad.msal4j.OnBehalfOfParameters;
import com.microsoft.aad.msal4j.UserAssertion;

import java.net.MalformedURLException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Token de Azure Storage en nombre del usuario con el flujo On-Behalf-Of (MSAL4J). La assertion es el bearer con el
 * que el usuario llamó al broker (audiencia api://broker). La app del broker necesita el permiso delegado
 * Azure Storage / user_impersonation con consentimiento de administrador y una credencial propia.
 *
 * <p>Credencial del broker, en orden de preferencia:
 * <ol>
 *   <li>{@link #conIdentidadAdministrada}: la app registration confía en la identidad administrada de la Function
 *       (credencial federada, audiencia {@value #AUDIENCIA_INTERCAMBIO}). Sin secretos que rotar.</li>
 *   <li>{@link #conSecreto}: secreto de cliente. Solo para desarrollo local, donde no hay identidad administrada.</li>
 * </ol>
 *
 * <p>Caché en memoria por oid: se reutiliza el token mientras le quede al menos {@code vigenciaMinima}
 * (vigencia de la SAS + margen), para que el token no venza antes que la SAS que acompaña. Nunca se loguea.
 */
public class IntercambiadorTokenObo implements IntercambiadorTokenUsuario {

    public static final String SCOPE_STORAGE = "https://storage.azure.com/user_impersonation";
    /** Audiencia del token de la identidad administrada que Entra acepta como client assertion de una app. */
    public static final String AUDIENCIA_INTERCAMBIO = "api://AzureADTokenExchange";
    private static final long TIMEOUT_SEGUNDOS = 15;

    /** Seam para pruebas: el intercambio real contra Entra. */
    @FunctionalInterface
    interface Intercambio {
        TokenUsuario obo(String assertion);
    }

    private final Intercambio intercambio;
    private final Duration vigenciaMinima;
    private final Clock reloj;
    private final Map<String, TokenUsuario> cachePorOid = new ConcurrentHashMap<>();

    IntercambiadorTokenObo(Intercambio intercambio, Duration vigenciaMinima, Clock reloj) {
        this.intercambio = intercambio;
        this.vigenciaMinima = vigenciaMinima;
        this.reloj = reloj;
    }

    /** Broker autenticado con la identidad administrada asignada por el usuario (credencial federada en la app). */
    public static IntercambiadorTokenObo conIdentidadAdministrada(String clientId, String tenantId,
                                                                   String identidadClientId, Duration vigenciaMinima) {
        return new IntercambiadorTokenObo(msal(clientId, tenantId, asercionDesdeIdentidad(identidadClientId)),
                vigenciaMinima, Clock.systemUTC());
    }

    /** Broker autenticado con secreto de cliente. Solo desarrollo local. */
    public static IntercambiadorTokenObo conSecreto(String clientId, String tenantId, String clientSecret,
                                                    Duration vigenciaMinima) {
        return new IntercambiadorTokenObo(msal(clientId, tenantId, ClientCredentialFactory.createFromSecret(clientSecret)),
                vigenciaMinima, Clock.systemUTC());
    }

    @Override
    public TokenUsuario tokenStorage(ContextoLlamador llamador) {
        String oid = llamador.objectId().orElseThrow(() ->
                new NoAutenticadoException("Falta el oid del llamador para el token de Storage"));
        String assertion = llamador.bearerToken().orElseThrow(() ->
                new NoAutenticadoException("Falta el bearer del llamador para el token de Storage"));

        OffsetDateTime minimo = OffsetDateTime.now(reloj).plus(vigenciaMinima);
        TokenUsuario enCache = cachePorOid.get(oid);
        if (enCache != null && enCache.expira().isAfter(minimo)) {
            return enCache;
        }
        TokenUsuario nuevo = intercambio.obo(assertion);
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        cachePorOid.values().removeIf(t -> !t.expira().isAfter(ahora));
        cachePorOid.put(oid, nuevo);
        return nuevo;
    }

    /**
     * Client assertion que MSAL pide en cada adquisición: un token de la identidad administrada con audiencia
     * {@value #AUDIENCIA_INTERCAMBIO}. MSAL cachea el token de la identidad, así que el endpoint de identidad
     * solo se consulta al vencer.
     */
    private static IClientCredential asercionDesdeIdentidad(String identidadClientId) {
        ManagedIdentityApplication identidad = ManagedIdentityApplication
                .builder(ManagedIdentityId.userAssignedClientId(identidadClientId))
                .build();
        ManagedIdentityParameters parametros = ManagedIdentityParameters.builder(AUDIENCIA_INTERCAMBIO).build();
        Callable<String> proveedor = () -> identidad.acquireTokenForManagedIdentity(parametros)
                .get(TIMEOUT_SEGUNDOS, TimeUnit.SECONDS).accessToken();
        return ClientCredentialFactory.createFromCallback(proveedor);
    }

    private static Intercambio msal(String clientId, String tenantId, IClientCredential credencial) {
        ConfidentialClientApplication app;
        try {
            app = ConfidentialClientApplication
                    .builder(clientId, credencial)
                    .authority("https://login.microsoftonline.com/" + tenantId + "/")
                    .build();
        } catch (MalformedURLException e) {
            throw new IllegalStateException("Autoridad Entra inválida para el tenant configurado", e);
        }
        return assertion -> {
            OnBehalfOfParameters parametros = OnBehalfOfParameters
                    .builder(Set.of(SCOPE_STORAGE), new UserAssertion(assertion))
                    .build();
            try {
                IAuthenticationResult r = app.acquireToken(parametros).get(TIMEOUT_SEGUNDOS, TimeUnit.SECONDS);
                return new TokenUsuario(r.accessToken(),
                        OffsetDateTime.ofInstant(r.expiresOnDate().toInstant(), ZoneOffset.UTC));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IntercambioTokenException("Se interrumpió el OBO hacia Storage", "-", e);
            } catch (TimeoutException e) {
                throw new IntercambioTokenException("Entra no respondió el OBO hacia Storage a tiempo", "timeout", e);
            } catch (ExecutionException e) {
                throw traducir(e.getCause() == null ? e : e.getCause());
            } catch (MsalException e) {
                throw traducir(e);
            }
        };
    }

    /**
     * Solo el código de error (invalid_grant, interaction_required, ...) y, si Entra lo manda, el desafío de claims
     * (acceso condicional): el mensaje de MSAL puede traer datos y no se propaga.
     */
    private static IntercambioTokenException traducir(Throwable t) {
        String codigo = t instanceof MsalServiceException mse ? mse.errorCode()
                : t instanceof MsalException me ? me.errorCode() : t.getClass().getSimpleName();
        String claims = t instanceof MsalServiceException mse ? mse.claims() : null;
        return new IntercambioTokenException("No se pudo obtener el token de Storage del usuario (OBO)",
                codigo == null ? "-" : codigo, claims, null);
    }
}
