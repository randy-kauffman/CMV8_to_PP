# 07. Storage de adjuntos (cuenta endurecida)

> Rol que lo ejecuta: Cloud. Depende de: 03, 04, 05 (workspace para los diagnósticos). Lo necesitan: 08 (Blob Data Contributor en el contenedor y Blob Delegator en la cuenta), 10 (Function App, `ADJUNTOS_*`), 11 (Event Grid, opcional), 12 (Defender, opcional) y 19 (manifiesto del PCF, dominio `<cuenta>.blob.core.windows.net`). Costo: menos de USD 5 por mes en el escenario mediano (Hot LRS ~USD 0,02 por GB y mes, con 7 días de retención máxima; ver sección 5b de `docs/costos-estimados.md`). Defender for Storage, si se habilita, es aparte.

## Qué es y qué hace en esta solución

Es la cuenta `cmv8adj<sufijo>` y su contenedor `cmv8-ingesta`: el corazón de ADR-002. El binario **nunca** pasa por Dataverse ni por la Function:

1. El conector le pide al broker una SAS de delegación de usuario ligada al usuario (`sduoid`), de solo escritura (`cw`), sobre un único blob `{solicitudId}/{archivo}`, con inicio y vencimiento (15 minutos), más un token de Storage del usuario obtenido por OBO.
2. El componente PCF sube **desde el navegador** directo a esta cuenta en bloques (`Put Block` + `Put Block List`), presentando la SAS en la URL y el token como `Authorization: Bearer`. Por eso la cuenta necesita CORS.
3. El evento `BlobCreated` (o el resultado de Defender) encola el trabajo en la cola de la guía 06; el worker lee el blob con la identidad administrada, lo archiva en Content Manager y lo **borra**.
4. Todo acceso queda en `StorageBlobLogs` (diagnósticos) con `AuthenticationType`, `RequesterUpn` y la URI con `scid`.

La cuenta está endurecida: Shared Key apagado (no existen claves de cuenta, ni SAS de servicio ni de cuenta), sin acceso anónimo, TLS 1.2, sin replicación entre tenants, política de SAS de 1 hora máximo con bloqueo, soft delete de 7 días y borrado automático de huérfanos.

## ¿Imprescindible?

**Veredicto: Imprescindible**

- Qué aporta: es donde vive el binario en tránsito. Permite 100 MB o más por archivo sin pasar por los límites de Dataverse ni de la Function, con autorización por archivo y por usuario.
- Qué pasa si no está: no hay dónde subir; el diseño de ADR-002 entero se cae.
- Qué decisión lo haría prescindible: **revertir ADR-002** y usar columnas de archivo de Dataverse. Ver `docs/justificacion-financiera-blob-vs-dataverse.md`: el almacenamiento de archivos de Dataverse es mucho más caro por GB y tiene límites de tamaño y de request que el requisito de 100 MB tensiona.
- Alternativa y su tradeoff: subir por el conector de Blob con VNet delegada de Power Platform (endpoint público cerrado), que cae en el límite de 50 MB y no cumple el requisito (ADR-002, "Red").

Dentro de la cuenta, pieza por pieza:

| Pieza | Veredicto | Por qué |
| --- | --- | --- |
| Cuenta con Shared Key OFF, sin anónimo, TLS 1.2, sin cross-tenant | Imprescindible | Es la base del modelo de seguridad: solo Entra y SAS de delegación |
| Contenedor `cmv8-ingesta` | Imprescindible | Alcance del rol de la identidad (mínimo privilegio) y del prefijo de ciclo de vida |
| CORS | Imprescindible | El PCF sube desde el navegador; sin CORS el preflight falla |
| Soft delete (blobs y contenedores, 7 días) | Prescindible (red de seguridad barata) | Protege de un borrado accidental; el worker borra igual al archivar |
| Ciclo de vida `borrar-adjuntos-huerfanos` | Prescindible (red de seguridad barata) | Borra lo que el worker no llegó a archivar; sin ella, los huérfanos se acumulan |
| Política de SAS (1 h, Block) | Defensa en profundidad | Ninguna SAS de ningún origen puede vivir más de 1 hora. El diseño no depende de ella |
| `requireUserBoundUserDelegationSas` | Defensa en profundidad, efecto no observado | El broker siempre firma con `sduoid`; en el spike, una SAS no ligada siguió funcionando con la política en Block (ADR-002, "Política de cuenta y auditoría") |
| Firewall de IP | Depende de la decisión de red S0 | Solo si todos los usuarios suben desde la red del Banco |
| Diagnósticos a Log Analytics | Prescindible técnicamente, imprescindible para auditoría | Única evidencia de quién subió qué |

