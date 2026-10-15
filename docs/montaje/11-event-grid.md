# 11. Event Grid: system topic de adjuntos y suscripción BlobCreated

> Rol que lo ejecuta: Cloud. Depende de: 03 a 08 (cuenta de adjuntos `STORAGE_ADJ` y su contenedor `cmv8-ingesta`), 10 (Function App), 15 (Easy Auth con `/runtime/webhooks/eventgrid` excluido) y **16 (código publicado: la función `OnBlobCreated` tiene que existir)**. Lo necesitan: el worker de ingesta (`OnBlobCreated` → cola `ingesta-pendiente` → `ProcesarIngesta`). Costo: despreciable (las primeras 100.000 operaciones del mes son gratis, aprox.; `docs/costos-estimados.md` §5b).

## Qué es y qué hace en esta solución

Cuando el componente PCF termina de subir un archivo (el `Put Block List` que confirma los bloques), Azure Storage publica un evento `Microsoft.Storage.BlobCreated`. Un **system topic** de Event Grid, creado sobre la cuenta de adjuntos, es el recurso que expone esos eventos; una **suscripción** (`a-function-onblobcreated`) los filtra y los entrega a la función `OnBlobCreated` de la Function App.

- Filtro: solo `Microsoft.Storage.BlobCreated` y solo blobs cuyo subject empieza con `/blobServices/default/containers/cmv8-ingesta/`. Las demás soluciones que compartan la cuenta (un contenedor por solución, ADR-002) no disparan este worker.
- Entrega: destino de tipo `AzureFunction` (`<sitio>/functions/OnBlobCreated`), un evento por lote (`maxEventsPerBatch: 1`, `preferredBatchSizeInKilobytes: 64`), esquema `EventGridSchema`.
- Qué hace la función (`function/src/main/java/com/banco/cmv8/adapters/in/EventosEventGrid.java`): valida el tipo de evento, extrae de `data.url` la ruta `{solicitudId}/{archivo}`, ignora blobs de otro contenedor o con otra forma, registra el resultado como **limpio** (no hay escaneo) y encola un mensaje en `%COLA_INGESTA%`. Si el caso de uso lanza una excepción, la propaga y Event Grid reintenta.

Por qué se crea **después** de publicar el código: al crear una suscripción con destino `AzureFunction`, Event Grid valida que el recurso `…/sites/<function>/functions/OnBlobCreated` exista. Sobre una Function sin código la creación falla. Por eso el Bicep la condiciona a `crearSuscripcionEventGrid=true` y se activa en un **segundo despliegue**, después de la guía 16.

**Reintentos.** Si la Function no responde o devuelve error, Event Grid reintenta con backoff exponencial (10 s, 30 s, 1 min, 5 min, 10 min, 30 min, 1 h, 3 h, 6 h y luego cada 12 h) hasta 30 intentos o 24 horas (1.440 minutos), lo que ocurra primero. Para destinos de recursos de Azure, un 400, 403 o 413 no se reintenta. Al agotarse, el evento va a **dead-letter** si hay un contenedor configurado; si no, **se descarta**. La suscripción del Bicep **no** define `retryPolicy` (usa esos defaults) **ni dead-letter**: un evento que no se entrega en 24 horas se pierde.

**Lo que cubre la pérdida: el Watchdog.** `WatchdogTimer` corre cada 15 minutos (`0 */15 * * * *`) y reencola las solicitudes que quedaron en Subido más de `ESTANCADA_MINUTOS` (15). El estado Subido lo escribe la app en Dataverse al terminar la carga, independiente de Event Grid. Un evento perdido demora la ingesta hasta el siguiente ciclo del Watchdog; no la pierde. El worker es idempotente (no sube a CMIS si la solicitud ya tiene `cmisObjectId`), así que un evento duplicado tampoco duplica el documento.

## ¿Imprescindible?

**Veredicto: Imprescindible** (para el disparador elegido en ADR-002).

