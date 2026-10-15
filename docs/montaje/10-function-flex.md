# 10. Plan Flex Consumption y Function App

> Rol que lo ejecuta: Cloud. Depende de: 01 (`BROKER_APP_ID`), 03 a 08 (grupo, Log Analytics y Application Insights, identidad asignada por el usuario, storage host con su contenedor de despliegue, storage de adjuntos, roles de la identidad sobre los dos storage y sobre App Insights), 09 (vault y rol Secrets User de la identidad). Lo necesitan: 11 (la suscripción de Event Grid apunta a `OnBlobCreated`), 12 (`OnScanResult`), 14 (credencial federada con el principal de la identidad), 15 (Easy Auth sobre este sitio), 16 (publicación del código). Costo: Flex Consumption escala a cero; ~4 USD/mes en el escenario chico y ~66 USD/mes en el mediano (`docs/costos-estimados.md` §4), sin costo fijo mientras no se configuren instancias Always Ready.

## Qué es y qué hace en esta solución

La Function App Java 21 es la única pieza de código del lado de Azure. Cumple dos papeles en el mismo sitio:

- **Broker de SAS** (HTTP, detrás de Easy Auth): verifica en Dataverse, suplantando al usuario, que la fila de `cmv8_solicitudingesta` es suya y está en el estado correcto; pide una clave de delegación de usuario con su identidad administrada; emite una SAS de un solo blob ligada al usuario (`sduoid`); y obtiene por OBO el token de Storage del usuario que el componente PCF manda junto con la SAS (ADR-002, "Diseño de dos llaves").
- **Worker de ingesta**: `OnBlobCreated` (o `OnScanResult` con Defender) recibe el evento de Event Grid y encola un mensaje en `%COLA_INGESTA%`; `ProcesarIngesta` toma el mensaje de la cola, lee el blob como stream, resuelve metadatos en el IBM i (jt400), crea el documento en Content Manager (OpenCMIS), actualiza Dataverse y borra el blob; `Watchdog` corre cada 15 minutos y reencola las solicitudes que quedaron en Subido.

El plan es **Flex Consumption** (`FC1`, Linux): escala por evento hasta `maximumInstanceCount` y a cero sin tráfico; el paquete de la app vive en un contenedor blob del storage host, al que la plataforma accede con la identidad asignada por el usuario (sin connection string). El runtime de Functions (`AzureWebJobsStorage`) también usa esa identidad. No hay ningún secreto en la configuración: las contraseñas son referencias de Key Vault (guía 09) y el OBO usa la identidad como credencial federada (guía 14).

## ¿Imprescindible?

**Veredicto: Imprescindible** (la Function). El **plan Flex Consumption** es **Prescindible por decisión**.

- Qué aporta: la Function es el único componente que puede firmar una SAS de delegación de usuario ligada al usuario (necesita una identidad con Storage Blob Delegator y la lógica de autorización contra Dataverse) y el único que habla con el IBM i y con Content Manager. El plan Flex aporta pago por uso con escala a cero, integración de VNet (necesaria para llegar a CM e IBM i por la red del Banco), identidad administrada para el paquete de despliegue y para el runtime, y memoria de instancia elegible (2048 MB alcanza para 100 MB en streaming, S4).
- Qué pasa si no está: sin Function no hay SAS ligada al usuario (el PCF no puede subir de forma segura) ni ingesta a Content Manager; la solución no funciona. Sin Flex (con otro plan), la solución funciona igual; cambia el costo y la forma de despliegue.
- Qué decisión lo haría prescindible (o distinto): para la Function, ninguna dentro de ADR-002. Para el plan: que el Banco no apruebe Flex Consumption, que la región elegida no lo soporte con Java 21 (`az functionapp list-flexconsumption-runtimes --location "$REGION" --runtime java`) o que una política exija instancias siempre encendidas.
- Alternativa y su tradeoff: **Premium EP1** (Elastic Premium, Linux). Mismo soporte de VNet, sin arranque en frío, despliegue con el mismo plugin de Maven (cambia la forma del paquete). Costo: al menos una instancia siempre encendida, 0,16 USD/h de vCPU + 0,0114 USD/GiB-h de memoria (Azure Retail Prices, eastus2, 2026-10-14); EP1 (1 vCPU, 3,5 GB) ≈ 146 USD/mes fijos, contra ~4 a ~66 USD/mes variables de Flex en los escenarios de `docs/costos-estimados.md` §4. Con Flex, el arranque en frío se mitiga con una instancia Always Ready (~21 USD/mes, mismo documento) sin cambiar de plan. Pasar a EP1 exige cambiar en el Bicep el `sku` del plan, quitar `functionAppConfig` y volver a `siteConfig.linuxFxVersion` + `WEBSITE_CONTENTAZUREFILECONNECTIONSTRING` (Premium usa Azure Files; con Shared Key deshabilitado en el storage host eso no funciona sin cambios adicionales, a verificar antes de decidir).

