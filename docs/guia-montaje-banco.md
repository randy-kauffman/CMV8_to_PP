# Guía de montaje en el tenant del Banco

Fecha: 2026-10-14. Runbook para levantar la solución completa (Entra, Azure, Function, Dataverse, componente, conector y app) en un tenant y una suscripción del Banco, en el orden en que las piezas se necesitan unas a otras. Reproduce lo que ya funciona en el tenant de desarrollo con los mismos comandos; donde algo depende de una decisión del Banco, lo dice.

Complementa `docs/guia-spikes-banco.md`: esa guía dice QUÉ se prueba adentro y qué evidencia cierra cada punto; esta dice CÓMO se monta lo que esa guía da por existente (su Fase 0). Las pruebas que solo se pueden hacer adentro (red, CM V8 real, IBM i real, acceso condicional con usuarios reales, Defender) siguen allá.

## 0. Antes de empezar

### 0.1 Qué hace falta tener

| Pieza | Qué se necesita | Quién lo da |
| --- | --- | --- |
| Suscripción de Azure | Suscripción con un grupo de recursos nuevo por ambiente (`rg-cmv8-<ambiente>`); rol **Owner** sobre ese grupo (el Bicep asigna roles RBAC, Contributor no alcanza) | Cloud |
| Región | Una que soporte Flex Consumption con Java 21 (`az functionapp list-flexconsumption-runtimes --location <región> --runtime java`) | Cloud |
| Entra ID | Permiso para crear app registrations (Application Developer alcanza para crearlas; **Cloud Application Administrator o superior** para dar consentimiento de administrador) | Identidad |
| Power Platform | Entorno administrado (Managed Environment) con Dataverse; quien corre los scripts es **administrador del entorno**; un administrador de Power Platform para la política DLP | Power Platform |
| Licencias | Todos los usuarios de la app con Power Apps Premium (ya confirmado: el conector personalizado y el componente con `external-service-usage` son premium) | Power Platform |
| Secretos | Contraseña del perfil de servicio de IBM i (solo lectura) y del usuario de servicio de Content Manager. Se cargan en Key Vault desde una estación autorizada, nunca pasan por el repo ni por el chat | IBM i, Content Manager |
| Estación de trabajo | Linux, macOS o WSL con: az CLI 2.91+ con Bicep 0.48+, Java 21 (Temurin) y Maven 3.9+, node 22, pac CLI 2.11+, jq, git, curl | quien monta |

Clonar el repo y pararse en su raíz; todos los comandos de esta guía se corren desde ahí.

### 0.2 Cuaderno de valores

A medida que se avanza aparecen identificadores que los pasos siguientes necesitan. Conviene tener un archivo **fuera del repo** (por ejemplo `~/cmv8-<ambiente>.env`) con estas variables y cargarlo con `source` antes de cada bloque de comandos. Ninguna es secreta; los secretos NO van acá.

```bash
AMBIENTE=        # dev | qa | prod
RG=rg-cmv8-$AMBIENTE
REGION=          # p. ej. eastus2
TENANT_ID=       # az account show --query tenantId -o tsv
BROKER_APP_ID=   # paso 1.1
BROKER_OBJ_ID=   # paso 1.1
BROKER_SCOPE_ID= # paso 1.1
CONECTOR_APP_ID= # paso 1.2
FUNCTION_NAME=   # paso 2.3 (salida functionAppName)
FUNCTION_HOST=   # paso 2.3 (salida functionAppHostName)
MI_CLIENT_ID=    # paso 2.3 (salida identidadClientId)
MI_PRINCIPAL_ID= # paso 2.3 (salida identidadPrincipalId)
STORAGE_ADJ=     # paso 2.3 (salida storageAdjuntosName)
KV_NAME=         # paso 2.3 (salida keyVaultName)
DATAVERSE_URL=   # https://<org>.crm.dynamics.com
ENV_ID=          # id del entorno de Power Platform (pac env list)
```

### 0.3 Orden y dependencias

1. **Entra**: app del broker y app del conector (no dependen de nada).
2. **Azure**: Bicep (necesita el app id del broker para Easy Auth y el OBO).
3. **Credencial federada** del broker (necesita el principal id de la identidad administrada que crea el Bicep).
4. **Easy Auth** en la Function (necesita la Function y el app id del broker).
5. **Código** de la Function (necesita la Function).
6. **Secretos** en Key Vault (necesita el vault; la Function los resuelve al reiniciar).
7. **Dataverse**: tabla, roles y usuario de aplicación (necesita el client id de la identidad).
8. **Componente PCF** (necesita el hostname de la Function y de la cuenta de Storage para el manifiesto).
9. **Conector** (necesita la Function con Easy Auth, las dos apps y el secreto del conector).
10. **Política DLP** y **app canvas**.
11. **Humo**.