## Valores del cuaderno

| Variable | Produce / consume | Cómo se obtiene |
| --- | --- | --- |
| `RG`, `REGION`, `PREFIJO`, `SUFIJO`, `SUB_ID` | Consume | Guía 03 |
| `MI_PRINCIPAL_ID` | Consume | Guía 04 |
| `LOG_ID` | Consume | Guía 05 |
| `STORAGE_ADJ` | Produce | `${PREFIJO}adj${SUFIJO}` (con Bicep: salida `storageAdjuntosName`) |
| `STORAGE_ADJ_ID` | Produce | `az storage account show -g "$RG" -n "$STORAGE_ADJ" --query id -o tsv` |
| `STORAGE_HOST_ADJ` | Produce | `$STORAGE_ADJ.blob.core.windows.net` (dominio para el manifiesto del PCF; salida `storageAdjuntosBlobEndpoint` sin `https://` ni `/`) |
| `CONTENEDOR_ADJ` | Produce | `cmv8-ingesta` (parámetro `contenedorAdjuntos`) |
| `DIAS_RETENCION` | Consume | `7` (parámetro `diasRetencionAdjuntos`) |
| `REQUERIR_SAS_LIGADA` / `ACCION_SAS_NO_LIGADA` | Consume | `true` / `Block` en el Banco (parámetros `requerirSasLigadaAUsuario` / `accionSasNoLigada`) |
| `IPS_PERMITIDAS` | Consume | Rangos públicos de salida del Banco separados por espacio, o vacío (parámetro `ipsPermitidas`; decisión S0) |

```bash
STORAGE_ADJ=            # cmv8adj<sufijo>
STORAGE_ADJ_ID=         # /subscriptions/.../storageAccounts/cmv8adj<sufijo>
CONTENEDOR_ADJ=cmv8-ingesta
DIAS_RETENCION=7
REQUERIR_SAS_LIGADA=true
ACCION_SAS_NO_LIGADA=Block
IPS_PERMITIDAS=         # "203.0.113.0/24 198.51.100.10" o vacío
```

## Con Bicep

Todo está en `infra/main.bicep`:

| Bloque | Recurso | Líneas | Parámetros |
| --- | --- | --- | --- |
| Cuenta | `storageAdjuntos` (`Microsoft.Storage/storageAccounts@2026-06-01`) | 215-240 | `prefijo`, `ipsPermitidas`, `requerirSasLigadaAUsuario`, `accionSasNoLigada` |
| Blob service (soft delete + CORS) | `storageAdjuntos::blobs` | 242-258 | `origenesCors` |
| Contenedor | `storageAdjuntos::blobs::ingesta` | 259-262 | `contenedorAdjuntos` |
| Ciclo de vida | `storageAdjuntos::lifecycle` | 265-289 | `contenedorAdjuntos`, `diasRetencionAdjuntos` |
| Diagnósticos | `diagAdjuntos` | 292-306 | ninguno (destino: el workspace de la guía 05) |
| Roles | `rolAdjuntosContenedor`, `rolAdjuntosDelegator` | 342-361 | ninguno (guía 08) |
| Opcionales | `defender`, `topicoScan`, `topicoSistemaAdjuntos` y suscripciones | 518-596 | `habilitarDefender`, `crearSuscripcionEventGrid` (fuera de esta guía) |