## Valores del cuaderno

| Variable | Produce / consume | Cómo se obtiene |
| --- | --- | --- |
| `RG`, `REGION`, `TENANT_ID`, `DATAVERSE_URL`, `BROKER_APP_ID` | Consume | Cuaderno |
| `SUB` | Produce (local) | `az account show --query id -o tsv` |
| `PREFIJO`, `SUFIJO` | Consume | Convención de nombres (guía 09) |
| `PLAN_NAME` | Produce | `${PREFIJO}-plan-${SUFIJO}` |
| `FUNCTION_NAME` | Produce | Bicep: salida `functionAppName`. A mano: `${PREFIJO}-func-${SUFIJO}` |
| `FUNCTION_HOST` | Produce | Bicep: salida `functionAppHostName`. A mano: `az functionapp show -g "$RG" -n "$FUNCTION_NAME" --query properties.defaultHostName -o tsv` |
| `MI_ID` | Consume | `az identity show -g "$RG" -n "${PREFIJO}-mi-${SUFIJO}" --query id -o tsv` |
| `MI_CLIENT_ID` | Consume | Salida `identidadClientId` o `az identity show ... --query clientId -o tsv` |
| `STORAGE_HOST` | Consume | Salida `storageHostName`; a mano `${PREFIJO}host${SUFIJO}` |
| `CONTENEDOR_DESPLIEGUE` | Consume | `app-package-${SUFIJO}` (lo crea la guía del storage host) |
| `STORAGE_ADJ`, `CONTENEDOR_ADJ` | Consume | Salidas `storageAdjuntosName`, `contenedorAdjuntosName` (`cmv8-ingesta`) |
| `APPI_NAME`, `APPI_CONN` | Consume | `${PREFIJO}-appi-${SUFIJO}`; `az resource show -g "$RG" -n "$APPI_NAME" --resource-type Microsoft.Insights/components --query properties.ConnectionString -o tsv` |
| `KV_NAME` | Consume | Guía 09 |
| `AS400_HOST`, `AS400_USER`, `AS400_SQL`, `CMIS_URL`, `CMIS_USER`, `CMIS_REPO`, `CMIS_CARPETA`, `CMIS_TIPO` | Consume | Los entregan IBM i y Content Manager (no son secretos; las contraseñas van a Key Vault) |

## Con Bicep

Está en `infra/main.bicep`, sección "Function App en Flex Consumption": plan `plan` (líneas 419 a 430, `Microsoft.Web/serverfarms@2024-04-01`), sitio `functionApp` (líneas 432 a 483, `Microsoft.Web/sites@2024-04-01`) y su hijo `configuracion` (`config/appsettings`, líneas 484 a 512). El sitio espera (`dependsOn`) a los roles de la identidad sobre el storage host (Blob Data Owner, Queue y Table Data Contributor) y al rol de Key Vault: así el primer arranque ya puede leer el paquete, las colas y las referencias.