Cada paso termina con una verificación. Si la verificación falla, no seguir: el siguiente paso va a fallar peor y más lejos de la causa.

## 1. Entra ID: las dos app registrations (Identidad)

Dos aplicaciones, single tenant. El **broker** es el recurso que protege la Function y el que hace el intercambio OBO hacia Azure Storage. El **conector** es el cliente que Power Platform usa para pedir tokens del broker en nombre del usuario. Ninguna de las dos lleva secreto salvo el conector (Power Platform solo soporta secreto o certificado para el flujo de autorización).

### 1.1 Broker ("CMV8 Broker (Function)")

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

Opcional, solo si la política del Banco lo permite: pre-autorizar a Azure CLI (`04b07795-8ddb-461a-bbee-02f9e1bf7b46`) sobre el scope del broker, para poder probar la Function con `az account get-access-token --resource api://$BROKER_APP_ID` sin pasar por Power Apps. Se agrega en `api.preAuthorizedApplications` con el mismo PATCH. Sin eso, las pruebas directas se hacen con un token obtenido por device code con la app del conector.

Verificación:

```bash
az ad app show --id "$BROKER_APP_ID" --query '{uris:identifierUris, v:api.requestedAccessTokenVersion, scopes:api.oauth2PermissionScopes[].value}'
az ad app permission list-grants --id "$BROKER_APP_ID" --query '[].{scope:scope, tipo:consentType}'
```

Se espera `api://<appId>`, versión `2`, scope `user_impersonation`, y un grant `AllPrincipals` con `user_impersonation` sobre Azure Storage.

### 1.2 Conector ("CMV8 Conector (Power Platform)")

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

**Secreto del conector.** Se genera con vencimiento a 6 meses y va DIRECTO al vault del Banco, sin pasar por la pantalla ni por un archivo. Anotar la fecha de rotación en el calendario del equipo (ver §10).

```bash
FIN=$(date -u -d '+6 months' +%Y-%m-%dT%H:%M:%SZ)
az keyvault secret set --vault-name "<vault de secretos de identidad del Banco>" --name cmv8-conector-secreto \
  --value "$(az ad app credential reset --id "$CONECTOR_APP_ID" --append --display-name power-platform-conector --end-date "$FIN" --query password -o tsv)" \
  --query attributes.expires -o tsv
```

Si el Banco guarda secretos en otro lugar, el patrón es el mismo: la salida del `credential reset` se entrega al gestor de secretos, nunca a la terminal.

La segunda URL de redirección (la específica del conector) se conoce recién después de crear el conector; se agrega en el paso 8.3.

Verificación: `az ad app permission list-grants --id "$CONECTOR_APP_ID"` muestra `user_impersonation` con `AllPrincipals` sobre el broker.

## 2. Azure: infraestructura con Bicep (Cloud)

### 2.1 Qué crea `infra/main.bicep`

Una cuenta de Storage de adjuntos endurecida (HTTPS y TLS 1.2, sin Shared Key, sin acceso público a blobs, política de SAS de 1 hora máximo, retención y borrado automático), un contenedor por solución, una cuenta de Storage para el host de Functions, una identidad administrada asignada por el usuario con Storage Blob Data Contributor **solo sobre el contenedor** y Storage Blob Delegator sobre la cuenta, Log Analytics y Application Insights, un Key Vault con RBAC, y la Function App Flex Consumption (Java 21) con todos los app settings, incluidas las referencias a Key Vault para las contraseñas. Defender for Storage y Event Grid son opcionales y arrancan apagados.

### 2.2 Archivo de parámetros del Banco

Copiar `infra/spikes.bicepparam` a `infra/banco-<ambiente>.bicepparam` (sí se versiona: no tiene secretos) y ajustar:

