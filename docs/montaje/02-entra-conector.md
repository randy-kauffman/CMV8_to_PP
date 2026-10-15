# 02. App registration del conector ("CMV8 Conector (Power Platform)")

> Rol que lo ejecuta: Identidad. Depende de: 01 (app id, object id y scope del broker). Lo necesitan: 20 (conector personalizado: `clientId` y secreto), 23 (rotación del secreto). Costo: sin costo propio.

## Qué es y qué hace en esta solución

Es el **cliente** OAuth que Power Platform usa para pedir, en nombre del usuario, tokens para el broker. Cuando un usuario crea la conexión del conector **CMV8 Broker**, inicia sesión con Entra en una ventana de consentimiento; Power Platform guarda el refresh token de ESTA app y, en cada llamada, pide un access token para `api://<appId del broker>` y lo manda como `Authorization: Bearer` a la Function.

Tiene:

- una URL de redirección **global** (`https://global.consent.azure-apim.net/redirect`) y, después de crear el conector, la URL **específica del conector** (sección "URL de redirección específica" más abajo);
- el permiso delegado `user_impersonation` sobre el broker, con consentimiento de administrador (así los usuarios no ven un consentimiento individual);
- un lugar en `knownClientApplications` del broker (consentimiento combinado);
- **el único secreto de la solución**: vence a los 6 meses y va directo al vault, nunca a la pantalla ni a un archivo.

## ¿Imprescindible?

**Veredicto: Imprescindible**

- Qué aporta: el conector personalizado hace OAuth 2.0 con Entra y necesita una app cliente registrada con la que pedir tokens del usuario para el broker. Es lo que permite que la Function reciba la identidad real del usuario (Easy Auth inyecta su `oid`) y que el broker haga OBO hacia Storage con ESE usuario.
- Qué pasa si no está: no hay conector con identidad de usuario; la app canvas no puede llamar al broker con el token del usuario y el diseño de dos llaves no tiene de dónde sacar el token de Storage.
- Qué decisión lo haría prescindible (o distinto): ninguna lo hace prescindible; lo que puede cambiar es la **credencial**. En el diseño montado y probado, Power Platform usa un client secret (también admite certificado: más gestión, misma necesidad de app). Microsoft Learn documenta además que los conectores personalizados con OAuth 2.0 + Entra pueden usar una **identidad administrada del conector** en lugar del secreto: en la pestaña **Security** se elige la opción de managed identity, el conector muestra un *issuer* y un *subject*, y se agregan como credencial federada en ESTA app (exige app single tenant y el tenant id real, no `common`; al exportar e importar el conector a otro entorno se genera otra identidad y hay que agregar otra credencial). Eso eliminaría el último secreto y su rotación de 6 meses. **No está probado en este proyecto** (no se sabe si `pac connector create` con `apiProperties.json` lo expresa); si el Banco quiere cero secretos, probarlo primero en desarrollo.
- Alternativa y su tradeoff: reutilizar la app del broker como cliente del conector (una sola app). Se descartó: mezcla el recurso con el cliente, obliga a ponerle secreto al broker (que hoy no tiene ninguno) y pierde la separación entre "quién protege la Function" y "quién pide tokens desde Power Platform".

## Valores del cuaderno

| Variable | Produce / consume | Cómo se obtiene |
| --- | --- | --- |
| `BROKER_APP_ID` | Consume | Guía 01 |
| `BROKER_OBJ_ID` | Consume | Guía 01 |
| `BROKER_SCOPE_ID` | Consume | Guía 01 |
| `CONECTOR_APP_ID` | Produce | Salida de `az ad app create` |
| Vault de secretos de identidad del Banco | Consume | Lo define Identidad. Puede ser el mismo vault de la Function (guía 09) o uno propio del área; el secreto se llama `cmv8-conector-secreto` |
| URL de redirección específica | Consume (después de la guía 20) | Pestaña **Security** del conector, campo **Redirect URL** |

## Con comandos

```bash
CONECTOR_APP_ID=$(az ad app create --display-name "CMV8 Conector (Power Platform)" --sign-in-audience AzureADMyOrg \
  --web-redirect-uris https://global.consent.azure-apim.net/redirect --query appId -o tsv)
az ad sp create --id "$CONECTOR_APP_ID" -o none

# permiso delegado sobre el scope del broker, con consentimiento de administrador
az ad app permission add --id "$CONECTOR_APP_ID" --api "$BROKER_APP_ID" --api-permissions "$BROKER_SCOPE_ID=Scope"
az ad app permission admin-consent --id "$CONECTOR_APP_ID"

# el broker reconoce al conector como cliente conocido (consentimiento combinado)
az rest --method PATCH --url "https://graph.microsoft.com/v1.0/applications/$BROKER_OBJ_ID" \
  --body "{\"api\":{\"knownClientApplications\":[\"$CONECTOR_APP_ID\"]}}"
```

Después del PATCH, repetir la verificación de la guía 01: el scope `user_impersonation` y (si se agregó) la pre-autorización de Azure CLI tienen que seguir ahí.

### Secreto del conector

Se genera con vencimiento a 6 meses y va DIRECTO al vault del Banco, sin pasar por la pantalla ni por un archivo. Anotar la fecha de rotación en el calendario del equipo (ver guía 23).