| Parámetro | Qué gobierna | Valor recomendado en el Banco |
| --- | --- | --- |
| `location` | Región del plan y del sitio (debe soportar Flex con Java) | La del grupo |
| `prefijo` | Nombres `${prefijo}-plan-…` y `${prefijo}-func-…` | `cmv8` |
| `javaVersion` | `functionAppConfig.runtime.version` | `'21'` |
| `maximumInstanceCount` | Tope de escala (40 a 1000) | `100` (techo de costo y de conexiones a CM/IBM i; bajarlo a 40 si CM limita sesiones) |
| `instanceMemoryMB` | Memoria por instancia (2048 o 4096) | `2048` (S4: 100 MB en streaming con `-Xmx64m`) |
| `origenesCors` | `siteConfig.cors.allowedOrigins` | `['*']` (decisión pendiente; ADR-002, "CORS `*`") |
| `dataverseUrl`, `autorizacionModo`, `bloqueMb`, `requerirSasLigadaAUsuario`, `habilitarDefender`, `brokerClientId`, `brokerTenantId` | App settings (ver tabla abajo) | `docs/guia-montaje-banco.md`, sección 2 |
| `as400Host`, `as400Usuario`, `as400SqlCliente`, `cmisUrl`, `cmisUsuario`, `cmisRepositorio`, `cmisCarpeta`, `cmisTipo` | Bloques `AS400_*` y `CMIS_*`; vacíos = el bloque no se crea | Valores reales del Banco |

Fijado sin parámetro: `sku: FC1 / FlexConsumption`, `reserved: true`, `kind: functionapp,linux`, identidad `UserAssigned`, `httpsOnly: true`, `keyVaultReferenceIdentity` = la identidad, `minTlsVersion: 1.2`, `http20Enabled: true`, `cors.supportCredentials: false`, paquete en `blobContainer` del storage host autenticado con `UserAssignedIdentity`.

**Importante:** `config/appsettings` en Bicep **reemplaza** todos los app settings en cada despliegue. Cualquier setting agregado a mano (portal o CLI) desaparece en el próximo `infra/desplegar.sh`. Todo cambio de configuración va al Bicep o al `.bicepparam`.

### App settings (líneas 489 a 511)