| Parámetro | Valor en el Banco | Nota |
| --- | --- | --- |
| `prefijo` | `cmv8` | Máximo 8 caracteres; forma los nombres de todos los recursos |
| `dataverseUrl` | `https://<org>.crm.dynamics.com` | El entorno del ambiente |
| `origenesCors` | `['*']` al montar; decisión en la fase 6 de la guía de spikes | CORS no es control de seguridad |
| `ipsPermitidas` | Rangos públicos de salida corporativos, si la carga siempre es desde la red del Banco; `[]` si hay usuarios fuera | Firewall de la cuenta de adjuntos |
| `requerirSasLigadaAUsuario` / `accionSasNoLigada` | `true` / `'Block'` | Ya verificado afuera; no hace falta pasar por `'Log'` |
| `brokerClientId` / `brokerTenantId` | `$BROKER_APP_ID` / `$TENANT_ID` | Easy Auth y OBO |
| `autorizacionModo` | `'dataverse'` | Nunca `'ninguna'` fuera de un spike |
| `habilitarDefender` | `false` al montar; fase 7 de la guía de spikes | Tiene costo fijo por cuenta |
| `crearSuscripcionEventGrid` | `false` al montar; `true` en un segundo despliegue DESPUÉS de publicar el código (paso 5) | La suscripción valida el endpoint |
| `keyVaultAccesoPublico` | `true` hasta que exista la VNet (S0); después `false` con private endpoint | Hoy la Function no tiene VNet |
| `oidOperadorKeyVault` | Object id de la persona que carga los secretos (`az ad signed-in-user show --query id -o tsv`) | Rol Key Vault Secrets Officer |
| `as400Host`, `as400Usuario`, `as400SqlCliente` | Host del IBM i, perfil de servicio y la consulta real con un solo `?` | La contraseña NO va acá |
| `cmisUrl`, `cmisUsuario`, `cmisRepositorio`, `cmisCarpeta`, `cmisTipo` | Valores reales de Content Manager (los entrega CM, ver S4a) | La contraseña NO va acá |
| `diasRetencionAdjuntos` | `7` | Red de seguridad: el worker borra el blob al archivar |
| `bloqueMb` | `4` | Tamaño de bloque que el broker recomienda al componente |

`as400Usuario` en el archivo de spikes viene de una variable de entorno para no dejarlo en el repo del sandbox; en el Banco puede ir literal (es un nombre de perfil, no un secreto) o mantenerse con `readEnvironmentVariable('AS400_USER', '')` y exportar `AS400_USER` antes de desplegar.

### 2.3 Desplegar

```bash
az login --tenant "$TENANT_ID"
az account set -s "<subscription id>"
bash infra/desplegar.sh "$RG" "$REGION" infra/banco-$AMBIENTE.bicepparam
```

El script registra los proveedores (Microsoft.Web, Storage, EventGrid, Insights, OperationalInsights, ManagedIdentity, Security, KeyVault, App), verifica la región, crea el grupo con etiquetas, valida y despliega. Tarda 5 a 8 minutos. Las salidas quedan en `infra/ultimo-despliegue.outputs.json` (ignorado por git); de ahí salen `FUNCTION_NAME`, `FUNCTION_HOST`, `MI_CLIENT_ID`, `MI_PRINCIPAL_ID`, `STORAGE_ADJ` y `KV_NAME` del cuaderno:

```bash
jq -r '.functionAppName.value, .functionAppHostName.value, .identidadClientId.value, .identidadPrincipalId.value, .storageAdjuntosName.value, .keyVaultName.value' infra/ultimo-despliegue.outputs.json
```

Verificación: `az functionapp show -g "$RG" -n "$FUNCTION_NAME" --query state` → `Running`; `az storage account show -g "$RG" -n "$STORAGE_ADJ" --query '{sharedKey:allowSharedKeyAccess, publico:allowBlobPublicAccess, tls:minimumTlsVersion}'` → `false`, `false`, `TLS1_2`.

Presupuesto: crear uno sobre el grupo de recursos con alertas (como el `cmv8-spikes-mensual` del sandbox) antes de seguir. Costos estimados en `docs/costos-estimados.md`.

## 3. Credencial federada del broker (Identidad)

La app del broker confía en la identidad administrada de la Function: el OBO se firma con un token de esa identidad y no hay secreto que rotar. Necesita el `MI_PRINCIPAL_ID` del paso anterior.

