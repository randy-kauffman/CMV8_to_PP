# 12. Defender for Storage (escaneo de malware al subir)

> Rol que lo ejecuta: Cloud, con aprobación de Seguridad y de Finanzas. Depende de: 03 a 08 (cuenta de adjuntos), 10 (Function App), 16 (código publicado: `OnScanResult` tiene que existir para la suscripción). Lo necesitan: nada en el montaje vigente. **OPCIONAL y NO habilitado** por decisión del usuario (2026-10-14). Costo: ≈ 9,78 USD/mes fijos por cuenta protegida + 0,15 USD por GB escaneado; ≈ 20 USD/mes en el escenario chico (66 GB/mes) y ≈ 109 USD/mes en el mediano (660 GB/mes) (`docs/justificacion-financiera-blob-vs-dataverse.md` §8).

## Qué es y qué hace en esta solución

Microsoft Defender for Storage con **escaneo de malware al subir** analiza cada blob que se escribe en la cuenta de adjuntos, antes de que el worker lo lea. El resultado se publica de dos formas: como etiqueta de índice del blob (`blobScanResultsOptions: BlobIndexTags`) y como evento `Microsoft.Security.MalwareScanningResult` en un **tópico personalizado** de Event Grid (`cmv8-egt-scan-<sufijo>`). Una suscripción de ese tópico (`a-function-onscanresult`) entrega el evento a la función `OnScanResult`.

Con Defender, el disparador del worker **cambia**:

| | Sin Defender (vigente) | Con Defender |
| --- | --- | --- |
| Quién publica | Storage: `Microsoft.Storage.BlobCreated` | Defender: `Microsoft.Security.MalwareScanningResult` |
| Recurso de Event Grid | System topic `cmv8-egst-adj-<sufijo>` (guía 11) | Tópico personalizado `cmv8-egt-scan-<sufijo>` |
| Función | `OnBlobCreated` (trata el blob como limpio) | `OnScanResult` (lee `scanResultType` y `malwareNamesFound`) |
| App setting | `ESCANEO_REQUERIDO=false` | `ESCANEO_REQUERIDO=true` |
| Si hay malware | No se detecta: entra a Content Manager | La solicitud pasa a Rechazado y el blob no se ingesta |

`OnScanResult` (`EventosEventGrid.java`) valida el tipo de evento, toma `data.blobUri`, interpreta `data.scanResultType` y, si hay nombres de malware, los registra como detalle; si el resultado es limpio, encola la ingesta en `%COLA_INGESTA%`. El Bicep hace excluyentes los dos disparadores: con `habilitarDefender=true` no crea el system topic de BlobCreated.

## ¿Imprescindible?

**Veredicto: Prescindible por decisión**

- Qué aporta: escaneo de malware en Azure **antes** de archivar en Content Manager, con resultado auditable (etiqueta en el blob y evento), sin poner un antivirus en el camino del código. Es el control que ADR-002 previó ("el worker no sube nada a Content Manager sin resultado limpio").
- Qué pasa si no está: el blob entra a Content Manager **sin escaneo** en Azure. El control queda en el antivirus del Banco aguas abajo (el de Content Manager o el de los equipos que abren el documento) y en el endpoint del usuario que subió el archivo. La solución funciona igual; el riesgo aceptado es archivar un archivo malicioso.
- Qué decisión lo haría prescindible (o distinto): ya es prescindible: el usuario decidió no habilitarlo y no correr el spike S3 en el entorno propio para no pagar el costo fijo; queda cuantificado en el impacto financiero. Cambiaría si Seguridad del Banco exige escaneo antes de archivar, o si el Banco ya tiene Defender for Storage a nivel de suscripción (el cargo fijo por cuenta ya estaría pagado y solo sumaría el escaneo por GB).
- Alternativa y su tradeoff: **confiar en el antivirus del Banco aguas abajo** (vigente): costo cero en Azure, pero el archivo ya está archivado cuando se detecta y la remediación es en Content Manager. Otra alternativa es un antivirus propio en la Function (por ejemplo, un motor de escaneo en un contenedor aparte): más control y sin costo por GB, pero hay que operar y actualizar firmas, y suma latencia y memoria al worker. Se prefirió no construirlo.

## Valores del cuaderno