| Setting | Valor | Para qué |
| --- | --- | --- |
| `AzureWebJobsStorage__accountName` | Nombre del storage host | El runtime (colas de triggers, leases del timer, claves del host) usa ese storage sin connection string |
| `AzureWebJobsStorage__credential` | `managedidentity` | Autenticación por identidad administrada |
| `AzureWebJobsStorage__clientId` | Client id de la identidad | Qué identidad usar (la Function no tiene identidad del sistema) |
| `APPLICATIONINSIGHTS_CONNECTION_STRING` | Connection string de App Insights | Destino de la telemetría |
| `APPLICATIONINSIGHTS_AUTHENTICATION_STRING` | `ClientId=<client id>;Authorization=AAD` | App Insights tiene `DisableLocalAuth=true`: la telemetría se envía con token de Entra de la identidad (rol Monitoring Metrics Publisher) |
| `AZURE_CLIENT_ID` | Client id de la identidad | `DefaultAzureCredential` / `ManagedIdentityCredential` de azure-identity eligen esa identidad (Storage, Dataverse, OBO) |
| `ADJUNTOS_STORAGE_ACCOUNT` | Nombre de la cuenta de adjuntos | Cuenta sobre la que se pide la clave de delegación |
| `ADJUNTOS_BLOB_ENDPOINT` | `https://<cuenta>.blob.core.windows.net/` | Base de las URLs de SAS y de lectura del worker |
| `ADJUNTOS_CONTAINER` | `cmv8-ingesta` | Contenedor de la solución; el worker ignora eventos de otros contenedores |
| `DATAVERSE_URL` | `https://<org>.crm.dynamics.com` | Entorno donde se verifica acceso y se actualiza la fila |
| `SAS_UPLOAD_MINUTOS` | `15` | Vigencia de la SAS de subida |
| `SAS_READ_MINUTOS` | `5` | Vigencia de la SAS de lectura |
| `COLA_INGESTA` | `ingesta-pendiente` | Nombre de la cola (`%COLA_INGESTA%` en los bindings); la poison queue es `ingesta-pendiente-poison` |
| `AUTORIZACION_MODO` | `dataverse` | Verificar acceso a la fila por suplantación. `ninguna` solo en spikes |
| `SAS_LIGADA_USUARIO` | `true` / `false` según `requerirSasLigadaAUsuario` | Firmar la SAS con `sduoid` y devolver el token OBO de Storage |
| `ESCANEO_REQUERIDO` | `true` / `false` según `habilitarDefender` | Con `true` el disparador esperado es `OnScanResult` (guía 12) |
| `ESTANCADA_MINUTOS` | `15` | Minutos en Subido a partir de los cuales el Watchdog reencola |
| `SAS_BLOQUE_MB` | `4` (`bloqueMb`) | Tamaño de bloque que el broker recomienda al PCF |
| `BROKER_CLIENT_ID` | `$BROKER_APP_ID` | App registration del broker: cliente del OBO |
| `BROKER_TENANT_ID` | `$TENANT_ID` | Autoridad del OBO |
| `AS400_HOST` | Host del IBM i | Conexión jt400 (solo si `as400Host` no está vacío) |
| `AS400_USER` | Perfil de servicio | Usuario jt400 |
| `AS400_SECURE` | `true` | jt400 por TLS |
| `AS400_SQL_CLIENTE` | Consulta con un solo `?` | Devuelve IDCLIENTE, IDCLIENTE2, NOMBRE, AFILIADOPADRE, IDDUENO |
| `AS400_PASSWORD` | `@Microsoft.KeyVault(VaultName=<vault>;SecretName=as400-password)` | Contraseña resuelta desde Key Vault (guía 09) |
| `CMIS_URL` | URL del Browser binding de CM | Cliente OpenCMIS (solo si `cmisUrl` no está vacío) |
| `CMIS_USER` | Usuario de servicio de CM | Usuario CMIS |
| `CMIS_REPOSITORY_ID` | Id del repositorio | Repositorio destino |
| `CMIS_FOLDER_PATH` | Ruta de carpeta | Carpeta destino |
| `CMIS_TYPE_ID` | Tipo de documento | Tipo con los metadatos del Banco |
| `CMIS_PASSWORD` | `@Microsoft.KeyVault(VaultName=<vault>;SecretName=cmis-password)` | Contraseña resuelta desde Key Vault |

No hay `BROKER_CLIENT_SECRET`: el OBO usa la identidad como credencial federada (guía 14). El secreto solo existe en `function/local.settings.json` para desarrollo local.

## Con Azure CLI

Verificado con `az --help` (az CLI 2.91). Hay dos caminos; el primero reproduce el Bicep propiedad por propiedad.

### Camino A (recomendado): plan y sitio por ARM, ajustes con `az functionapp`

Por qué no `az functionapp create`: (1) `az functionapp plan create --sku` no ofrece `FC1`, así que el plan con nombre propio solo se crea por ARM; con `--flexconsumption-location` el create genera un plan con nombre automático. (2) Sin `--app-insights` el create crea un Application Insights nuevo, y sin más flags escribe un `AzureWebJobsStorage` con connection string, que el storage host (Shared Key deshabilitado) rechaza.