Valores para el Banco (tabla de parámetros de la sección 2 de `docs/guia-montaje-banco.md`): `contenedorAdjuntos = 'cmv8-ingesta'`, `origenesCors = ['*']` hasta la decisión de la fase 6 de la guía de spikes, `ipsPermitidas` según S0, `requerirSasLigadaAUsuario = true` con `accionSasNoLigada = 'Block'`, `diasRetencionAdjuntos = 7`.

Ojo con la versión de API: `requireUserBoundUserDelegationSas` y `requireUserBoundUserDelegationSasAction` solo existen en `2026-06-01`; por eso esta cuenta usa una API distinta de la cuenta host.

## Con Azure CLI

Bloques en orden. Todos usan el cuaderno:

```bash
source ~/cmv8-$AMBIENTE.env
export PYTHONWARNINGS=ignore
STORAGE_ADJ="${PREFIJO}adj${SUFIJO}"
CONTENEDOR_ADJ=cmv8-ingesta
```

### Cuenta

```bash
az storage account create --name "$STORAGE_ADJ" --resource-group "$RG" --location "$REGION" \
  --kind StorageV2 --sku Standard_LRS --access-tier Hot \
  --min-tls-version TLS1_2 --https-only true \
  --allow-blob-public-access false --allow-shared-key-access false \
  --allow-cross-tenant-replication false \
  --public-network-access Enabled --default-action Allow --bypass AzureServices -o none
STORAGE_ADJ_ID=$(az storage account show -g "$RG" -n "$STORAGE_ADJ" --query id -o tsv)

# defaultToOAuthAuthentication no tiene flag en az CLI 2.91: va por ARM
az rest --method patch --url "https://management.azure.com${STORAGE_ADJ_ID}?api-version=2026-06-01" \
  --body '{"properties":{"defaultToOAuthAuthentication":true}}' -o none
```

La red queda abierta acá (`--default-action Allow`) y se cierra, si corresponde, en la subsección "Red" DESPUÉS de cargar las IPs, para no dejar la cuenta inaccesible a mitad de camino.

### Blob service: soft delete y CORS

```bash
# Soft delete de blobs y de contenedores, 7 días (no tocar versionado: queda apagado)
az storage account blob-service-properties update --resource-group "$RG" --account-name "$STORAGE_ADJ" \
  --enable-delete-retention true --delete-retention-days 7 \
  --enable-container-delete-retention true --container-delete-retention-days 7 -o none

# CORS por ARM. 'az storage cors add' NO sirve: es de plano de datos, no tiene --auth-mode login
# y necesita la clave de cuenta, que no existe con Shared Key apagado.
az storage account blob-service-properties cors-rule clear --resource-group "$RG" --account-name "$STORAGE_ADJ" -o none
az storage account blob-service-properties cors-rule add --resource-group "$RG" --account-name "$STORAGE_ADJ" \
  --allowed-origins '*' --allowed-methods GET HEAD PUT OPTIONS \
  --allowed-headers '*' --exposed-headers '*' --max-age 3600 -o none
```

`cors-rule add` agrega una regla a las existentes; el `clear` previo hace el bloque repetible. Si el Banco restringe orígenes (fase 6 de la guía de spikes), reemplazar `'*'` por la lista separada por espacios; Storage acepta comodín de subdominio (por ejemplo `https://*.powerapps.com`), falta probar si cubre varios niveles (ADR-002).

### Contenedor

```bash
az storage container-rm create --resource-group "$RG" --storage-account "$STORAGE_ADJ" \
  --name "$CONTENEDOR_ADJ" --public-access off -o none
CONTENEDOR_ADJ_ID="${STORAGE_ADJ_ID}/blobServices/default/containers/${CONTENEDOR_ADJ}"
```

`container-rm` va por ARM: no necesita rol de datos ni Shared Key. `CONTENEDOR_ADJ_ID` es el alcance del rol Blob Data Contributor (guía 08).

### Ciclo de vida