| Variable | Produce / consume | Cómo se obtiene |
| --- | --- | --- |
| `RG`, `REGION` | Consume | Cuaderno |
| `SUB` | Produce (local) | `az account show --query id -o tsv` |
| `PREFIJO`, `SUFIJO` | Consume | Convención de nombres (guía 09) |
| `STORAGE_ADJ`, `STORAGE_ADJ_ID` | Consume | Guía 11 |
| `FUNCTION_NAME`, `FUNCTION_ID` | Consume | Guías 10 y 11 |
| `EG_SCAN_TOPIC` | Produce | `${PREFIJO}-egt-scan-${SUFIJO}` (salida `topicoScanName` del Bicep) |
| `EG_SCAN_TOPIC_ID` | Produce (local) | `az eventgrid topic show -g "$RG" -n "$EG_SCAN_TOPIC" --query id -o tsv` |
| `CAP_GB_MES` | Consume | Tope de GB escaneados por mes: `100` en el Bicep |

## Con Bicep

Está en `infra/main.bicep`, sección "Opcional: Defender for Storage + tópico de resultados de escaneo (S3)": tópico `topicoScan` (líneas 518 a 527, `Microsoft.EventGrid/topics@2025-02-15`), `defender` (líneas 529 a 546, `Microsoft.Security/defenderForStorageSettings@2025-07-01-preview`, alcance la cuenta de adjuntos) y `suscripcionScan` (líneas 548 a 566). El app setting `ESCANEO_REQUERIDO` sale de `habilitarDefender` (línea 506).

| Parámetro | Qué gobierna | Valor recomendado en el Banco |
| --- | --- | --- |
| `habilitarDefender` | Crea tópico y configuración de Defender; pone `ESCANEO_REQUERIDO=true`; deja de crear el system topic de BlobCreated | `false` (decisión vigente). `true` solo con aprobación de Seguridad y Finanzas |
| `crearSuscripcionEventGrid` | Con `habilitarDefender=true`, crea `a-function-onscanresult` | `true` en un despliegue posterior a la guía 16 |

Fijado sin parámetro:

- Tópico: identidad `SystemAssigned`, `inputSchema: EventGridSchema`, `publicNetworkAccess: Enabled` (Defender no publica en tópicos solo privados), `disableLocalAuth: false`.
- Defender: `isEnabled: true`, `overrideSubscriptionLevelSettings: true` (la cuenta no hereda la configuración de la suscripción), `malwareScanning.onUpload.isEnabled: true`, `capGBPerMonth: 100`, `scanResultsEventGridTopicResourceId` = el tópico, `blobScanResultsOptions: BlobIndexTags`, `automatedResponse: None` (no borra ni mueve blobs maliciosos: la decisión la toma el worker), `sensitiveDataDiscovery.isEnabled: false`.
- Suscripción: `EventGridSchema`, destino `AzureFunction` `…/functions/OnScanResult`, lote de 1 evento y 64 KB, `retryPolicy` de 10 intentos y 1.440 minutos. Sin dead-letter (mismo comentario que la guía 11).

El tope de 100 GB/mes acota el costo variable a ≈ 15 USD/mes; por encima del tope los blobs **no se escanean** y el worker no recibiría evento para ellos (quedarían en Subido hasta el Watchdog, que hoy no distingue "sin escaneo"). Dimensionar el tope con el volumen real: 66 GB/mes en el escenario chico entra; 660 GB/mes en el mediano no.

Activación en el Banco, cuando se apruebe:

1. Si ya se había creado el system topic de BlobCreated (guía 11), **borrarlo primero** (`az eventgrid system-topic delete ... --yes`): el despliegue incremental del Bicep no lo elimina, y Defender crea su propio system topic sobre la cuenta para escuchar las subidas (una cuenta admite uno solo).
2. `habilitarDefender = true` y `crearSuscripcionEventGrid = false`, desplegar.
3. `crearSuscripcionEventGrid = true`, desplegar.
4. Probar con EICAR (S3 de la guía de spikes) y medir la demora entre `PutBlockList` y el evento (criterio: menos de 5 minutos para 100 MB).

## Con Azure CLI

Verificado con `az --help` (az CLI 2.91). No hay comando de az CLI para la configuración de Defender **por cuenta**; se usa `az rest` sobre el mismo recurso y la misma API del Bicep. Nada de esto se ejecutó en el sandbox.

### 1. Tópico de resultados

