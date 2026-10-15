# 01. App registration del broker ("CMV8 Broker (Function)")

> Rol que lo ejecuta: Identidad. Depende de: nada (es el primer paso). Lo necesitan: 02 (permiso del conector sobre su scope), infraestructura con Bicep (guías 03 a 13: `brokerClientId`), 14 (credencial federada), 15 (Easy Auth), 20 (`resourceUri` del conector). Costo: sin costo propio (una app registration no se factura).

## Qué es y qué hace en esta solución

La app registration del broker es el **recurso** que protege la Function. Cumple dos papeles a la vez:

1. **Audiencia de Easy Auth.** Cuando el conector llama a la Function, manda un token de Entra emitido para `api://<appId del broker>`. La autenticación integrada de App Service (guía 15) solo deja pasar tokens cuyo `aud` sea esta app.
2. **Cliente confidencial del OBO.** Con el token del usuario, la Function pide a Entra un token de **Azure Storage** en nombre de ese mismo usuario (On-Behalf-Of). Ese token es la segunda llave del diseño de dos llaves (ADR-002, «Estado tras los spikes»): la SAS ligada con `sduoid` solo sirve si se presenta junto con un token de Storage del mismo usuario. Para que Entra acepte el intercambio, la app del broker necesita el permiso delegado Azure Storage / `user_impersonation` con consentimiento de administrador y una credencial propia.

La credencial no es un secreto: la app confía en la identidad administrada de la Function como **credencial federada** (guía 14). Easy Auth tampoco necesita credencial: solo valida firmas.

Es una app single tenant. Expone un único scope delegado, `user_impersonation`, y pide tokens **v2** (`requestedAccessTokenVersion: 2`), consistentes con el issuer v2 que se configura en Easy Auth.

## ¿Imprescindible?

**Veredicto: Imprescindible**

- Qué aporta: la identidad contra la que Easy Auth valida los tokens y el cliente confidencial que puede hacer OBO hacia Storage. Sin cero secretos que custodiar (credencial federada, guía 14).
- Qué pasa si no está: no hay audiencia que configurar en Easy Auth, así que la Function queda abierta o directamente no se puede proteger con Entra; y sin OBO no hay token de Storage del usuario, así que la SAS ligada (`sduoid`) no se puede usar y se cae el diseño de dos llaves.
- Qué decisión lo haría prescindible (o distinto): ninguna dentro del diseño actual. La identidad administrada de la Function **no** puede reemplazarla: una identidad administrada no puede exponer un scope delegado ni ser audiencia de un token de usuario, y no puede ser cliente de un OBO por sí sola. Solo si se abandonara la SAS ligada al usuario (volver a SAS de delegación sin `sduoid`) desaparecería la necesidad del OBO, pero seguiría haciendo falta una app como audiencia de Easy Auth.
- Alternativa y su tradeoff: que el PCF obtenga el token de Storage con MSAL en el navegador, sin OBO. Se descartó en S1b: Power Apps corre en orígenes `*.island.powerapps.com` variables que no se pueden registrar como redirect de una SPA, y los popups o iframes de MSAL dentro del host no son confiables (ADR-002, «Diseño de dos llaves»). Además, seguiría haciendo falta una app como audiencia de la Function.

## Valores del cuaderno

| Variable | Produce / consume | Cómo se obtiene |
| --- | --- | --- |
| `TENANT_ID` | Consume | `az account show --query tenantId -o tsv` |
| `BROKER_APP_ID` | Produce | Salida de `az ad app create` (Application (client) ID) |
| `BROKER_OBJ_ID` | Produce | `az ad app show --id "$BROKER_APP_ID" --query id -o tsv` (Object ID de la app, no del service principal) |
| `BROKER_SCOPE_ID` | Produce | `uuidgen` antes del PATCH; es el id del scope `user_impersonation` |

## Con comandos

```bash
# crear la app y su service principal
BROKER_APP_ID=$(az ad app create --display-name "CMV8 Broker (Function)" --sign-in-audience AzureADMyOrg --query appId -o tsv)
BROKER_OBJ_ID=$(az ad app show --id "$BROKER_APP_ID" --query id -o tsv)
az ad sp create --id "$BROKER_APP_ID" -o none

# exponer la API: identifier URI, scope delegado user_impersonation y tokens v2
BROKER_SCOPE_ID=$(uuidgen)
cat > /tmp/broker-api.json <<EOF
{
  "identifierUris": ["api://$BROKER_APP_ID"],
  "api": {
    "requestedAccessTokenVersion": 2,
    "oauth2PermissionScopes": [{
      "id": "$BROKER_SCOPE_ID",
      "value": "user_impersonation",
      "type": "User",
      "isEnabled": true,
      "adminConsentDisplayName": "Acceder a CMV8 Broker",
      "adminConsentDescription": "Permite que la aplicacion llame al broker CMV8 en nombre del usuario que inicio sesion.",
      "userConsentDisplayName": "Acceder a CMV8 Broker",
      "userConsentDescription": "Permite que la aplicacion llame al broker CMV8 en tu nombre."
    }]
  }
}
EOF
az rest --method PATCH --url "https://graph.microsoft.com/v1.0/applications/$BROKER_OBJ_ID" --body @/tmp/broker-api.json

# permiso delegado Azure Storage / user_impersonation (lo que habilita el OBO hacia Storage)
az ad app permission add --id "$BROKER_APP_ID" \
  --api e406a681-f3d4-42a8-90b6-c2b029497af1 \
  --api-permissions 03e0da56-190b-40ad-a80c-ea378c433f7f=Scope
az ad app permission admin-consent --id "$BROKER_APP_ID"
```