```bash
T=$(mktemp -d)
python3 - "$CONTENEDOR_ADJ" "${DIAS_RETENCION:-7}" > "$T/politica.json" <<'PY'
import json, sys
contenedor, dias = sys.argv[1], int(sys.argv[2])
print(json.dumps({"rules": [{
    "name": "borrar-adjuntos-huerfanos",
    "enabled": True,
    "type": "Lifecycle",
    "definition": {
        "filters": {"blobTypes": ["blockBlob"], "prefixMatch": [f"{contenedor}/"]},
        "actions": {"baseBlob": {"delete": {"daysAfterModificationGreaterThan": dias}}}
    }
}]}, indent=2))
PY
az storage account management-policy create --resource-group "$RG" --account-name "$STORAGE_ADJ" \
  --policy @"$T/politica.json" -o none
rm -rf "$T"
```

La política se escribe completa (el servicio no admite actualizaciones parciales). Corre una vez por día; la primera ejecución puede tardar hasta 24 horas. Como el soft delete está activo, lo que borra la política queda 7 días más en estado eliminado (recuperable).

### Política de SAS

```bash
# sasPolicy completa en un solo PATCH: expiración + acción + exigencia de SAS ligada a usuario.
# az storage account update tiene --sas-exp y --sas-exp-action, pero NO los dos campos de SAS ligada,
# y az storage account show con la API de CLI 2.91 tampoco los muestra.
az rest --method patch --url "https://management.azure.com${STORAGE_ADJ_ID}?api-version=2026-06-01" \
  --body "{
    \"properties\": {
      \"sasPolicy\": {
        \"sasExpirationPeriod\": \"00.01:00:00\",
        \"expirationAction\": \"Block\",
        \"requireUserBoundUserDelegationSas\": ${REQUERIR_SAS_LIGADA:-true},
        \"requireUserBoundUserDelegationSasAction\": \"${ACCION_SAS_NO_LIGADA:-Block}\"
      }
    }
  }" -o none
```

Consecuencias que hay que conocer:

- Con la política activa, **toda SAS necesita fecha de inicio (`st`)**; una SAS sin `st` o con vida mayor a 1 hora queda fuera de política y, con `Block`, se rechaza. El broker ya fija `st` (`EmisorSasBlob`, `setStartTime`) y emite SAS de 15 minutos (subida) y 5 minutos (lectura).
- La política aplica también a las SAS de delegación que genere un operador a mano (por ejemplo desde Storage Explorer): máximo 1 hora.
- `requireUserBoundUserDelegationSas = true` con `Block` quedó aplicado en el sandbox pero sin efecto observado (ver ADR-002). No reemplaza el `sduoid` del broker.

### Red (firewall de IP, opcional según S0)

Solo si el Banco decide que todos los usuarios suben desde su red (`IPS_PERMITIDAS` no vacío). Si hay usuarios fuera, saltear esta subsección: la cuenta queda con `defaultAction = Allow`, como en el Bicep con `ipsPermitidas = []`.

```bash
if [ -n "${IPS_PERMITIDAS:-}" ]; then
  # 1. Primero las reglas, después el Deny (si no, la cuenta queda cerrada un instante para todos)
  az storage account network-rule add --resource-group "$RG" --account-name "$STORAGE_ADJ" \
    --ip-address $IPS_PERMITIDAS -o none
  # 2. Cerrar por defecto, manteniendo la excepción de servicios de confianza de Azure
  az storage account update --resource-group "$RG" --name "$STORAGE_ADJ" \
    --default-action Deny --bypass AzureServices -o none
fi
```

`$IPS_PERMITIDAS` va sin comillas a propósito: `--ip-address` acepta una lista separada por espacios. Recordar que la Function hoy sale por IPs compartidas de Flex Consumption: con el firewall cerrado y sin VNet ni private endpoint (S0), el worker no puede leer ni borrar el blob. **No cerrar el firewall antes de resolver S0.**

### Diagnósticos

```bash
az monitor diagnostic-settings create --name a-log-analytics \
  --resource "${STORAGE_ADJ_ID}/blobServices/default" \
  --workspace "$LOG_ID" \
  --logs '[{"category":"StorageRead","enabled":true},{"category":"StorageWrite","enabled":true},{"category":"StorageDelete","enabled":true}]' \
  --metrics '[{"category":"Transaction","enabled":true}]' -o none
```