```bash
source ~/cmv8-$AMBIENTE.env
SUB=$(az account show --query id -o tsv)
EG_SCAN_TOPIC="${PREFIJO}-egt-scan-${SUFIJO}"

az eventgrid topic create \
  --resource-group "$RG" \
  --name "$EG_SCAN_TOPIC" \
  --location "$REGION" \
  --input-schema eventgridschema \
  --public-network-access enabled \
  --mi-system-assigned \
  -o none
EG_SCAN_TOPIC_ID=$(az eventgrid topic show -g "$RG" -n "$EG_SCAN_TOPIC" --query id -o tsv)
```

El tópico tiene que estar en la **misma región** que la cuenta de adjuntos y aceptar tráfico público. `disableLocalAuth` queda en `false` por defecto.

### 2. Defender en la cuenta de adjuntos

```bash
STORAGE_ADJ_ID=$(az storage account show -g "$RG" -n "$STORAGE_ADJ" --query id -o tsv)

az rest --method PUT \
  --url "https://management.azure.com$STORAGE_ADJ_ID/providers/Microsoft.Security/defenderForStorageSettings/current?api-version=2025-07-01-preview" \
  --body "{\"properties\":{
    \"isEnabled\": true,
    \"overrideSubscriptionLevelSettings\": true,
    \"malwareScanning\": {
      \"onUpload\": { \"isEnabled\": true, \"capGBPerMonth\": 100 },
      \"scanResultsEventGridTopicResourceId\": \"$EG_SCAN_TOPIC_ID\",
      \"blobScanResultsOptions\": \"BlobIndexTags\",
      \"automatedResponse\": \"None\"
    },
    \"sensitiveDataDiscovery\": { \"isEnabled\": false }
  }}" -o none
```

Alternativa a nivel de **suscripción** (protege todas las cuentas, incluida la del host): `az security pricing create -n StorageAccounts --tier standard --subplan DefenderForStorageV2`. No se recomienda para esta solución: suma el cargo fijo por cada cuenta de la suscripción. Si el Banco ya lo tiene así, el paso 2 igual hace falta para fijar el tópico y el tope en esta cuenta (`overrideSubscriptionLevelSettings: true`).

### 3. Suscripción hacia OnScanResult (después de la guía 16)

```bash
FUNCTION_ID=$(az functionapp show -g "$RG" -n "$FUNCTION_NAME" --query id -o tsv)

az eventgrid topic event-subscription create \
  --resource-group "$RG" \
  --topic-name "$EG_SCAN_TOPIC" \
  --name a-function-onscanresult \
  --endpoint-type azurefunction \
  --endpoint "$FUNCTION_ID/functions/OnScanResult" \
  --event-delivery-schema eventgridschema \
  --max-events-per-batch 1 \
  --pref-batch-size-kb 64 \
  --max-delivery-attempts 10 \
  --event-ttl 1440 \
  -o none
```

### 4. Cambiar el disparador en la Function

```bash
az functionapp config appsettings set -g "$RG" -n "$FUNCTION_NAME" --settings ESCANEO_REQUERIDO=true -o none
# si existía la suscripción de BlobCreated, quitarla para no ingestar sin escaneo
az eventgrid system-topic event-subscription delete -g "$RG" --system-topic-name "${PREFIJO}-egst-adj-${SUFIJO}" -n a-function-onblobcreated --yes
az eventgrid system-topic delete -g "$RG" -n "${PREFIJO}-egst-adj-${SUFIJO}" --yes
```

El borrado del system topic va ANTES del paso 2 si se activa Defender sobre una cuenta que ya tenía la guía 11 aplicada (ver "Con Bicep", activación).

## Con el portal

1. Tópico: **Create a resource** → **Event Grid Topic** → **Create**.
   - **Basics**: grupo, **Name** `cmv8-egt-scan-<sufijo>`, **Region** = la de la cuenta de adjuntos.
   - **Networking**: **Public access** (default) — Defender no publica en tópicos con solo private endpoint.
   - **Security** / **Identity**: **System assigned** = On (verificar en el portal la pestaña exacta).
   - **Advanced**: **Event Schema** = **Event Grid Schema** (default).
   - **Review + create** → **Create**.