`e406a681-f3d4-42a8-90b6-c2b029497af1` es el app id de Azure Storage y `03e0da56-190b-40ad-a80c-ea378c433f7f` el id de su scope `user_impersonation`; son los mismos en todos los tenants.

El consentimiento de administrador requiere **Cloud Application Administrator o superior** (Application Developer alcanza para crear la app, no para consentir).

### Opcional: pre-autorizar a Azure CLI

Solo si la política del Banco lo permite: pre-autorizar a Azure CLI (`04b07795-8ddb-461a-bbee-02f9e1bf7b46`) sobre el scope del broker, para poder probar la Function con `az account get-access-token --resource api://$BROKER_APP_ID` sin pasar por Power Apps. Se agrega en `api.preAuthorizedApplications` con el mismo PATCH:

```bash
az rest --method PATCH --url "https://graph.microsoft.com/v1.0/applications/$BROKER_OBJ_ID" \
  --body "{\"api\":{\"preAuthorizedApplications\":[{\"appId\":\"04b07795-8ddb-461a-bbee-02f9e1bf7b46\",\"delegatedPermissionIds\":[\"$BROKER_SCOPE_ID\"]}]}}"
```

Sin eso, las pruebas directas se hacen con un token obtenido por device code con la app del conector.

### Lo que agrega la guía 02

`api.knownClientApplications` (el app id del conector, para el consentimiento combinado) se escribe sobre ESTA app, pero recién cuando existe la app del conector: el PATCH está en `02-entra-conector.md`.

Después de cada PATCH sobre `api`, volver a correr la verificación de abajo: el resultado esperado es que los tres bloques (`oauth2PermissionScopes`, `preAuthorizedApplications`, `knownClientApplications`) convivan. Así quedó la app de desarrollo, construida con estos mismos PATCH.

## Con el portal

Pasos en el **Microsoft Entra admin center** (https://entra.microsoft.com), con al menos el rol Cloud Application Administrator.

1. **Entra ID** → **App registrations** → **New registration**. **Name**: `CMV8 Broker (Function)`. **Supported account types**: *Accounts in this organizational directory only* (single tenant). Dejar **Redirect URI** vacío: el broker es una API, nadie inicia sesión en él. **Register**.
2. En **Overview**, copiar **Application (client) ID** (`BROKER_APP_ID`) y **Object ID** (`BROKER_OBJ_ID`). Al registrar desde el portal, el service principal (la *Enterprise application*) se crea solo.
3. **Expose an API** → **Add** junto a **Application ID URI** → aceptar el valor por defecto `api://<appId>` → **Save**.
4. En la misma pantalla, **Add a scope**:
   - **Scope name**: `user_impersonation`
   - **Who can consent?**: **Admins and users**
   - **Admin consent display name**: `Acceder a CMV8 Broker`; **Admin consent description**: `Permite que la aplicacion llame al broker CMV8 en nombre del usuario que inicio sesion.`
   - **User consent display name** y **User consent description**: los mismos textos del JSON de arriba.
   - **State**: **Enabled** → **Add scope**. El portal asigna el id del scope; copiarlo como `BROKER_SCOPE_ID` desde el **Manifest** (`api.oauth2PermissionScopes[0].id`).
5. **Manifest**: cambiar `requestedAccessTokenVersion` a `2` (en el manifiesto de Microsoft Graph está dentro de `api`; en el formato antiguo se llamaba `accessTokenAcceptedVersion`) → **Save**. No hay otra pantalla que lo exponga (verificar en el portal el nombre exacto de la propiedad según la versión del editor de manifiesto).
6. Opcional, Azure CLI pre-autorizada: **Expose an API** → **Add a client application** → **Client ID** `04b07795-8ddb-461a-bbee-02f9e1bf7b46` → marcar el scope `user_impersonation` → **Add application**.
7. **API permissions** → **Add a permission** → pestaña **APIs my organization uses** (o **Microsoft APIs**) → buscar **Azure Storage** → **Delegated permissions** → marcar `user_impersonation` → **Add permissions**.
8. En **API permissions**, **Grant admin consent for <tenant>** → **Yes**. La columna **Status** debe quedar en verde ("Granted for …").

## Verificación

```bash
az ad app show --id "$BROKER_APP_ID" --query '{uris:identifierUris, v:api.requestedAccessTokenVersion, scopes:api.oauth2PermissionScopes[].value}'
az ad app permission list-grants --id "$BROKER_APP_ID" --query '[].{scope:scope, tipo:consentType}'
```

Se espera `api://<appId>`, versión `2`, scope `user_impersonation`, y un grant `AllPrincipals` con `user_impersonation` sobre Azure Storage.

En el portal: **Expose an API** muestra el URI y el scope; **API permissions** muestra Azure Storage / `user_impersonation` con estado *Granted*.

## Errores que ya vimos

| Síntoma | Causa | Arreglo |
| --- | --- | --- |
| 401 de la Function con un token válido | El token es v1 y Easy Auth espera v2 (issuer distinto) porque la app no quedó con `requestedAccessTokenVersion: 2` | Corregir el paso 5 de esta guía y revisar audiencias e issuer en la guía 15 |
| `AADSTS700211` / `AADSTS70021` en el OBO | No es de esta guía: credencial federada con `subject` equivocado | Guía 14 |
| 401 con `WWW-Authenticate: Bearer error="interaction_required"` | Acceso condicional sobre Azure Storage que el token del conector no satisface (hallazgo 10) | No es un error de esta app: ver `docs/backlog-tecnico.md` §10 y la guía de spikes |