```bash
cat > /tmp/fc.json <<EOF
{
  "name": "uami-cmv8-broker",
  "issuer": "https://login.microsoftonline.com/$TENANT_ID/v2.0",
  "subject": "$MI_PRINCIPAL_ID",
  "audiences": ["api://AzureADTokenExchange"]
}
EOF
az ad app federated-credential create --id "$BROKER_OBJ_ID" --parameters /tmp/fc.json -o none
az ad app federated-credential list --id "$BROKER_OBJ_ID" --query '[].{name:name, subject:subject}'
```

El `subject` es el **principal id** (object id del service principal) de la identidad, no su client id. Si se confunden, el OBO falla con `AADSTS700211` o `AADSTS70021` al primer `POST /api/sas/subida`.

## 4. Easy Auth en la Function (Cloud)

La Function no valida tokens en código: la autenticación integrada de App Service valida el token de Entra antes de que la request llegue a Java e inyecta `X-MS-CLIENT-PRINCIPAL-*`. Sin este paso la Function está abierta y el `oid` es declarativo. **No publicar código con datos reales antes de este paso.**

```bash
SUB=$(az account show --query id -o tsv)
cat > /tmp/easyauth.json <<EOF
{
  "properties": {
    "platform": { "enabled": true, "runtimeVersion": "~1" },
    "globalValidation": {
      "requireAuthentication": true,
      "unauthenticatedClientAction": "Return401",
      "excludedPaths": ["/runtime/webhooks/eventgrid", "/api/salud"]
    },
    "identityProviders": {
      "azureActiveDirectory": {
        "enabled": true,
        "registration": {
          "clientId": "$BROKER_APP_ID",
          "openIdIssuer": "https://login.microsoftonline.com/$TENANT_ID/v2.0"
        },
        "validation": {
          "allowedAudiences": ["api://$BROKER_APP_ID", "$BROKER_APP_ID"]
        },
        "login": { "disableWWWAuthenticate": false }
      }
    },
    "login": { "tokenStore": { "enabled": true } }
  }
}
EOF
az rest --method PUT \
  --url "https://management.azure.com/subscriptions/$SUB/resourceGroups/$RG/providers/Microsoft.Web/sites/$FUNCTION_NAME/config/authsettingsV2?api-version=2023-12-01" \
  --body @/tmp/easyauth.json -o none
```

Por qué cada cosa: issuer **v2** porque la app pide tokens v2; dos audiencias porque un token v2 trae el GUID pelado en `aud`; `/runtime/webhooks/eventgrid` excluido porque Event Grid entrega con la clave de sistema del host, no con Entra; `/api/salud` excluido para que el monitoreo y el despliegue puedan verificar sin token. Easy Auth no necesita credencial de la app: solo valida firmas.

Verificación (GET sobre la misma URL): `requireAuthentication: true`, `unauthenticatedClientAction: Return401`, el issuer y las dos audiencias.

## 5. Publicar el código de la Function (Cloud)

```bash
cd function
mvn clean package azure-functions:deploy -DfunctionAppName="$FUNCTION_NAME" -DresourceGroup="$RG" -Dregion="$REGION"
cd ..
```

En Flex Consumption se publica con el plugin de Maven; `func azure functionapp publish` no es el camino soportado para este plan. La primera publicación compila y corre los tests unitarios (56) antes de subir.

Verificación:

```bash
curl -s -o /dev/null -w '%{http_code}\n' "https://$FUNCTION_HOST/api/salud"                 # 200
curl -s -o /dev/null -w '%{http_code}\n' -X POST "https://$FUNCTION_HOST/api/sas/subida"     # 401
```

Si el 200 tarda en aparecer, esperar un minuto: el host de Flex arranca en frío. Si el segundo da 200 en vez de 401, Easy Auth no quedó aplicado: volver al paso 4.

Recién ahora, si se va a usar Event Grid (con o sin Defender), poner `crearSuscripcionEventGrid = true` y repetir el paso 2.3: la suscripción valida que el endpoint `OnScanResult` / `OnBlobCreated` exista.

## 6. Secretos en Key Vault (quien tenga el rol de operador)

Las contraseñas del IBM i y de Content Manager viven en Key Vault; la Function las lee como referencias `@Microsoft.KeyVault(VaultName=…;SecretName=…)` con su identidad administrada. El script las toma de un archivo con el formato de `function/local.settings.json`, y nunca las imprime.