```bash
FIN=$(date -u -d '+6 months' +%Y-%m-%dT%H:%M:%SZ)
az keyvault secret set --vault-name "<vault de secretos de identidad del Banco>" --name cmv8-conector-secreto \
  --expires "$FIN" \
  --value "$(az ad app credential reset --id "$CONECTOR_APP_ID" --append --display-name power-platform-conector --end-date "$FIN" --query password -o tsv)" \
  --query attributes.expires -o tsv
```

Respecto de la versión anterior de la guía se agregó `--expires "$FIN"`: sin él, el secreto del vault no tiene vencimiento, la salida `attributes.expires` sale vacía y el evento `SecretNearExpiry` del vault nunca se dispara. Con él, el vault y la app registration vencen el mismo día y la alerta de rotación es automática.

`--append` es obligatorio: sin él, `credential reset` borra las credenciales existentes de la app (en una rotación, cortaría el conector antes de actualizarlo).

Si el Banco guarda secretos en otro lugar, el patrón es el mismo: la salida del `credential reset` se entrega al gestor de secretos, nunca a la terminal.

### URL de redirección específica

La segunda URL de redirección (la específica del conector) se conoce recién después de crear el conector (guía 20). Al crear la conexión, Power Platform no usa la URL global sino una **específica del conector** (`https://global.consent.azure-apim.net/redirect/<nombre interno>-<hash>`). Si no está registrada en la app del conector, Entra responde `AADSTS50011`. Microsoft Learn confirma que los conectores personalizados con OAuth 2.0 usan una URL de redirección por conector.

1. Abrir el conector en make.powerapps.com → **Custom connectors** → CMV8 Broker → **Security** → campo **Redirect URL**. Copiarla.
2. Registrarla junto con la global:

```bash
az ad app update --id "$CONECTOR_APP_ID" --web-redirect-uris \
  https://global.consent.azure-apim.net/redirect \
  "<URL específica copiada>"
```

`--web-redirect-uris` reemplaza la lista completa: siempre pasar las dos.

Cada vez que el conector se recrea cambia el hash y hay que repetirlo; `pac connector update` sobre el mismo id no lo cambia.

## Con el portal

En el **Microsoft Entra admin center** (https://entra.microsoft.com), con Cloud Application Administrator o superior.

1. **Entra ID** → **App registrations** → **New registration**. **Name**: `CMV8 Conector (Power Platform)`. **Supported account types**: *Accounts in this organizational directory only*. **Redirect URI**: plataforma **Web**, valor `https://global.consent.azure-apim.net/redirect`. **Register**.
2. Copiar **Application (client) ID** de **Overview** (`CONECTOR_APP_ID`).
3. **API permissions** → **Add a permission** → **My APIs** → `CMV8 Broker (Function)` → **Delegated permissions** → marcar `user_impersonation` → **Add permissions**.
4. **Grant admin consent for <tenant>** → **Yes**. Estado en verde.
5. Volver a la app **CMV8 Broker (Function)** → **Expose an API** → **Add a client application**: este paso agrega una *pre-autorización*, que NO es lo mismo que `knownClientApplications`. `knownClientApplications` no tiene pantalla propia: se edita en el **Manifest** del broker (`api.knownClientApplications: ["<CONECTOR_APP_ID>"]`) → **Save**.
6. Secreto: **Certificates & secrets** → **Client secrets** → **New client secret** → **Description** `power-platform-conector`, **Expires** 180 días (6 meses) → **Add**. El valor se muestra UNA vez: copiarlo directo al vault (Azure portal → Key vault → **Secrets** → **Generate/Import**, nombre `cmv8-conector-secreto`, con **Set expiration date** igual al vencimiento) y cerrar la pantalla. Por portal el secreto pasa por el portapapeles; por eso el camino recomendado es el comando, que no lo muestra nunca.
7. Después de la guía 20: **Authentication** → en la plataforma **Web**, **Add URI** → pegar la URL específica del conector → **Save**. Debe quedar junto a la global.

## Verificación

`az ad app permission list-grants --id "$CONECTOR_APP_ID"` muestra `user_impersonation` con `AllPrincipals` sobre el broker.

```bash
az ad app show --id "$CONECTOR_APP_ID" --query '{web:web.redirectUris, cred:passwordCredentials[].{n:displayName,fin:endDateTime}}'
az ad app show --id "$BROKER_APP_ID" --query api.knownClientApplications
```

Se espera: la URL global (y, después de la guía 20, la específica), una credencial `power-platform-conector` con vencimiento a 6 meses, y el app id del conector en `knownClientApplications` del broker.

Verificación de punta a punta (después de la guía 20): crear una conexión del conector con un usuario piloto. Entra pide iniciar sesión y, si el consentimiento de administrador de esta guía está dado, no pide consentimiento individual.

## Errores que ya vimos

| Síntoma | Causa | Arreglo |
| --- | --- | --- |
| `AADSTS50011` al crear la conexión | Falta la URL de redirección específica del conector en esta app | Sección "URL de redirección específica" |
| `AADSTS50011` otra vez, después de recrear el conector | Al recrear el conector cambia el hash de la URL | Repetir el `az ad app update` con la URL nueva |
| El usuario ve una pantalla de consentimiento al crear la conexión | Falta el `admin-consent` del permiso sobre el broker | `az ad app permission admin-consent --id "$CONECTOR_APP_ID"` |