El alcance es el **blob service** (`…/blobServices/default`), no la cuenta: los logs de lectura, escritura y borrado se definen por servicio. Los datos van a la tabla `StorageBlobLogs`. La categoría de métrica `Capacity` queda apagada, igual que en el Bicep.

## Con el portal

### Cuenta

1. **Storage accounts** → **Create**.
2. **Basics**: **Resource group** = `rg-cmv8-<ambiente>`; **Storage account name** = `cmv8adj<sufijo>`; **Region** = la del grupo; **Performance** = **Standard**; **Redundancy** = **Locally-redundant storage (LRS)** (ZRS si el Banco lo exige; ADR-002 lo permite).
3. **Advanced** → **Security**:
   - **Require secure transfer for REST API operations**: marcado (default).
   - **Allow enabling anonymous access on individual containers**: **desmarcar** si viene marcado.
   - **Enable storage account key access**: **desmarcar** (default marcado).
   - **Default to Microsoft Entra authorization in the Azure portal**: **marcar** (default desmarcado).
   - **Minimum TLS version**: **Version 1.2**.
   - **Permitted scope for copy operations**: dejar el default (el Bicep no lo fija).
4. **Advanced** → **Blob storage**: **Allow cross-tenant replication** **desmarcado**; **Access tier** = **Hot**. Sin hierarchical namespace, SFTP ni NFS.
5. **Networking**: **Public network access** = **Enable**, alcance **Enable from all networks** (el firewall se ajusta en "Red" si corresponde). (verificar en el portal los rótulos de esta pestaña)
6. **Data protection**: ver la subsección siguiente; se puede dejar configurado acá mismo.
7. **Encryption**: **Microsoft-managed keys**, salvo que cumplimiento exija CMK (ADR-002, "Protección de datos").
8. **Review + create** → **Create**.

### Blob service: soft delete y CORS

9. La cuenta → **Data management** → **Data protection** (o la pestaña **Data protection** del asistente):
   - **Enable soft delete for blobs**: marcado, **7** días.
   - **Enable soft delete for containers**: marcado, **7** días.
   - **Enable versioning for blobs**, **Enable blob change feed**, **Enable point-in-time restore for containers**: **apagados** (el portal no los marca por defecto; si alguien los marcó, desmarcarlos: point-in-time restore prende versionado y change feed, con costo).
   - **Enable permanent delete for soft deleted items**: apagado (es `allowPermanentDelete: false`). (verificar en el portal)
   - **Save**.
10. La cuenta → **Settings** → **Resource sharing (CORS)** → pestaña **Blob service** → una fila:
    - **Allowed origins**: `*`
    - **Allowed methods**: **GET**, **HEAD**, **PUT**, **OPTIONS** (solo esos cuatro; el default es ninguno).
    - **Allowed headers**: `*`
    - **Exposed headers**: `*`
    - **Max age**: `3600`
    - **Save**. Las pestañas de File, Queue y Table quedan vacías.

### Contenedor

11. La cuenta → **Data storage** → **Containers** → **+ Container** → **Name** = `cmv8-ingesta`; **Anonymous access level** = **Private (no anonymous access)** → **Create**.

### Ciclo de vida

12. La cuenta → **Data management** → **Lifecycle management** → pestaña **List View** → **Add a rule**.
13. **Details**: **Rule name** = `borrar-adjuntos-huerfanos`; **Rule scope** = **Limit blobs with filters**; **Blob type** = **Block blobs**; **Blob subtype** = **Base blobs**.
14. **Base blobs**: condición **Last modified**, **More than (days ago)** = `7`; acción **Then** = **Delete the blob**.
15. **Filter set**: **Blob prefix** = `cmv8-ingesta/`.
16. **Add**. Alternativa equivalente: pestaña **Code View**, pegar el JSON del bloque CLI y **Save**.

### Política de SAS

