# Conector personalizado "CMV8 Broker"

Conector de Power Platform que llama a la Function `cmv8-func-supfcopn` (broker de adjuntos) **con la identidad del usuario** que usa la app o el flujo. La Function no valida tokens en código: la autenticación integrada de App Service (Easy Auth) valida el token de Entra ID antes de que la request llegue al worker de Java, y le inyecta `X-MS-CLIENT-PRINCIPAL-ID` / `X-MS-CLIENT-PRINCIPAL` (los lee `LectorLlamador`).

> **Conector premium.** Todo conector personalizado es premium: cada usuario de la app o del flujo necesita licencia Power Apps / Power Automate premium (o pay-as-you-go).

## Archivos

| Archivo | Qué es |
| --- | --- |
| `cmv8-broker.swagger.json` | Definición OpenAPI 2.0 v2 (ADR-003 §5): host `cmv8-func-supfcopn.azurewebsites.net`, basePath `/api`. Operaciones: `Salud`, `EmitirSasLote`, `EmitirSasLectura`, `Archivar`, `Reanudar`, `IngestarArchivo` (cuerpo binario, para flujos), `ExtenderRetencion`, `DescubrirTipos`, `ActualizarTipos`, `ValidarTipo`, `ValidarTabla`, `ReanudarConflicto`, `ConsultarAs400`. `uploadUrl` y `tokenStorage` están marcados `x-ms-visibility: internal` para que no se muestren en los diseñadores; igual viven solo en una variable de la app. |
| `apiProperties.json` | Parámetros de conexión OAuth 2.0 con Entra ID (`identityProvider: aad`). **El secreto NO está acá**: el campo `clientSecret` lleva un marcador. |

## Las dos app registrations (tenant `7bcfee03-896e-4ad3-947b-8a2d9ce17974`)

### 1. CMV8 Broker (Function) — el recurso

| Dato | Valor |
| --- | --- |
| Application (client) ID | `fe2f40d6-cac3-451b-b24d-ab9e93c80482` |
| Tipo de cuenta | Single tenant |
| Application ID URI | `api://fe2f40d6-cac3-451b-b24d-ab9e93c80482` |
| Scope expuesto | `user_impersonation` (consienten admins y usuarios) |
| `requestedAccessTokenVersion` | `2` (tokens v2, consistente con el issuer v2 de Easy Auth) |
| Clientes pre-autorizados | Azure CLI (`04b07795-8ddb-461a-bbee-02f9e1bf7b46`) para pruebas con `az account get-access-token` |
| `knownClientApplications` | el app id del conector (`72ec6834-…`) |
| Credencial | Credencial federada `uami-cmv8-broker`: la app confía en la identidad administrada de la Function (subject = principal id de la UAMI, audiencia `api://AzureADTokenExchange`). Sin secretos. Easy Auth no necesita credencial |
| Permiso delegado | Azure Storage / `user_impersonation`, con consentimiento de administrador (S1b) |

### 2. CMV8 Conector (Power Platform) — el cliente

| Dato | Valor |
| --- | --- |
| Application (client) ID | `72ec6834-cb06-4d9e-a008-bca52f7f41dd` |
| Tipo de cuenta | Single tenant |
| Redirect URI (Web) | `https://global.consent.azure-apim.net/redirect` |
| Permiso | delegado `user_impersonation` sobre CMV8 Broker, **con consentimiento de administrador** |
| Secreto | `power-platform-conector`, vence a los 6 meses (2027-04-14) |

## Configuración de Easy Auth en la Function

`Microsoft.Web/sites/cmv8-func-supfcopn/config/authsettingsV2`:

- Proveedor Microsoft (Entra), `clientId` = app id del broker.
- Issuer: `https://login.microsoftonline.com/7bcfee03-896e-4ad3-947b-8a2d9ce17974/v2.0`.
- Audiencias permitidas: `api://fe2f40d6-cac3-451b-b24d-ab9e93c80482` y `fe2f40d6-cac3-451b-b24d-ab9e93c80482` (un token v2 trae el GUID en `aud`).
- Requests sin autenticar: `Return401`.
- Rutas excluidas: `/runtime/webhooks/eventgrid` (Event Grid entrega con la clave de sistema del host) y `/api/salud`.
- Token store: habilitado (valor por defecto).