```bash
source ~/cmv8-$AMBIENTE.env
SUB=$(az account show --query id -o tsv)
PLAN_NAME="${PREFIJO}-plan-${SUFIJO}"
FUNCTION_NAME="${PREFIJO}-func-${SUFIJO}"
STORAGE_HOST="${PREFIJO}host${SUFIJO}"
CONTENEDOR_DESPLIEGUE="app-package-${SUFIJO}"
MI_ID=$(az identity show -g "$RG" -n "${PREFIJO}-mi-${SUFIJO}" --query id -o tsv)
MI_CLIENT_ID=$(az identity show -g "$RG" -n "${PREFIJO}-mi-${SUFIJO}" --query clientId -o tsv)
BASE="https://management.azure.com/subscriptions/$SUB/resourceGroups/$RG/providers/Microsoft.Web"

# 1. plan Flex Consumption (FC1, Linux)
az rest --method PUT --url "$BASE/serverfarms/$PLAN_NAME?api-version=2024-04-01" --body "{
  \"location\": \"$REGION\",
  \"kind\": \"functionapp\",
  \"sku\": { \"tier\": \"FlexConsumption\", \"name\": \"FC1\" },
  \"properties\": { \"reserved\": true }
}" -o none

# 2. sitio: identidad, plan, HTTPS, KV references, TLS, HTTP/2, CORS, paquete, escala y runtime
az rest --method PUT --url "$BASE/sites/$FUNCTION_NAME?api-version=2024-04-01" --body "{
  \"location\": \"$REGION\",
  \"kind\": \"functionapp,linux\",
  \"identity\": { \"type\": \"UserAssigned\", \"userAssignedIdentities\": { \"$MI_ID\": {} } },
  \"properties\": {
    \"serverFarmId\": \"/subscriptions/$SUB/resourceGroups/$RG/providers/Microsoft.Web/serverfarms/$PLAN_NAME\",
    \"httpsOnly\": true,
    \"keyVaultReferenceIdentity\": \"$MI_ID\",
    \"siteConfig\": {
      \"minTlsVersion\": \"1.2\",
      \"http20Enabled\": true,
      \"cors\": { \"allowedOrigins\": [\"*\"], \"supportCredentials\": false }
    },
    \"functionAppConfig\": {
      \"deployment\": { \"storage\": {
        \"type\": \"blobContainer\",
        \"value\": \"https://$STORAGE_HOST.blob.core.windows.net/$CONTENEDOR_DESPLIEGUE\",
        \"authentication\": { \"type\": \"UserAssignedIdentity\", \"userAssignedIdentityResourceId\": \"$MI_ID\" }
      } },
      \"scaleAndConcurrency\": { \"maximumInstanceCount\": 100, \"instanceMemoryMB\": 2048 },
      \"runtime\": { \"name\": \"java\", \"version\": \"21\" }
    }
  }
}" -o none

FUNCTION_HOST=$(az functionapp show -g "$RG" -n "$FUNCTION_NAME" --query properties.defaultHostName -o tsv)
```

Antes del paso 2 los roles de la identidad sobre el storage host y el rol Secrets User sobre el vault deben existir (guías 03 a 09): son el `dependsOn` del Bicep.

### Camino B: `az functionapp create` y ajustes posteriores

Sirve si se acepta el nombre automático del plan. El create recibe todo lo de Flex; lo demás se fija después.

```bash
az functionapp create \
  --resource-group "$RG" \
  --name "$FUNCTION_NAME" \
  --storage-account "$STORAGE_HOST" \
  --flexconsumption-location "$REGION" \
  --runtime java \
  --runtime-version 21.0 \
  --functions-version 4 \
  --instance-memory 2048 \
  --maximum-instance-count 100 \
  --deployment-storage-name "$STORAGE_HOST" \
  --deployment-storage-container-name "$CONTENEDOR_DESPLIEGUE" \
  --deployment-storage-auth-type UserAssignedIdentity \
  --deployment-storage-auth-value "$MI_ID" \
  --assign-identity "$MI_ID" \
  --app-insights "${PREFIJO}-appi-${SUFIJO}" \
  --https-only true

az functionapp config set -g "$RG" -n "$FUNCTION_NAME" --min-tls-version 1.2 --http20-enabled true -o none
az functionapp cors add -g "$RG" -n "$FUNCTION_NAME" --allowed-origins '*' -o none
az functionapp cors credentials -g "$RG" -n "$FUNCTION_NAME" --enable false -o none
az resource update --ids "/subscriptions/$SUB/resourceGroups/$RG/providers/Microsoft.Web/sites/$FUNCTION_NAME" \
  --set properties.keyVaultReferenceIdentity="$MI_ID" -o none
# el create deja un AzureWebJobsStorage con connection string: se reemplaza por identidad (bloque de abajo)
az functionapp config appsettings delete -g "$RG" -n "$FUNCTION_NAME" --setting-names AzureWebJobsStorage -o none
```