- Qué aporta: dispara la ingesta en segundos sin que la app ni Power Automate intervengan; no consume Power Platform requests; reintentos de 24 horas y dead-letter nativos (este último a configurar); filtra por contenedor y tipo de evento sin código.
- Qué pasa si no está: ningún blob dispara `OnBlobCreated`. Las solicitudes quedan en Subido y solo el Watchdog las procesa, con hasta 15 minutos de demora más el umbral de `ESTANCADA_MINUTOS`. Funciona, pero como modo degradado: el Watchdog es la red, no el camino principal.
- Qué decisión lo haría prescindible (o distinto): que el Banco prefiera que la app llame a un endpoint HTTP de la Function al terminar la subida, o un flujo de Power Automate disparado por el cambio de estado en Dataverse (el diseño de ADR-001). Con Defender habilitado el disparador cambia: el evento lo publica Defender en un tópico propio y lo recibe `OnScanResult` (guía 12); este system topic no se crea.
- Alternativa y su tradeoff: **llamada HTTP desde la app o flujo de Power Automate**. Ventaja: un recurso de Azure menos y nada que crear en un segundo despliegue. Costo: cada disparo consume requests del pool del usuario (el flujo además depende de su licencia y de la conexión), se pierden los reintentos y el dead-letter nativos (si la llamada falla, la app tiene que reintentar o el usuario se entera), y la ingesta depende de que el navegador siga abierto hasta llamar. El Watchdog cubriría los huecos con la misma demora de arriba.

## Valores del cuaderno

| Variable | Produce / consume | Cómo se obtiene |
| --- | --- | --- |
| `RG`, `REGION` | Consume | Cuaderno |
| `SUB` | Produce (local) | `az account show --query id -o tsv` |
| `PREFIJO`, `SUFIJO` | Consume | Convención de nombres (guía 09) |
| `STORAGE_ADJ` | Consume | Salida `storageAdjuntosName` |
| `STORAGE_ADJ_ID` | Produce (local) | `az storage account show -g "$RG" -n "$STORAGE_ADJ" --query id -o tsv` |
| `CONTENEDOR_ADJ` | Consume | `cmv8-ingesta` (salida `contenedorAdjuntosName`) |
| `FUNCTION_NAME` | Consume | Guía 10 |
| `FUNCTION_ID` | Produce (local) | `az functionapp show -g "$RG" -n "$FUNCTION_NAME" --query id -o tsv` |
| `EG_TOPIC` | Produce | `${PREFIJO}-egst-adj-${SUFIJO}` |

## Con Bicep

Está en `infra/main.bicep`, sección "Fallback si Defender no está aprobado": `topicoSistemaAdjuntos` (líneas 569 a 576, `Microsoft.EventGrid/systemTopics@2025-02-15`) y `suscripcionBlobCreated` (líneas 578 a 596, `Microsoft.EventGrid/systemTopics/eventSubscriptions@2025-02-15`). Los dos se crean solo si `!habilitarDefender && crearSuscripcionEventGrid`.

| Parámetro | Qué gobierna | Valor recomendado en el Banco |
| --- | --- | --- |
| `crearSuscripcionEventGrid` | Crea el system topic y la suscripción | `false` en el primer despliegue; `true` en un **segundo** despliegue después de la guía 16 |
| `habilitarDefender` | Si es `true`, este par NO se crea (el disparador pasa a ser Defender, guía 12) | `false` (decisión vigente) |
| `contenedorAdjuntos` | Prefijo del filtro `subjectBeginsWith` | `cmv8-ingesta` |
| `prefijo` | Nombre `${prefijo}-egst-adj-<sufijo>` | `cmv8` |

Fijado sin parámetro: `topicType: Microsoft.Storage.StorageAccounts`, `source` = la cuenta de adjuntos, nombre de suscripción `a-function-onblobcreated`, `eventDeliverySchema: EventGridSchema`, `includedEventTypes: ['Microsoft.Storage.BlobCreated']`, destino `AzureFunction` con `maxEventsPerBatch: 1` y `preferredBatchSizeInKilobytes: 64`. Sin `retryPolicy` (defaults 30 intentos / 1.440 minutos) y sin `deadLetterDestination`.

Segundo despliegue:

```bash
# en infra/banco-$AMBIENTE.bicepparam: param crearSuscripcionEventGrid = true
bash infra/desplegar.sh "$RG" "$REGION" infra/banco-$AMBIENTE.bicepparam
```

Recomendación para el Banco (no está en el Bicep): agregar dead-letter a un contenedor del storage host con identidad del system topic (ver "Con Azure CLI", paso 3) para que un evento no entregado quede auditable en lugar de perderse.

## Con Azure CLI

Verificado con `az --help` (az CLI 2.91). Correr **después** de publicar el código (guía 16).

### 1. System topic