```bash
# archivo FUERA del repo, solo con las dos claves, permisos 600
cat > ~/cmv8-$AMBIENTE-secretos.json <<'EOF'
{ "Values": { "AS400_PASSWORD": "<contraseña del perfil de IBM i>", "CMIS_PASSWORD": "<contraseña del usuario de CM>" } }
EOF
chmod 600 ~/cmv8-$AMBIENTE-secretos.json
bash infra/cargar-secretos.sh "$RG" ~/cmv8-$AMBIENTE-secretos.json
rm ~/cmv8-$AMBIENTE-secretos.json
az functionapp restart -g "$RG" -n "$FUNCTION_NAME"
```

Verificación: todas las referencias en `Resolved`.

```bash
az rest --method GET \
  --url "https://management.azure.com/subscriptions/$SUB/resourceGroups/$RG/providers/Microsoft.Web/sites/$FUNCTION_NAME/config/configreferences/appsettings?api-version=2023-12-01" \
  --query 'value[].{setting:name, estado:properties.status}' -o table
```

Si una queda en `UnauthorizedClient` o `AccessToKeyVaultDenied`, el rol Key Vault Secrets User de la identidad todavía no propagó (hasta 10 minutos) o el vault quedó con `keyVaultAccesoPublico=false` sin private endpoint.

## 7. Dataverse (Power Platform)

### 7.1 Requisitos del entorno

- Entorno administrado del ambiente, con Dataverse.
- Habilitado **Power Apps component framework for canvas apps** (Centro de administración → Entornos → Configuración → Producto → Características). Sin eso el componente no se puede importar.
- Si el entorno tiene **firewall de IP de Dataverse**, la IP de salida de la Function tiene que estar permitida; esa IP depende de la red (S0 de la guía de spikes). Hasta resolverlo, el broker responde 502 al consultar Dataverse.

### 7.2 Tabla, roles y usuario de aplicación

```bash
az login --tenant "$TENANT_ID"       # con un administrador del entorno
DATAVERSE_URL="$DATAVERSE_URL" bash dataverse/crear-tabla.sh
DATAVERSE_URL="$DATAVERSE_URL" APP_CLIENT_ID="$MI_CLIENT_ID" bash dataverse/crear-roles.sh
```

El primero crea publisher (prefijo `cmv8`), solución `cmv8ingesta`, la tabla `cmv8_solicitudingesta` y sus columnas, y publica. El segundo crea los roles **CMV8 Worker** (la Function: lectura y escritura global de la tabla, impersonación, lectura de usuarios y unidades) y **CMV8 Solicitante** (usuarios: crear, leer y escribir SOLO lo propio), da de alta la identidad administrada como usuario de aplicación y le asigna CMV8 Worker. Los dos scripts son idempotentes.

`APP_CLIENT_ID` es el **client id** de la identidad administrada (salida `identidadClientId`), no el principal id. Es el mismo valor que la Function usa en `AZURE_CLIENT_ID`.

### 7.3 Usuarios piloto

Asignar el rol **CMV8 Solicitante** a cada usuario piloto (Centro de administración → Entornos → Usuarios → Administrar roles, o `pac admin assign-user`). Ningún usuario de negocio recibe CMV8 Worker, y la identidad no recibe ningún rol de sistema.

Verificación: en el centro de administración, Usuarios de aplicación muestra `CMV8 Function` con el rol CMV8 Worker; el explorador de soluciones muestra la tabla con sus columnas dentro de `cmv8ingesta`.

## 8. Componente PCF y conector (Power Platform)

### 8.1 Componente

Antes de compilar, el manifiesto tiene que declarar los dominios reales: Power Apps bloquea cualquier llamada a un dominio que no esté en `external-service-usage`.

```bash
python3 - "$FUNCTION_HOST" "$STORAGE_ADJ" <<'EOF'
import re, sys, pathlib
p = pathlib.Path("pcf/CargaAdjuntoBlob/ControlManifest.Input.xml")
x = p.read_text()
x = re.sub(r"<domain>[^<]*azurewebsites\.net</domain>", f"<domain>{sys.argv[1]}</domain>", x)
x = re.sub(r"<domain>[^<]*blob\.core\.windows\.net</domain>", f"<domain>{sys.argv[2]}.blob.core.windows.net</domain>", x)
p.write_text(x)
EOF
rg -n '<domain>' pcf/CargaAdjuntoBlob/ControlManifest.Input.xml
```

Después, compilar y subir al entorno:

```bash
cd pcf
npm ci
npm run build -- --buildMode production
pac auth create --environment "$ENV_ID"
pac pcf push --publisher-prefix cmv8 --environment "$ENV_ID"
cd ..
```