17. La cuenta → **Settings** → **Configuration** → **Shared access signature (SAS) expiration policy** = **Enabled**; **Upper limit for SAS expiry interval** = 0 días, 1 hora, 0 minutos, 0 segundos; acción **Block** → **Save**. (verificar en el portal: la documentación indica que el control puede aparecer deshabilitado si las claves de cuenta nunca se rotaron; con Shared Key apagado desde la creación no se verificó cómo se comporta. Si está gris, usar el bloque CLI)
18. **Exigir SAS ligada a usuario** (`requireUserBoundUserDelegationSas` y su acción): **no se encontró un control en el portal** documentado para esta propiedad. Aplicarla con el bloque `az rest` de la sección CLI (se puede correr desde **Cloud Shell** en el mismo portal). (verificar en el portal)

### Red

19. Solo si S0 lo decide: la cuenta → **Security + networking** → **Networking** → **Public network access** = **Enabled from selected virtual networks and IP addresses** → en **Firewall** / **IPv4 Addresses** agregar cada rango de salida del Banco → en **Exceptions** dejar marcado **Allow Azure services on the trusted services list to access this storage account** → **Save**. (verificar en el portal los rótulos, que cambiaron en 2025)

### Diagnósticos

20. La cuenta → **Monitoring** → **Diagnostic settings** → elegir el recurso **blob** (no la cuenta) → **Add diagnostic setting**.
21. **Diagnostic setting name** = `a-log-analytics`; **Logs** → **Categories**: marcar **StorageRead**, **StorageWrite**, **StorageDelete** (no usar los grupos **allLogs**/**audit**, para reproducir exactamente el Bicep); **Metrics**: marcar **Transaction** y dejar **Capacity** desmarcado.
22. **Destination details** → **Send to Log Analytics workspace** → suscripción y workspace `cmv8-log-<sufijo>` (o el central) → **Save**.

## Verificación

```bash
# Cuenta + política de SAS completa (la API 2026-06-01 es la única que muestra los campos de SAS ligada)
az rest --method get --url "https://management.azure.com${STORAGE_ADJ_ID}?api-version=2026-06-01" \
  --query '{kind:kind, sku:sku.name, tier:properties.accessTier, tls:properties.minimumTlsVersion, https:properties.supportsHttpsTrafficOnly, publico:properties.allowBlobPublicAccess, sharedKey:properties.allowSharedKeyAccess, oauth:properties.defaultToOAuthAuthentication, crossTenant:properties.allowCrossTenantReplication, red:properties.networkAcls, sas:properties.sasPolicy}' -o json
```

Esperado: `StorageV2`, `Standard_LRS`, `Hot`, `TLS1_2`, `https: true`, `publico: false`, `sharedKey: false`, `oauth: true`, `crossTenant: false`, `red.defaultAction` = `Allow` (o `Deny` con las `ipRules` si hubo firewall), `red.bypass` = `AzureServices` y:

```json
"sas": {
  "expirationAction": "Block",
  "requireUserBoundUserDelegationSas": true,
  "requireUserBoundUserDelegationSasAction": "Block",
  "sasExpirationPeriod": "00.01:00:00"
}
```

```bash
az storage account blob-service-properties show -g "$RG" -n "$STORAGE_ADJ" \
  --query '{softDelete:deleteRetentionPolicy, softDeleteContenedor:containerDeleteRetentionPolicy, versionado:isVersioningEnabled, cors:cors.corsRules}' -o json
# softDelete enabled/7, softDeleteContenedor enabled/7, versionado null o false, una regla CORS GET/HEAD/PUT/OPTIONS * * 3600

az storage container-rm show -g "$RG" --storage-account "$STORAGE_ADJ" -n "$CONTENEDOR_ADJ" --query publicAccess -o tsv   # None

az storage account management-policy show -g "$RG" --account-name "$STORAGE_ADJ" \
  --query 'policy.rules[].{regla:name, prefijo:definition.filters.prefixMatch, dias:definition.actions.baseBlob.delete.daysAfterModificationGreaterThan}' -o json
# borrar-adjuntos-huerfanos, ["cmv8-ingesta/"], 7.0

az monitor diagnostic-settings list --resource "${STORAGE_ADJ_ID}/blobServices/default" \
  --query '[].{nombre:name, logs:logs[?enabled].category, metricas:metrics[?enabled].category, destino:workspaceId}' -o json
# a-log-analytics, [StorageRead, StorageWrite, StorageDelete], [Transaction], $LOG_ID

curl -s -o /dev/null -w '%{http_code}\n' "https://$STORAGE_ADJ.blob.core.windows.net/$CONTENEDOR_ADJ?restype=container&comp=list"
# 409 (PublicAccessNotPermitted, verificado en el sandbox): nunca 200. Si hay firewall y se prueba desde fuera: 403
```

Después de la humo (guía 22), en el workspace → **Logs**:

```kusto
StorageBlobLogs
| where TimeGenerated > ago(1h)
| where OperationName in ("PutBlock", "PutBlockList")
| project TimeGenerated, OperationName, StatusCode, AuthenticationType, RequesterUpn, Uri
```

Esperado: `StatusCode` 201, `AuthenticationType = DelegationSas`, `RequesterUpn` del piloto y `scid=<solicitudId>` en la URI. Los 403 `AuthenticationFailed` **no** aparecen en esta tabla (fallan antes de autenticar): se ven en la métrica **Transactions** con la dimensión **ResponseType**.

En el portal: la cuenta → **Settings** → **Configuration**; **Data management** → **Data protection** y **Lifecycle management**; **Settings** → **Resource sharing (CORS)**; **Monitoring** → **Diagnostic settings** (recurso blob).

## Errores que ya vimos

| Síntoma | Causa | Arreglo |
| --- | --- | --- |
| `az storage cors add` pide clave de cuenta o falla | Comando de plano de datos sin `--auth-mode login`; la cuenta no tiene Shared Key | `az storage account blob-service-properties cors-rule add` (ARM) |
| `az storage account show` no muestra `requireUserBoundUserDelegationSas` | La API que usa az CLI 2.91 es anterior a `2026-06-01` | Verificar con `az rest … api-version=2026-06-01` |
| Una SAS de delegación NO ligada sigue funcionando con la política en Block | Efecto de `requireUserBoundUserDelegationSas` no observado en el spike | El diseño no depende de ella (el broker firma con `sduoid`); reprobar y, si persiste, caso con Microsoft (ADR-002) |
| 403 `AuthenticationFailed` al subir solo con la SAS | La SAS está ligada al usuario (`sduoid`) y falta el bearer de Storage del mismo usuario | El PCF manda la SAS y el `Authorization: Bearer` en cada PUT (checklist de la app) |
| 403 `AuthorizationPermissionMismatch` al subir | Faltó el bearer junto con la SAS ligada, o el usuario tiene un rol de datos que no debería | Revisar la app; los usuarios no tienen RBAC en el contenedor |
| No hay rastros de 403 `AuthenticationFailed` en `StorageBlobLogs` | Esos rechazos no se registran en logs de recurso | Alertas sobre la métrica Transactions por `ResponseType` |
| El preflight `OPTIONS` falla desde Power Apps (anticipado) | CORS sin `PUT`/`OPTIONS`, o con orígenes enumerados que no cubren el host regional de Power Apps | Regla con los cuatro métodos y `*` (o comodín de subdominio verificado) |
| Una SAS emitida a mano es rechazada aunque dure 30 minutos (anticipado) | Falta `st` (fecha de inicio): fuera de política con `Block` | Emitir siempre con inicio; el broker ya lo hace |
| El worker no puede leer ni borrar el blob después de cerrar el firewall (anticipado) | La Function sale por IPs compartidas sin VNet; `AzureServices` no cubre a Functions | No cerrar el firewall hasta S0 (VNet + private endpoint) |
| La política de ciclo de vida "no borra" (anticipado) | Primera ejecución hasta 24 h; o el prefijo no termina en `/` o no coincide con el contenedor | Esperar un día; revisar `prefixMatch = ["cmv8-ingesta/"]` |
| `RoleAssignmentExists` al pasar de montaje manual a Bicep (anticipado) | Mismas asignaciones con nombres distintos | Ver guía 08 |