```bash
source ~/cmv8-$AMBIENTE.env
SUB=$(az account show --query id -o tsv)
EG_TOPIC="${PREFIJO}-egst-adj-${SUFIJO}"
STORAGE_ADJ_ID=$(az storage account show -g "$RG" -n "$STORAGE_ADJ" --query id -o tsv)

az eventgrid system-topic create \
  --resource-group "$RG" \
  --name "$EG_TOPIC" \
  --location "$REGION" \
  --topic-type Microsoft.Storage.StorageAccounts \
  --source "$STORAGE_ADJ_ID" \
  -o none
```

Una cuenta de Storage admite **un solo** system topic. Si ya existe uno (por ejemplo, lo creó el portal al configurar un evento, o Defender for Storage al habilitar el escaneo), se reutiliza ese en lugar de crear otro: `az eventgrid system-topic list -g "$RG" --query "[?source=='$STORAGE_ADJ_ID'].name" -o tsv`.

### 2. Suscripción hacia OnBlobCreated

```bash
FUNCTION_ID=$(az functionapp show -g "$RG" -n "$FUNCTION_NAME" --query id -o tsv)

az eventgrid system-topic event-subscription create \
  --resource-group "$RG" \
  --system-topic-name "$EG_TOPIC" \
  --name a-function-onblobcreated \
  --endpoint-type azurefunction \
  --endpoint "$FUNCTION_ID/functions/OnBlobCreated" \
  --event-delivery-schema eventgridschema \
  --included-event-types Microsoft.Storage.BlobCreated \
  --subject-begins-with "/blobServices/default/containers/cmv8-ingesta/" \
  --max-events-per-batch 1 \
  --preferred-batch-size-in-kilobytes 64 \
  --max-delivery-attempts 30 \
  --event-ttl 1440 \
  -o none
```

`--max-delivery-attempts 30` y `--event-ttl 1440` son los defaults (los mismos que deja el Bicep); se escriben explícitos para que el comando documente el reintento.

### 3. Dead-letter (recomendado, no está en el Bicep, no probado)

La CLI acepta `--deadletter-endpoint <resource id del contenedor>`, pero con Shared Key deshabilitado en las cuentas de esta solución la entrega a dead-letter tiene que hacerse con la identidad del system topic. Eso se configura por ARM:

```bash
# identidad del sistema en el topic y rol sobre un contenedor de dead-letter del storage host
az eventgrid system-topic update -g "$RG" -n "$EG_TOPIC" --mi-system-assigned -o none   # en 2.91 --identity está deprecado; --mi-system-assigned es el reemplazo
EG_PRINCIPAL=$(az eventgrid system-topic show -g "$RG" -n "$EG_TOPIC" --query identity.principalId -o tsv)
DL_CONTENEDOR_ID="/subscriptions/$SUB/resourceGroups/$RG/providers/Microsoft.Storage/storageAccounts/${PREFIJO}host${SUFIJO}/blobServices/default/containers/eventgrid-deadletter"
# (crear el contenedor eventgrid-deadletter en el storage host con la guía de storage)
az role assignment create --assignee-object-id "$EG_PRINCIPAL" --assignee-principal-type ServicePrincipal \
  --role "Storage Blob Data Contributor" --scope "$DL_CONTENEDOR_ID" -o none

az rest --method PATCH \
  --url "https://management.azure.com/subscriptions/$SUB/resourceGroups/$RG/providers/Microsoft.EventGrid/systemTopics/$EG_TOPIC/eventSubscriptions/a-function-onblobcreated?api-version=2025-02-15" \
  --body "{\"properties\":{\"deadLetterWithResourceIdentity\":{
    \"identity\":{\"type\":\"SystemAssigned\"},
    \"deadLetterDestination\":{\"endpointType\":\"StorageBlob\",\"properties\":{
      \"resourceId\":\"/subscriptions/$SUB/resourceGroups/$RG/providers/Microsoft.Storage/storageAccounts/${PREFIJO}host${SUFIJO}\",
      \"blobContainerName\":\"eventgrid-deadletter\"}}}}}" -o none
```

Este paso no se probó en el sandbox; validar en dev antes de llevarlo al Bicep.

## Con el portal