`pac pcf push` sube el control en una solución temporal (`PowerAppsTools_cmv8`); para el ALM del Banco el control se agrega a la solución `cmv8ingesta` desde el diseñador de soluciones o se exporta con `pac solution` cuando esté todo adentro. El namespace del control es `Banco` (manifiesto); si el Banco tiene una convención de namespaces, cambiarlo ANTES del primer push, porque después el control ya existe con ese nombre.

Verificación: `pac pcf push` termina con el control importado y publicado; en Studio, Insertar → Obtener más componentes → Código muestra `CargaAdjuntoBlob`.

### 8.2 Conector personalizado

El swagger y las propiedades del repo apuntan al sandbox. Se arma una copia **fuera del repo** con los valores del Banco y el secreto del conector, se crea el conector, y se borra la copia.

```bash
T=$(mktemp -d); chmod 700 "$T"
jq --arg h "$FUNCTION_HOST" '.host = $h' dataverse/conector/cmv8-broker.swagger.json > "$T/swagger.json"
jq --arg c "$CONECTOR_APP_ID" --arg t "$TENANT_ID" --arg r "api://$BROKER_APP_ID" \
   --arg s "$(az keyvault secret show --vault-name '<vault de identidad>' --name cmv8-conector-secreto --query value -o tsv)" \
   '.properties.connectionParameters.token.oAuthSettings.clientId = $c
    | .properties.connectionParameters.token.oAuthSettings.clientSecret = $s
    | .properties.connectionParameters.token.oAuthSettings.customParameters.tenantId.value = $t
    | .properties.connectionParameters.token.oAuthSettings.customParameters.resourceUri.value = $r' \
   dataverse/conector/apiProperties.json > "$T/apiProperties.json"

pac connector create \
  --api-definition-file "$T/swagger.json" \
  --api-properties-file "$T/apiProperties.json" \
  --solution-unique-name cmv8ingesta \
  --environment "$ENV_ID"
rm -rf "$T"
pac connector list --environment "$ENV_ID"
```

Si `pac connector create` falla con `PessimisticConcurrencyConflict` ("Operation on the API is in progress"), no es un error de la definición: reintentar a los 5 minutos sin cambiar nada (pasó dos veces en el sandbox).

Para versionar los valores del Banco sin el secreto, se puede guardar `swagger.json` y un `apiProperties.json` con el marcador en `dataverse/conector/banco-<ambiente>/`; el secreto siempre se inyecta en el momento de crear o actualizar.

### 8.3 URL de redirección específica

Al crear la conexión, Power Platform no usa la URL global sino una **específica del conector** (`https://global.consent.azure-apim.net/redirect/<nombre interno>-<hash>`). Si no está registrada en la app del conector, Entra responde `AADSTS50011`.

1. Abrir el conector en make.powerapps.com → Conectores personalizados → CMV8 Broker → Seguridad → campo **URL de redirección**. Copiarla.
2. Registrarla junto con la global:

```bash
az ad app update --id "$CONECTOR_APP_ID" --web-redirect-uris \
  https://global.consent.azure-apim.net/redirect \
  "<URL específica copiada>"
```

Cada vez que el conector se recrea cambia el hash y hay que repetirlo; `pac connector update` sobre el mismo id no lo cambia.

### 8.4 Política DLP

El conector **CMV8 Broker** va en el grupo **Business** de la política DLP del entorno, junto con Dataverse. Si queda en Non-business o en Blocked, la app no puede usarlo con Dataverse en la misma pantalla. Lo hace un administrador de Power Platform en el centro de administración → Políticas → Directivas de datos.

Verificación: crear una conexión del conector con un usuario piloto desde make.powerapps.com → Conexiones → Nueva → CMV8 Broker. Entra pide iniciar sesión y, si el consentimiento de administrador del paso 1.2 está dado, no pide consentimiento individual. Probar la operación `Salud` desde la pestaña Prueba del conector: 200.

## 9. App canvas (Power Platform)

La app se construye con `docs/checklist-construccion-app.md`: la respuesta del conector trae la SAS y el token de Storage del usuario, y la app los guarda en UNA variable, se los pasa al componente y los limpia al terminar; nunca los persiste ni los muestra. Las fórmulas de referencia del botón Preparar y del `OnChange` están ahí.