El grupo `az functionapp auth` necesita la extensión `authV2` de la CLI; sin ella se configura con `az rest --method PUT` sobre la URL de `authsettingsV2` (`api-version=2023-12-01`).

## Dónde vive el secreto

- **En el conector** (Power Platform lo guarda cifrado) y en ningún archivo del repo.
- Para crear o actualizar el conector se arma una copia local de `apiProperties.json` **fuera del repo**, con el secreto real en `clientSecret`, y se borra al terminar.
- Si el secreto se pierde o vence: `az ad app credential reset --id 72ec6834-cb06-4d9e-a008-bca52f7f41dd --append --display-name power-platform-conector --end-date <fecha>` y luego actualizar el conector (abajo). Las conexiones existentes siguen funcionando hasta que el usuario tenga que volver a iniciar sesión.

## Crear o actualizar el conector

Conector actual (entorno Dev `9c8994b8-…`, solución `cmv8ingesta`): id `2102807c-9ac7-f111-a05a-000d3a3b5b91`, nombre `new_cmv8-20broker`. `pac connector create` deriva el nombre del `info.title` con prefijo `new_` (no el prefijo del publisher). Los dos primeros intentos fallaron con `PessimisticConcurrencyConflict` ("Operation on the API is in progress") en el APIM interno; el reintento ~5 minutos después funcionó sin cambiar nada.

```bash
# copia temporal FUERA del repo, con el secreto real
jq --arg s "<SECRETO>" '.properties.connectionParameters.token.oAuthSettings.clientSecret=$s' \
  dataverse/conector/apiProperties.json > /ruta/temporal/apiProperties.json

# crear (solución cmv8ingesta, entorno de desarrollo)
pac connector create \
  --api-definition-file dataverse/conector/cmv8-broker.swagger.json \
  --api-properties-file /ruta/temporal/apiProperties.json \
  --solution-unique-name cmv8ingesta \
  --environment 9c8994b8-b1bc-ee23-9184-2f6346502613

# actualizar uno existente
pac connector update --connector-id <id> \
  --api-definition-file dataverse/conector/cmv8-broker.swagger.json \
  --api-properties-file /ruta/temporal/apiProperties.json

pac connector list --environment 9c8994b8-b1bc-ee23-9184-2f6346502613
rm /ruta/temporal/apiProperties.json
```

Al crear la primera conexión, el usuario inicia sesión con Entra ID; el conector pide un token para `resourceUri` = `api://fe2f40d6-…` y lo manda como `Authorization: Bearer` a la Function.

### Redirect URI

`apiProperties.json` usa `redirectMode: Global` (la URL `https://global.consent.azure-apim.net/redirect`, registrada en la app del conector). Microsoft recomienda migrar a la URL única por conector (`GlobalPerConnector`): si se cambia, hay que copiar la URL que genera el conector (pestaña Seguridad) y agregarla como redirect URI de "CMV8 Conector (Power Platform)".

## Probar sin Power Platform

```bash
TOKEN=$(az account get-access-token --resource api://fe2f40d6-cac3-451b-b24d-ab9e93c80482 --query accessToken -o tsv)
curl -s -X POST https://cmv8-func-supfcopn.azurewebsites.net/api/sas/subida \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"solicitudId":"<guid>","nombreArchivo":"x.bin","tamanoBytes":1024,"contentType":"application/octet-stream"}'
```

Sin `Authorization` la respuesta es 401 (Easy Auth, con `WWW-Authenticate: Bearer realm=...`).

## URL de redirección por conector (aprendido en S7)

Al crear la conexión, Power Platform usa una redirect URI **específica del conector**, no la global:
`https://global.consent.azure-apim.net/redirect/new-5fcmv8-20broker-5ff95a98b5516c5717` (el sufijo sale del nombre interno del conector más un hash). Si no está en la app registration del conector, Entra devuelve `AADSTS50011`. Se ve en el conector, pestaña Seguridad, campo "URL de redirección", y hay que agregarla con `az ad app update --id <connectorAppId> --web-redirect-uris <global> <específica>`. Al recrear el conector cambia el hash y hay que repetirlo.
