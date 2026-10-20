# 24. App registration «CMV8 PCF» (cliente público para model-driven)

**Veredicto**: imprescindible solo si el Banco usa el control de adjuntos en apps model-driven (ADR-003 §4.6). En canvas no hace falta: la SAS llega por el conector.

## Qué es y por qué existe

En model-driven el PCF orquesta solo: crea las filas con la Web API, pide el lote de SAS al broker y sube con las dos llaves. Para llamar al broker necesita un token del usuario con audiencia del broker, y un componente de código no puede usar la conexión del conector. La solución documentada por Microsoft (arquitectura de referencia de Dynamics 365 para PCF con MSAL) es una app registration de **cliente público** (SPA) que el PCF usa con MSAL Browser: silencioso con la sesión de Entra que el usuario ya tiene en Dataverse, popup solo si el navegador bloquea el iframe.

El modelo de seguridad no cambia: el token tiene la misma audiencia que el del conector, Easy Auth lo valida igual, el broker no cambia una línea y las políticas de acceso condicional del Banco aplican igual.

| Dato | Valor en desarrollo | En el Banco |
| --- | --- | --- |
| Nombre | CMV8 PCF | el que decida el Banco |
| Application (client) ID | `880a00aa-dac2-4741-aa76-c1fd9584a870` | salida del alta; va a la propiedad `clientIdPcf` del control |
| Tipo de cuenta | Single tenant | Single tenant |
| Plataforma | SPA, redirect `https://<org>.crm.dynamics.com/` y `https://<org>.crm.dynamics.com/main.aspx` | uno por entorno de Dataverse |
| Permiso delegado | CMV8 Broker / `user_impersonation`, con consentimiento de administrador | igual |
| Secreto | ninguno (cliente público) | ninguno |
| En el broker | listada en `knownClientApplications` (consentimiento combinado) | igual |

## CLI (az 2.91: el redirect SPA se fija por Graph porque la CLI no tiene `--spa-redirect-uris`)

```bash
ORG=https://<org>.crm.dynamics.com
BROKER=<appId del broker>
SCOPE=$(az ad app show --id "$BROKER" --query "api.oauth2PermissionScopes[?value=='user_impersonation'].id" -o tsv)

APP=$(az ad app create --display-name "CMV8 PCF" --sign-in-audience AzureADMyOrg --query appId -o tsv)
OBJ=$(az ad app show --id "$APP" --query id -o tsv)
az rest --method PATCH --url "https://graph.microsoft.com/v1.0/applications/$OBJ" \
  --headers 'Content-Type=application/json' \
  --body "{\"spa\":{\"redirectUris\":[\"$ORG/\",\"$ORG/main.aspx\"]},\"isFallbackPublicClient\":false}"
az ad sp create --id "$APP"
az ad app permission add --id "$APP" --api "$BROKER" --api-permissions "$SCOPE=Scope"
sleep 15
az ad app permission admin-consent --id "$APP"      # Global Administrator o Privileged Role Administrator

# El broker reconoce al PCF como cliente conocido (además del conector)
BOBJ=$(az ad app show --id "$BROKER" --query id -o tsv)
CONECTOR=<appId del conector>
az rest --method PATCH --url "https://graph.microsoft.com/v1.0/applications/$BOBJ" \
  --headers 'Content-Type=application/json' \
  --body "{\"api\":{\"knownClientApplications\":[\"$CONECTOR\",\"$APP\"]}}"
```

## Portal

Entra ID → App registrations → New registration: nombre, «Accounts in this organizational directory only», sin redirect. Luego Authentication → Add a platform → **Single-page application** con las dos URL del entorno; «Allow public client flows» en No. API permissions → Add → My APIs → CMV8 Broker → `user_impersonation` → Grant admin consent. En la app del broker: Expose an API → Authorized client applications no es lo mismo: lo que hace falta es Manifest → `knownClientApplications` con el appId del PCF.

## Qué configurar en el control

Propiedades del control `AdjuntosGrilla` en la subcuadrícula: `clientIdPcf` = Application ID de esta app, `tenantId`, `brokerClientId` = appId del broker, `urlBroker` = `https://<function>.azurewebsites.net`. Ver `pcf/AdjuntosGrilla/README.md`.

## Riesgos y verificación pendiente (ADR-003 §13)

- Navegadores que bloquean cookies de terceros rompen el iframe silencioso de MSAL: el control cae a popup. En la app móvil de Dynamics y en Teams el popup puede no abrir: a verificar con el Banco.
- Si el acceso condicional del Banco exige más de lo que el token trae, el broker responde 401 con `WWW-Authenticate` y claims; el control reintenta con popup pasando esos claims.
- Una app registration más que gobernar (sin secreto, sin rotación).