Resumen del cableado del componente: `uploadUrl = varSas.uploadUrl`, `tokenStorage = varSas.tokenStorage`, `bloqueBytes = varSas.bloqueBytes`, `solicitudId = Text(varFila.'Solicitud de ingesta')`, `urlBroker` vacío (al broker lo llama el conector, no el componente).

Compartir la app y el conector con los usuarios piloto. La conexión del conector es por usuario; nunca compartida ni embebida con una cuenta de servicio.

## 10. Humo y entrega

Correr la fase 0.6 de `docs/guia-spikes-banco.md` (S2 y S7 en corto): un piloto sube 100 MB desde Studio; otro usuario sin el rol pide SAS sobre esa fila y recibe 403; `StorageBlobLogs` muestra `PutBlock` y `PutBlockList` 201 con `AuthenticationType = DelegationSas` y el UPN del piloto; la fila pasa a Subido. La evidencia queda en `spikes/resultados/BANCO-0-cimientos.md` con el costo de Azure del montaje.

A partir de acá siguen las fases de la guía de spikes: S0 red, hallazgo 10, S4a, S6 real, CORS, Defender, S5.

## 11. Operación

| Qué | Cuándo | Cómo |
| --- | --- | --- |
| Rotar el secreto del conector | Antes de los 6 meses | `az ad app credential reset … --append` al vault (paso 1.2), `pac connector update --connector-id <id>` con la copia temporal (paso 8.2), borrar la credencial vieja con `az ad app credential delete`. Las conexiones existentes siguen hasta que el usuario vuelva a iniciar sesión |
| Rotar contraseñas de IBM i o CM | Según la política del Banco | Paso 6 de nuevo y `az functionapp restart`; la Function toma el valor nuevo al reiniciar (o sola en 24 h) |
| Publicar una versión nueva de la Function | Cada cambio | Paso 5; `/api/salud` devuelve la versión |
| Publicar una versión nueva del componente | Cada cambio | Subir `version` en el manifiesto y repetir 8.1; la app toma la versión nueva al abrirla en Studio |
| Cambiar la Function de hostname o de cuenta de Storage | Nunca sin plan | Hay que repetir 8.1 (manifiesto) y 8.2 (swagger) |
| Vigilar | Siempre | App Insights: trazas `SAS de subida emitida`, `OBO del broker con la identidad administrada`, excepciones del worker; `StorageBlobLogs` para 403; presupuesto con alertas |

### Errores que ya se vieron y qué significan

| Síntoma | Causa | Dónde se arregla |
| --- | --- | --- |
| `AADSTS50011` al crear la conexión | Falta la URL de redirección específica del conector | 8.3 |
| 401 de la Function con token válido | Audiencia o issuer de Easy Auth distintos de los del token (v1 contra v2, GUID contra `api://`) | 4 |
| 403 `AccesoDenegado` del broker | El usuario no tiene CMV8 Solicitante o la fila no es suya | 7.3 |
| 409 del broker | La fila no está en Creada | la app crea la fila antes de pedir la SAS |
| 401 con `WWW-Authenticate: Bearer error="interaction_required"` | Acceso condicional sobre Azure Storage que el token del conector no satisface | hallazgo 10 de la guía de spikes |
| 403 `AuthorizationPermissionMismatch` al subir | Faltó el bearer de Storage junto con la SAS ligada, o el usuario tiene un rol de datos que no debería | checklist de la app; roles del contenedor |
| 502 del broker al autorizar | Dataverse inaccesible: firewall de IP o usuario de aplicación sin rol | 7.1, 7.2 |
| `AADSTS700211` / `AADSTS70021` en el OBO | Credencial federada con subject equivocado | 3 |
| `PessimisticConcurrencyConflict` en `pac connector create` | Operación previa en curso en el APIM interno | reintentar a los 5 minutos |

## 12. Lo que esta guía no cubre todavía

- **VNet, NAT y private endpoints** de Storage y Key Vault: dependen de las respuestas de Red (S0) y de código que se agrega al Bicep antes de esa fase (`virtualNetworkSubnetId`, private endpoint del vault, función `/api/diagnostico/red`).
- **Conectividad real a Content Manager e IBM i**: certificados, puertos y perfil de servicio, en S4a y S6 de la guía de spikes.
- **Defender for Storage**: fase 7 de la guía de spikes, con su costo.
- **Exportación de la solución** `cmv8ingesta` como paquete administrado para pasar de dev a qa y prod: cuando el componente y el conector estén adentro y el Banco defina su pipeline de ALM.