1. Ir a la cuenta de adjuntos `cmv8adj<sufijo>` → **Events** (menú izquierdo).
2. **+ Event Subscription**. Pestaña **Basic**:
   - **Name**: `a-function-onblobcreated`.
   - **Event Schema**: **Event Grid Schema** (default).
   - **System Topic Name**: `cmv8-egst-adj-<sufijo>` (CAMBIAR: el portal propone un nombre propio; si la cuenta ya tiene un system topic, lo muestra y no se puede cambiar).
   - **Filter to Event Types**: dejar SOLO **Blob Created** (CAMBIAR: el default marca también **Blob Deleted**).
   - **Endpoint Type**: **Azure Function** → **Configure an endpoint** → suscripción, grupo, Function App `cmv8-func-<sufijo>`, slot **Production**, función **OnBlobCreated** → **Confirm Selection**. Si la función no aparece, el código no está publicado (guía 16).
3. Pestaña **Filters**: marcar **Enable subject filtering** → **Subject Begins With** = `/blobServices/default/containers/cmv8-ingesta/` (CAMBIAR).
4. Pestaña **Additional Features**:
   - **Max events per batch** = 1 y **Preferred batch size in kilobytes** = 64 (verificar en el portal en qué pestaña aparecen los campos de lote para destinos Azure Function).
   - **Retry policies**: **Max delivery attempts** 30, **Event time to live** 1 día (defaults).
   - **Dead-lettering**: opcional, recomendado (ver "Con Bicep"); elegir **Use system assigned identity** si el portal lo ofrece (verificar en el portal).
5. **Create**.

## Verificación

```bash
az eventgrid system-topic show -g "$RG" -n "$EG_TOPIC" --query '{tipo:topicType, origen:source, estado:provisioningState}'
# esperado: Microsoft.Storage.StorageAccounts, id de la cuenta de adjuntos, Succeeded

az eventgrid system-topic event-subscription show -g "$RG" --system-topic-name "$EG_TOPIC" -n a-function-onblobcreated \
  --query '{estado:provisioningState, tipos:filter.includedEventTypes, prefijo:filter.subjectBeginsWith, destino:destination.endpointType, funcion:destination.resourceId, lote:destination.maxEventsPerBatch, reintentos:retryPolicy}'
# esperado: Succeeded, [Microsoft.Storage.BlobCreated], /blobServices/default/containers/cmv8-ingesta/, AzureFunction, .../functions/OnBlobCreated, 1, 30 / 1440
```

Prueba de punta a punta (§10 "Humo y entrega" de `docs/guia-montaje-banco.md`): subir un archivo desde la app y verificar en Application Insights la traza de `OnBlobCreated` y de `ProcesarIngesta` para ese `solicitudId`. Las métricas del system topic en el portal (**Metrics** → **Delivered Events**, **Delivery Failed Events**, **Dropped Events**) muestran si hubo eventos perdidos.

Estado del sandbox (2026-10-14): **no hay system topic** en `rg-cmv8-spikes`; `crearSuscripcionEventGrid=false`. Las pruebas del worker se hicieron llamando al caso de uso directamente.

## Errores que ya vimos

No hay errores reales registrados: la suscripción no se creó en el sandbox. Los siguientes son **anticipados**.

| Síntoma | Causa | Arreglo |
| --- | --- | --- |
| El despliegue falla en `suscripcionBlobCreated` con un error de validación del destino | `crearSuscripcionEventGrid=true` antes de publicar el código: `OnBlobCreated` no existe | Publicar (guía 16) y redesplegar |
| Eventos con `Delivery Failed` y 401 | Easy Auth exige token y `/runtime/webhooks/eventgrid` no está en `excludedPaths` | Guía 15 |
| Eventos entregados pero la función los ignora ("evento de un blob fuera del contenedor") | Blob subido fuera de `cmv8-ingesta` o sin la forma `{solicitudId}/{archivo}` | Revisar la ruta que arma el broker; el filtro ya excluye otros contenedores |
| El system topic no se crea: ya existe uno sobre la cuenta | Una cuenta admite un solo system topic (pudo crearlo el portal o Defender) | Reutilizar el existente: `--system-topic-name <existente>` |
| Solicitudes que tardan hasta 30 minutos en archivarse | Eventos descartados (sin dead-letter) y recuperados por el Watchdog | Revisar `Dropped Events`; configurar dead-letter |
| Dos ingestas del mismo blob en los logs | Reintento de Event Grid tras un timeout (entrega al menos una vez) | Nada: el worker es idempotente por `cmisObjectId` |
