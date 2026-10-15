# 05. Observabilidad: Log Analytics y Application Insights

> Rol que lo ejecuta: Cloud. Depende de: 03, 04. Lo necesitan: 07 (diagnósticos del blob service de adjuntos), 08 (rol Monitoring Metrics Publisher) y 10 (Function App, `APPLICATIONINSIGHTS_CONNECTION_STRING`). Costo: de USD 0 a 15 por mes aprox. (primeros 5 GB de ingesta por mes sin cargo, después alrededor de USD 2,30 por GB; ver `docs/costos-estimados.md`).

## Qué es y qué hace en esta solución

Dos recursos que trabajan juntos:

- **Log Analytics** (`cmv8-log-<sufijo>`, SKU `PerGB2018`, retención 30 días): el almacén donde caen los registros. Acá llegan los `StorageBlobLogs` de la cuenta de adjuntos (cada `PutBlock`, `PutBlockList`, `GetBlob` y `DeleteBlob` con `AuthenticationType`, `RequesterUpn` del usuario delegado y la URI con `scid` = solicitudId) y las tablas `App*` de Application Insights.
- **Application Insights** (`cmv8-appi-<sufijo>`, basado en workspace, `DisableLocalAuth = true`): recibe la telemetría de la Function (trazas "SAS de subida emitida", "OBO del broker con la identidad administrada", excepciones del worker CMIS, dependencias hacia Dataverse, AS400 y Content Manager). Con la autenticación local apagada, solo acepta telemetría autenticada con Entra; por eso la identidad de la guía 04 necesita **Monitoring Metrics Publisher** sobre este recurso.

Con eso se responde "quién subió qué, cuándo y con qué autorización" (auditoría) y "por qué falló el OBO / Easy Auth / el archivado" (diagnóstico).

**Dónde están los diagnósticos del blob service de adjuntos**: en la guía 07, sección "Diagnósticos", porque se configuran sobre la cuenta de adjuntos. Esta guía crea el workspace que esa sección usa como destino (`LOG_ID`).

## ¿Imprescindible?

**Veredicto: Prescindible por decisión** (técnicamente) / **Imprescindible operativamente**

- Qué aporta: la única evidencia de auditoría de la carga (StorageBlobLogs con `RequesterUpn`, `AuthenticationType = DelegationSas` y `scid`) y el único lugar donde se ve por qué falló un OBO, una validación de Easy Auth o un archivado en Content Manager.
- Qué pasa si no está: la Function arranca y funciona igual (sin `APPLICATIONINSIGHTS_CONNECTION_STRING` simplemente no envía telemetría). Pero no hay forma de demostrar quién subió un archivo, la humo del guía 22 no se puede cerrar (pide `StorageBlobLogs` con el UPN del piloto) y cada incidente se diagnostica a ciegas.
- Qué decisión lo haría distinto: **el Banco ya tiene un workspace central de Log Analytics** (lo habitual en banca, conectado a su SIEM). En ese caso **no se crea** `cmv8-log-<sufijo>`: Application Insights y el diagnóstico del blob service apuntan al workspace del Banco. Hoy `main.bicep` siempre crea el suyo; reutilizar uno existente requiere agregar un parámetro (por ejemplo `logAnalyticsIdExistente`) con un `existing` condicional. Si se monta a mano, basta con poner el resource id del workspace del Banco en `LOG_ID` y saltear el paso 1 de la sección CLI.
- Alternativa y su tradeoff: enviar los diagnósticos a una cuenta de Storage de archivo o a un Event Hub del SIEM. Más barato para retención larga, pero sin consultas KQL inmediatas ni alertas de logs; para la operación diaria se pierde el diagnóstico rápido.

## Valores del cuaderno

| Variable | Produce / consume | Cómo se obtiene |
| --- | --- | --- |
| `RG`, `REGION`, `PREFIJO`, `SUFIJO` | Consume | Guía 03 |
| `MI_PRINCIPAL_ID` | Consume | Guía 04 |
| `LOG_NAME` | Produce | `${PREFIJO}-log-${SUFIJO}` (o el workspace central del Banco) |
| `LOG_ID` | Produce | `az monitor log-analytics workspace show -g "$RG" -n "$LOG_NAME" --query id -o tsv` |
| `APPI_NAME` | Produce | `${PREFIJO}-appi-${SUFIJO}` |
| `APPI_ID` | Produce | `az resource show -g "$RG" -n "$APPI_NAME" --resource-type Microsoft.Insights/components --query id -o tsv` |
| `APPI_CONN` | Produce | Cadena de conexión (no es secreta con `DisableLocalAuth = true`: sin token Entra no se puede ingerir). La consume la Function en `APPLICATIONINSIGHTS_CONNECTION_STRING` |