Ajustes posteriores con comandos dedicados (sirven para los dos caminos): `az functionapp scale config set --instance-memory --maximum-instance-count` y `az functionapp deployment config set --deployment-storage-name --deployment-storage-container-name --deployment-storage-auth-type --deployment-storage-auth-value`. `list-flexconsumption-runtimes` informa la versión como `21.0`; el Bicep usa `21` y ARM acepta los dos.

Riesgo del camino B (anticipado, no probado): con Shared Key deshabilitado en el storage host, el create puede fallar al intentar leer las claves de la cuenta para armar `AzureWebJobsStorage`. Si pasa, usar el camino A.

### App settings (los dos caminos)

`az functionapp config appsettings set` **agrega o reemplaza** los nombrados y deja los demás; no es igual al Bicep, que reemplaza todo.

```bash
APPI_CONN=$(az resource show -g "$RG" -n "${PREFIJO}-appi-${SUFIJO}" --resource-type Microsoft.Insights/components --query properties.ConnectionString -o tsv)

az functionapp config appsettings set -g "$RG" -n "$FUNCTION_NAME" -o none --settings \
  "AzureWebJobsStorage__accountName=$STORAGE_HOST" \
  "AzureWebJobsStorage__credential=managedidentity" \
  "AzureWebJobsStorage__clientId=$MI_CLIENT_ID" \
  "APPLICATIONINSIGHTS_CONNECTION_STRING=$APPI_CONN" \
  "APPLICATIONINSIGHTS_AUTHENTICATION_STRING=ClientId=$MI_CLIENT_ID;Authorization=AAD" \
  "AZURE_CLIENT_ID=$MI_CLIENT_ID" \
  "ADJUNTOS_STORAGE_ACCOUNT=$STORAGE_ADJ" \
  "ADJUNTOS_BLOB_ENDPOINT=https://$STORAGE_ADJ.blob.core.windows.net/" \
  "ADJUNTOS_CONTAINER=cmv8-ingesta" \
  "DATAVERSE_URL=$DATAVERSE_URL" \
  "SAS_UPLOAD_MINUTOS=15" \
  "SAS_READ_MINUTOS=5" \
  "COLA_INGESTA=ingesta-pendiente" \
  "AUTORIZACION_MODO=dataverse" \
  "SAS_LIGADA_USUARIO=true" \
  "ESCANEO_REQUERIDO=false" \
  "ESTANCADA_MINUTOS=15" \
  "SAS_BLOQUE_MB=4" \
  "BROKER_CLIENT_ID=$BROKER_APP_ID" \
  "BROKER_TENANT_ID=$TENANT_ID"

# IBM i (solo si se configura)
az functionapp config appsettings set -g "$RG" -n "$FUNCTION_NAME" -o none --settings \
  "AS400_HOST=$AS400_HOST" "AS400_USER=$AS400_USER" "AS400_SECURE=true" \
  "AS400_SQL_CLIENTE=$AS400_SQL" \
  "AS400_PASSWORD=@Microsoft.KeyVault(VaultName=$KV_NAME;SecretName=as400-password)"

# Content Manager (solo si se configura)
az functionapp config appsettings set -g "$RG" -n "$FUNCTION_NAME" -o none --settings \
  "CMIS_URL=$CMIS_URL" "CMIS_USER=$CMIS_USER" "CMIS_REPOSITORY_ID=$CMIS_REPO" \
  "CMIS_FOLDER_PATH=$CMIS_CARPETA" "CMIS_TYPE_ID=$CMIS_TIPO" \
  "CMIS_PASSWORD=@Microsoft.KeyVault(VaultName=$KV_NAME;SecretName=cmis-password)"
```

`AS400_SQL` lleva espacios y comillas: definirlo en el cuaderno entre comillas simples. Si alguna vez se despliega el Bicep sobre un sitio armado así, el Bicep impone su lista y elimina lo que no esté en él.