2. Defender en la cuenta: ir a `cmv8adj<sufijo>` → **Security + networking** → **Microsoft Defender for Cloud** → **Settings**:
   - **Override Defender for Storage subscription-level settings** = **On** (CAMBIAR).
   - **On-upload malware scanning** = **On**.
   - **Set limit of GB scanned per month** = **100** (CAMBIAR: el default es 10.000 GB).
   - **Sensitive data threat detection** = **Off** (CAMBIAR: viene activado).
   - Envío de resultados a Event Grid: elegir el tópico `cmv8-egt-scan-<sufijo>` (el portal solo lista tópicos de la misma suscripción).
   - Guardar resultados como **blob index tags**: activado (verificar en el portal el nombre exacto de la opción).
   - Respuesta automática (borrado temporal de blobs maliciosos): **desactivada**.
   - **Save**. Si la cuenta no tenía Defender, el botón es **Enable on storage account**.
3. Suscripción: en el tópico → **+ Event Subscription**:
   - **Name** `a-function-onscanresult`, **Event Schema** **Event Grid Schema**.
   - **Endpoint Type** **Azure Function** → Function App `cmv8-func-<sufijo>` → función **OnScanResult** → **Confirm Selection**.
   - **Additional Features**: **Max delivery attempts** **10** (CAMBIAR: default 30), **Event time to live** 1 día; lote de 1 evento y 64 KB (verificar en el portal la ubicación de los campos de lote).
   - **Create**.
4. Function → **Settings** → **Environment variables** → `ESCANEO_REQUERIDO` = `true` → **Apply**. Mejor hacerlo con el Bicep para que no se pierda en el próximo despliegue.

## Verificación

```bash
# configuración de Defender en la cuenta
az rest --method GET \
  --url "https://management.azure.com$STORAGE_ADJ_ID/providers/Microsoft.Security/defenderForStorageSettings/current?api-version=2025-07-01-preview" \
  --query 'properties.{activo:isEnabled, override:overrideSubscriptionLevelSettings, escaneo:malwareScanning.onUpload.isEnabled, tope:malwareScanning.onUpload.capGBPerMonth, topico:malwareScanning.scanResultsEventGridTopicResourceId}'
# esperado con Defender: true, true, true, 100, id del tópico

az eventgrid topic event-subscription show -g "$RG" --topic-name "$EG_SCAN_TOPIC" -n a-function-onscanresult \
  --query '{estado:provisioningState, funcion:destination.resourceId, reintentos:retryPolicy}'
az functionapp config appsettings list -g "$RG" -n "$FUNCTION_NAME" --query "[?name=='ESCANEO_REQUERIDO'].value" -o tsv   # true
```

Prueba funcional (S3): subir el archivo de prueba EICAR por la app; el blob recibe la etiqueta de resultado, llega el evento a `OnScanResult` y la solicitud queda Rechazada con el nombre del malware como detalle. Un archivo limpio debe llegar a Archivado.

Estado real del sandbox (`cmv8adjsupfcopn`, 2026-10-14): Defender **deshabilitado** (`isEnabled: false`, `onUpload.isEnabled: false`, `capGBPerMonth: -1`, `overrideSubscriptionLevelSettings: false`); plan de la suscripción `StorageAccounts` en tier **Free**. No hay tópico de escaneo.

## Errores que ya vimos

No hay errores reales: Defender no se habilitó (decisión del usuario). Los siguientes son **anticipados**.

| Síntoma | Causa | Arreglo |
| --- | --- | --- |
| Defender no crea su system topic o los escaneos no arrancan | Ya existe el system topic de BlobCreated sobre la cuenta (una cuenta admite uno solo) | Borrar el de la guía 11 antes de habilitar Defender |
| No llega ningún evento a `OnScanResult` | Tópico en otra región, con acceso público cerrado, o `overrideSubscriptionLevelSettings` en `false` (manda la configuración de la suscripción, sin tópico) | Corregir región, red u override |
| Blobs que nunca reciben resultado y quedan en Subido | Se alcanzó `capGBPerMonth` | Subir el tope o aceptar el Watchdog; alertar sobre el consumo del tope (ADR-002, "Operación") |
| Archivos ingestados sin escaneo después de habilitar Defender | Quedó viva la suscripción `a-function-onblobcreated` | Borrarla (paso 4 de CLI) |
| Factura con cargo fijo en todas las cuentas | Se habilitó a nivel de suscripción en lugar de por cuenta | Volver a `Free` en la suscripción y habilitar solo en la cuenta de adjuntos |
| El despliegue falla en `suscripcionScan` | `crearSuscripcionEventGrid=true` antes de publicar el código | Guía 16 y redesplegar |