```bash
LOG_NAME=        # cmv8-log-<sufijo> o el workspace central del Banco
LOG_ID=          # /subscriptions/.../workspaces/<nombre>
APPI_NAME=       # cmv8-appi-<sufijo>
APPI_ID=         # /subscriptions/.../components/cmv8-appi-<sufijo>
```

## Con Bicep

- `logAnalytics` (`Microsoft.OperationalInsights/workspaces@2023-09-01`), líneas 153-160: `retentionInDays: 30`, `sku.name: 'PerGB2018'`.
- `appInsights` (`Microsoft.Insights/components@2020-02-02`), líneas 162-171: `kind: 'web'`, `Application_Type: 'web'`, `WorkspaceResourceId: logAnalytics.id`, `DisableLocalAuth: true`.
- `rolAppInsights`, líneas 363-371: Monitoring Metrics Publisher (`3913510d-42f4-4e42-8a64-420c390055eb`) a la identidad, alcance el componente de App Insights.
- `diagAdjuntos`, líneas 292-306: diagnóstico del blob service de adjuntos hacia este workspace (detalle en la guía 07).
- App settings de la Function, líneas 493-494: `APPLICATIONINSIGHTS_CONNECTION_STRING` y `APPLICATIONINSIGHTS_AUTHENTICATION_STRING = ClientId=<client id>;Authorization=AAD`.
- Parámetros: ninguno propio. Nombres por `nombreLog` y `nombreAppi` (líneas 115-116). Para el Banco: si hay workspace central, hace falta el parámetro nuevo descripto arriba (hoy no existe).

## Con Azure CLI

```bash
source ~/cmv8-$AMBIENTE.env
export PYTHONWARNINGS=ignore
LOG_NAME="${PREFIJO}-log-${SUFIJO}"
APPI_NAME="${PREFIJO}-appi-${SUFIJO}"

# 1. Log Analytics (saltear si se reutiliza el workspace central del Banco y poner su id en LOG_ID)
az monitor log-analytics workspace create --resource-group "$RG" --workspace-name "$LOG_NAME" \
  --location "$REGION" --sku PerGB2018 --retention-time 30 -o none
LOG_ID=$(az monitor log-analytics workspace show -g "$RG" -n "$LOG_NAME" --query id -o tsv)

# 2. Application Insights basado en workspace, con autenticación local apagada.
#    Se usa az resource create porque 'az monitor app-insights' requiere una extensión
#    (application-insights) que puede no estar instalada ni ser instalable en una estación sin internet.
az resource create --resource-group "$RG" --name "$APPI_NAME" \
  --resource-type Microsoft.Insights/components --api-version 2020-02-02 --is-full-object \
  --properties "{
    \"location\": \"$REGION\",
    \"kind\": \"web\",
    \"properties\": {
      \"Application_Type\": \"web\",
      \"WorkspaceResourceId\": \"$LOG_ID\",
      \"DisableLocalAuth\": true
    }
  }" -o none
APPI_ID=$(az resource show -g "$RG" -n "$APPI_NAME" --resource-type Microsoft.Insights/components --query id -o tsv)
APPI_CONN=$(az resource show --ids "$APPI_ID" --query properties.ConnectionString -o tsv)

# 3. La identidad puede publicar telemetría con Entra (sin esto, con DisableLocalAuth no llega nada)
az role assignment create --assignee-object-id "$MI_PRINCIPAL_ID" --assignee-principal-type ServicePrincipal \
  --role 3913510d-42f4-4e42-8a64-420c390055eb --scope "$APPI_ID" -o none
```

Variante con la extensión, si está disponible: `az monitor app-insights component create --app "$APPI_NAME" -g "$RG" --location "$REGION" --kind web --application-type web --workspace "$LOG_ID"`. Ese comando **no tiene opción para `DisableLocalAuth`**; después hay que correr `az resource update --ids "$APPI_ID" --set properties.DisableLocalAuth=true`.

Valores que la Function necesita (los fija la guía 10, no esta):

```bash
echo "APPLICATIONINSIGHTS_CONNECTION_STRING=$APPI_CONN"
echo "APPLICATIONINSIGHTS_AUTHENTICATION_STRING=ClientId=$MI_CLIENT_ID;Authorization=AAD"
```

## Con el portal

Log Analytics:

1. Buscar **Log Analytics workspaces** → **Create**.
2. **Basics**: **Subscription**, **Resource group** = `rg-cmv8-<ambiente>`, **Name** = `cmv8-log-<sufijo>`, **Region** = la del grupo.
3. **Review + Create** → **Create**. El portal crea el workspace con el plan por uso (`PerGB2018`) y 30 días de retención por defecto, que es lo que pide el Bicep.
4. Confirmar la retención: el workspace → **Settings** → **Usage and estimated costs** → **Data Retention** → 30 días → **OK**.

Application Insights:

5. **Create a resource** → categoría **Monitoring & Diagnostics** → **Application Insights** → **Create**.
6. **Basics**: **Resource group** = `rg-cmv8-<ambiente>`, **Name** = `cmv8-appi-<sufijo>`; **Region** = la del grupo; **Log Analytics Workspace** = `cmv8-log-<sufijo>` (o el central del Banco). **Cambiar respecto del default**: si no se elige un workspace, el portal crea uno nuevo automáticamente con otro nombre.
7. **Review + create** → **Create**.
8. **Apagar la autenticación local (no está en el asistente de creación)**: el recurso → **Configure** → **Properties** → **Local Authentication** muestra **Enabled (click to change)** → elegir **Disabled** → aplicar. En **Overview** pasa a verse **Disabled**.
9. Rol para la identidad: el recurso de App Insights → **Access control (IAM)** → **Add** → **Add role assignment** → rol **Monitoring Metrics Publisher** → **Next** → **Assign access to** = **Managed identity** → **+ Select members** → **Managed identity** = **User-assigned managed identity** → `cmv8-mi-<sufijo>` → **Select** → **Review + assign**.
10. Copiar la **Connection String** desde **Overview** del recurso (va a `APPLICATIONINSIGHTS_CONNECTION_STRING`).

## Verificación

```bash
az monitor log-analytics workspace show -g "$RG" -n "$LOG_NAME" --query '{sku:sku.name, retencion:retentionInDays}' -o json
# {"retencion": 30, "sku": "PerGB2018"}

az resource show --ids "$APPI_ID" \
  --query '{kind:kind, tipo:properties.Application_Type, ws:properties.WorkspaceResourceId, sinLocalAuth:properties.DisableLocalAuth, ingesta:properties.IngestionMode}' -o json
# kind "web", tipo "web", ws = $LOG_ID, sinLocalAuth true, ingesta "LogAnalytics"

az role assignment list --scope "$APPI_ID" --assignee-object-id "$MI_PRINCIPAL_ID" --query '[].roleDefinitionName' -o tsv
# Monitoring Metrics Publisher
```

Después de publicar la Function (guía 16), en el workspace → **Logs**:

```kusto
AppTraces
| where TimeGenerated > ago(30m)
| project TimeGenerated, Message, SeverityLevel
| take 20
```

Debe haber trazas del host. Si hay requests pero `AppTraces` vacía, revisar `APPLICATIONINSIGHTS_AUTHENTICATION_STRING` y el rol.

En el portal: App Insights → **Overview** (workspace asociado y **Local Authentication: Disabled**); **Access control (IAM)** → **Role assignments**.

## Errores que ya vimos

| Síntoma | Causa | Arreglo |
| --- | --- | --- |
| `az monitor app-insights component …` falla instalando la extensión (`Pip failed with status code 1`) | Sin extensión `application-insights` y sin salida a PyPI desde la estación | Usar `az resource create` / `az resource show` como en esta guía |
| App Insights muestra retención 90 días aunque el workspace tiene 30 | Las tablas `App*` de Application Insights tienen 90 días incluidos sin cargo, independientes de la retención por defecto del workspace | Nada que arreglar; es comportamiento del producto |
| Aparece un grupo de acciones "Application Insights Smart Detection" en el grupo de recursos que nadie creó | Lo crea Application Insights automáticamente | Dejarlo o borrarlo; no lo gestiona el Bicep |
| La Function corre pero no hay telemetría (anticipado) | `DisableLocalAuth = true` sin Monitoring Metrics Publisher, o `APPLICATIONINSIGHTS_AUTHENTICATION_STRING` ausente o con el principal id | Rol del paso 3; el valor es `ClientId=<MI_CLIENT_ID>;Authorization=AAD` |
| Se crearon dos workspaces (anticipado) | En el portal se creó App Insights sin elegir workspace | Cambiar el workspace en App Insights → **Properties** → **Change workspace** y borrar el sobrante |
| Ingesta bloqueada por la red del Banco (anticipado) | Workspace central con ingesta pública deshabilitada y la Function sin VNet | Decisión S0 (Private Link de Azure Monitor); hasta entonces, workspace propio con ingesta pública |