## Con el portal

El portal no permite elegir el contenedor de despliegue al crear una app Flex (lo dice la documentación de Flex Consumption): se crea y se corrige después.

1. **Create a resource** → **Function App** → **Create**. En **Select a hosting option** elegir **Flex Consumption** → **Select**.
2. Pestaña **Basics**:
   - **Subscription**, **Resource Group**: los del ambiente.
   - **Function App name**: `cmv8-func-<sufijo>`.
   - **Region**: la del ambiente (solo aparecen las que soportan Flex).
   - **Runtime stack**: **Java**. **Version**: **21**.
   - **Instance size**: **2048 MB** (verificar el default; CAMBIAR si propone otro).
3. Pestaña **Storage**: **Storage account** = el storage host existente `cmv8host<sufijo>` (CAMBIAR: el default crea uno nuevo).
4. Pestaña **Networking**: **Enable public access** = **On** (fuera del Banco); **Enable network injection** = **Off** hasta que exista la VNet de S0.
5. Pestaña **Monitoring**: **Enable Application Insights** = **Yes**, elegir el existente `cmv8-appi-<sufijo>` (CAMBIAR: el default crea uno nuevo). Ese recurso tiene **DisableLocalAuth** en `true`; la telemetría necesita `APPLICATIONINSIGHTS_AUTHENTICATION_STRING` (paso 9).
6. Pestaña **Authentication**: **Authentication type** = **Managed identity** para todos los recursos (CAMBIAR). Si ofrece elegir una identidad existente, seleccionar `cmv8-mi-<sufijo>`; si solo crea una nueva, aceptar y corregir en el paso 8 (verificar en el portal).
7. **Review + create** → **Create**.
8. En la Function creada:
   - **Settings** → **Identity** → pestaña **User assigned** → **Add** → `cmv8-mi-<sufijo>`. Si el portal creó otra identidad, quitarla después de terminar el paso 9.
   - **Settings** → **Deployment settings**: **Application package location** = storage host + contenedor `app-package-<sufijo>`; **Storage authentication** = **User assigned identity** → `cmv8-mi-<sufijo>` → **Save**.
   - **Settings** → **Scale and concurrency**: **Instance memory** 2048; **Maximum instance count** 100 → **Save** (verificar el nombre exacto del campo de máximo en el portal).
   - **Settings** → **Configuration** → **General settings**: **HTTPS Only** = On, **Minimum Inbound TLS Version** = 1.2, **HTTP version** = 2.0 → **Save** (verificar en el portal: en Flex algunas opciones de esta pestaña cambian de lugar).
   - **API** → **CORS**: agregar `*`; **Enable Access-Control-Allow-Credentials** desmarcado → **Save**.
9. **Settings** → **Environment variables** → **App settings**: cargar cada setting de la tabla de "Con Bicep" con **+ Add** (o **Advanced edit** pegando el JSON) → **Apply** → **Confirm**. Borrar `AzureWebJobsStorage` si el asistente lo creó con connection string.
10. `keyVaultReferenceIdentity`: no está documentado un control en el portal (verificar en el portal); fijarlo con el `az resource update` del camino B.

## Verificación

```bash
BASE="https://management.azure.com/subscriptions/$SUB/resourceGroups/$RG/providers/Microsoft.Web"

# sitio: estado, HTTPS, identidad de Key Vault, paquete, escala, runtime
az functionapp show -g "$RG" -n "$FUNCTION_NAME" --query 'properties.{estado:state, https:httpsOnly, kvIdentidad:keyVaultReferenceIdentity, host:defaultHostName}'
az functionapp deployment config show -g "$RG" -n "$FUNCTION_NAME"
az functionapp scale config show -g "$RG" -n "$FUNCTION_NAME"
# esperado: Running, true, resource id de la identidad; blobContainer con UserAssignedIdentity; 100 / 2048; java 21

# configuración del sitio
az functionapp config show -g "$RG" -n "$FUNCTION_NAME" --query '{tls:minTlsVersion, http2:http20Enabled, cors:cors}'
# esperado: "1.2", true, allowedOrigins ["*"], supportCredentials false

# plan
az rest --method GET --url "$BASE/serverfarms/$PLAN_NAME?api-version=2024-04-01" --query '{sku:sku.name, tier:sku.tier, linux:properties.reserved}'
# esperado: FC1, FlexConsumption, true

# lista de settings (solo nombres; los valores no se imprimen)
az functionapp config appsettings list -g "$RG" -n "$FUNCTION_NAME" --query '[].name' -o tsv | sort

# referencias de Key Vault resueltas (guía 09)
az rest --method GET --url "$BASE/sites/$FUNCTION_NAME/config/configreferences/appsettings?api-version=2023-12-01" \
  --query 'value[].{setting:name, estado:properties.status}' -o table
```

En az CLI 2.91, `az functionapp show` devuelve las propiedades del sitio **anidadas bajo `properties`** (claves de primer nivel: `id`, `identity`, `kind`, `location`, `name`, `properties`, `resourceGroup`, `type`). `--query state` devuelve vacío; hay que usar `--query properties.state`. Lo mismo vale para `httpsOnly`, `defaultHostName` y `keyVaultReferenceIdentity`. `az appservice plan show` no devuelve `reserved` en 2.91: se lee por ARM como arriba.

Estado real del sandbox (`cmv8-func-supfcopn`, 2026-10-14): `Running`, HTTPS only, `keyVaultReferenceIdentity` = `cmv8-mi-supfcopn`, paquete en `https://cmv8hostsupfcopn.blob.core.windows.net/app-package-supfcopn` con `UserAssignedIdentity`, 100 instancias de 2048 MB, Java 21, TLS 1.2, HTTP/2, CORS `*` sin credenciales, `siteUpdateStrategy: Recreate` (default de la plataforma, no lo fija el Bicep), plan FC1 `reserved: true`, 25 app settings (sin bloque CMIS porque el spike no lo configura).

Después de publicar el código (guía 16): `az functionapp function list -g "$RG" -n "$FUNCTION_NAME" --query '[].name' -o tsv` debe listar, entre otras, `OnBlobCreated`, `OnScanResult`, `ProcesarIngesta` y `Watchdog`.

## Errores que ya vimos

| Síntoma | Causa | Arreglo |
| --- | --- | --- |
| `az functionapp show --query state` devuelve vacío | az 2.91 anida las propiedades bajo `properties` | `--query properties.state` |
| Setting agregado a mano desaparece | El siguiente despliegue del Bicep reemplazó todos los app settings (`config/appsettings`) | Agregarlo al Bicep o al `.bicepparam` |
| La Function no arranca; en logs, 403 contra el storage host (anticipado) | Faltan los roles de la identidad sobre el storage host (Blob Data Owner, Queue y Table Data Contributor) o no propagaron | Guías 03 a 08; esperar hasta 10 minutos y reiniciar |
| Sin telemetría en App Insights (anticipado) | Falta `APPLICATIONINSIGHTS_AUTHENTICATION_STRING` o el rol Monitoring Metrics Publisher; App Insights rechaza la clave de instrumentación por `DisableLocalAuth` | Agregar el setting y el rol |
| `az functionapp create` falla con "Key based authentication is not permitted" (anticipado) | Storage host con Shared Key deshabilitado | Camino A (ARM) |
| `func azure functionapp publish` falla o deja la app vacía | En Flex se publica con el plugin de Maven | `mvn clean package azure-functions:deploy` (guía 16) |
| 502 o 500 del broker al pedir SAS con `AS400_PASSWORD` vacío (anticipado) | Referencia de Key Vault no resuelta | Guía 09, "Verificación" |
| Region no aparece en el portal o el create falla por runtime (anticipado) | La región no soporta Flex o no tiene Java 21 | `az functionapp list-flexconsumption-runtimes --location "$REGION" --runtime java` |
