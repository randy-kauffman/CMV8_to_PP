# 06. Storage host (runtime de Functions y paquete de despliegue)

> Rol que lo ejecuta: Cloud. Depende de: 03, 04. Lo necesitan: 08 (tres roles de datos), 10 (Function App: `functionAppConfig.deployment.storage` y `AzureWebJobsStorage__*`) y 16 (publicación del código). Costo: USD 1 a 5 por mes aprox. (transacciones de cola y del host, pocos GB; ver `docs/costos-estimados.md`).

## Qué es y qué hace en esta solución

Es la cuenta `cmv8host<sufijo>` que usa **la plataforma**, no los usuarios. Tres cosas viven acá:

- **El paquete de despliegue** de la Function: Flex Consumption lee el `.zip` desde el contenedor `app-package-<sufijo>` con la identidad administrada (no hay Azure Files ni `WEBSITE_CONTENTAZUREFILECONNECTIONSTRING` en este plan).
- **`AzureWebJobsStorage`**: el host de Functions guarda acá sus claves (`azure-webjobs-secrets`, que incluyen la clave de sistema con la que Event Grid entrega a `/runtime/webhooks/eventgrid`) y los bloqueos de los timers (`azure-webjobs-hosts`, que usa el `WatchdogTimer` cada 15 minutos).
- **La cola `ingesta-pendiente`** (y su `ingesta-pendiente-poison`): `EventosEventGrid` y `WatchdogTimer` escriben con `@QueueOutput`, y `ColaIngestaFunction` consume con `@QueueTrigger`, las tres con `connection = "AzureWebJobsStorage"` (nombre de cola en el app setting `COLA_INGESTA`). La conexión es por identidad: `AzureWebJobsStorage__accountName`, `__credential = managedidentity` y `__clientId` = client id de la identidad. **La cola no la crea el Bicep ni esta guía: la crea el runtime en el primer uso.**

Ningún binario de adjuntos pasa por esta cuenta (eso es la guía 07).

## ¿Imprescindible?

**Veredicto: Imprescindible**

- Qué aporta: en Flex Consumption es obligatoria una cuenta para el paquete de despliegue y para `AzureWebJobsStorage`; además desacopla la carga (evento del blob) del archivado en Content Manager mediante la cola.
- Qué pasa si no está: la Function App no se puede crear ni arrancar; sin cola, el evento `BlobCreated` (u `OnScanResult`) no tiene dónde dejar el trabajo y el watchdog no puede reencolar solicitudes estancadas.
- Qué decisión lo haría distinto: **fusionarla con la cuenta de adjuntos**. Técnicamente funciona, pero el runtime necesita **Storage Blob Data Owner** sobre su cuenta (paquete, claves, leases); si es la misma cuenta, la identidad tendría Owner sobre los adjuntos y se pierde el mínimo privilegio de "Contributor solo en el contenedor `cmv8-ingesta`" (decisión documentada en `infra/README.md`, "Decisiones embebidas"). Tampoco se podría aplicar a los adjuntos el firewall de IP sin afectar al runtime.
- Alternativa y su tradeoff: reemplazar la cola de Storage por Service Bus. Da sesiones, dead-letter con más control y RBAC por entidad, a cambio de otro recurso con costo fijo y de cambiar los bindings. La cuenta host seguiría siendo necesaria para el paquete y el host.

## Valores del cuaderno

| Variable | Produce / consume | Cómo se obtiene |
| --- | --- | --- |
| `RG`, `REGION`, `PREFIJO`, `SUFIJO` | Consume | Guía 03 |
| `MI_PRINCIPAL_ID`, `MI_CLIENT_ID` | Consume | Guía 04 |
| `STORAGE_HOST` | Produce | `${PREFIJO}host${SUFIJO}` (3 a 24 caracteres, minúsculas y dígitos; con `cmv8` y 8 de sufijo da 16) |
| `STORAGE_HOST_ID` | Produce | `az storage account show -g "$RG" -n "$STORAGE_HOST" --query id -o tsv` |
| `CONTENEDOR_DESPLIEGUE` | Produce | `app-package-${SUFIJO}` |

```bash
STORAGE_HOST=           # cmv8host<sufijo>
STORAGE_HOST_ID=        # /subscriptions/.../storageAccounts/cmv8host<sufijo>
CONTENEDOR_DESPLIEGUE=  # app-package-<sufijo>
```

## Con Bicep

- `storageHost` (`Microsoft.Storage/storageAccounts@2023-05-01`), líneas 184-210: `StorageV2`, `Standard_LRS`, `accessTier: Hot`, `minimumTlsVersion: TLS1_2`, `supportsHttpsTrafficOnly: true`, `allowBlobPublicAccess: false`, `allowSharedKeyAccess: false`, `defaultToOAuthAuthentication: true`, `publicNetworkAccess: Enabled`, `networkAcls` con `defaultAction: Allow` y `bypass: AzureServices`.
- Contenedor `despliegue` (líneas 203-209): `app-package-<token>` con `publicAccess: None`. Sin soft delete, sin CORS, sin ciclo de vida, sin diagnósticos (a propósito: no tiene datos de negocio).
- Roles `rolHostOwner`, `rolHostQueue`, `rolHostTable` (líneas 311-339): detalle en la guía 08.
- Uso: `functionAppConfig.deployment.storage` (líneas 458-465) y app settings `AzureWebJobsStorage__*` (líneas 490-492). La Function declara `dependsOn` sobre los tres roles para no arrancar sin permisos.
- Parámetros: ninguno propio (solo `prefijo`). El Bicep **no** fija `allowCrossTenantReplication` en esta cuenta; la API lo deja en `false` por defecto (verificado en el sandbox).

## Con Azure CLI

```bash
source ~/cmv8-$AMBIENTE.env
export PYTHONWARNINGS=ignore
STORAGE_HOST="${PREFIJO}host${SUFIJO}"
CONTENEDOR_DESPLIEGUE="app-package-${SUFIJO}"

# 1. Cuenta (todas las propiedades del Bicep que tienen flag en az CLI 2.91)
az storage account create --name "$STORAGE_HOST" --resource-group "$RG" --location "$REGION" \
  --kind StorageV2 --sku Standard_LRS --access-tier Hot \
  --min-tls-version TLS1_2 --https-only true \
  --allow-blob-public-access false --allow-shared-key-access false \
  --allow-cross-tenant-replication false \
  --public-network-access Enabled --default-action Allow --bypass AzureServices -o none
STORAGE_HOST_ID=$(az storage account show -g "$RG" -n "$STORAGE_HOST" --query id -o tsv)

# 2. defaultToOAuthAuthentication: az storage account create/update NO tiene flag para esta propiedad
az rest --method patch --url "https://management.azure.com${STORAGE_HOST_ID}?api-version=2023-05-01" \
  --body '{"properties":{"defaultToOAuthAuthentication":true}}' -o none

# 3. Contenedor del paquete de despliegue (por ARM: funciona con Shared Key apagado y sin rol de datos)
az storage container-rm create --resource-group "$RG" --storage-account "$STORAGE_HOST" \
  --name "$CONTENEDOR_DESPLIEGUE" --public-access off -o none

# 4. Roles de datos de la identidad sobre la cuenta (guía 08 tiene la tabla completa)
for ROL in b7e6dc6d-f1e8-4753-8033-0f276bb0955b \
           974c5e8b-45b9-4653-ba55-5f855dd0fb88 \
           0a9a7e1f-b9d0-4cc4-a60d-0319b160aaa3; do
  az role assignment create --assignee-object-id "$MI_PRINCIPAL_ID" --assignee-principal-type ServicePrincipal \
    --role "$ROL" --scope "$STORAGE_HOST_ID" -o none
done
```

Notas:

- `--allow-cross-tenant-replication false` no está en el Bicep para esta cuenta, pero es el valor que la API pone por defecto; fijarlo explícito evita depender de la versión.
- **La cola `ingesta-pendiente` no se crea acá**: el host la crea en el primer `@QueueOutput`, y la `-poison` aparece con el primer mensaje que agota sus reintentos. Si el Banco exige que exista antes (por ejemplo para alertas sobre su largo), quien la cree necesita un rol de datos de colas: `az storage queue create --name ingesta-pendiente --account-name "$STORAGE_HOST" --auth-mode login`. Owner del grupo **no** alcanza para operaciones de datos.
- Los valores que la Function usa (`AzureWebJobsStorage__accountName=$STORAGE_HOST`, `AzureWebJobsStorage__credential=managedidentity`, `AzureWebJobsStorage__clientId=$MI_CLIENT_ID` y el destino `https://$STORAGE_HOST.blob.core.windows.net/$CONTENEDOR_DESPLIEGUE`) se fijan en la guía 10.

## Con el portal

1. Buscar **Storage accounts** → **Create**.
2. **Basics**: **Resource group** = `rg-cmv8-<ambiente>`; **Storage account name** = `cmv8host<sufijo>`; **Region** = la del grupo; **Performance** = **Standard**; **Redundancy** = **Locally-redundant storage (LRS)**. **Preferred storage type** es solo orientativo; si lo pide, elegir Blob storage.
3. **Advanced**, sección **Security**:
   - **Require secure transfer for REST API operations**: marcado (default).
   - **Allow enabling anonymous access on individual containers**: **desmarcar** si viene marcado.
   - **Enable storage account key access**: **desmarcar** (el default es marcado). Es el `allowSharedKeyAccess: false`.
   - **Default to Microsoft Entra authorization in the Azure portal**: **marcar** (el default es desmarcado).
   - **Minimum TLS version**: **Version 1.2** (default).
4. **Advanced**, sección **Blob storage**: **Allow cross-tenant replication** desmarcado; **Access tier** = **Hot** (default). Dejar **Enable hierarchical namespace**, SFTP y NFS apagados.
5. **Networking**: **Public network access** = **Enable**; **Public network access scope** = **Enable from all networks**. Sin private endpoint en esta etapa (S0). (verificar en el portal los rótulos exactos de esta pestaña, que cambiaron en 2025)
6. **Data protection**: el Bicep no habilita nada en esta cuenta. **El portal marca por defecto el soft delete de blobs, contenedores y file shares**: desmarcarlos para reproducir el Bicep (dejarlos prendidos no rompe nada, solo agrega un costo mínimo y diverge de la plantilla). Versionado y change feed apagados.
7. **Encryption**: dejar **Microsoft-managed keys** (default), salvo que el Banco exija CMK.
8. **Review + create** → **Create**.
9. Contenedor: la cuenta → **Data storage** → **Containers** → **+ Container** (o **+ Add container**) → **Name** = `app-package-<sufijo>`; **Anonymous access level** = **Private (no anonymous access)** → **Create**. Como Shared Key está apagado, el portal usa la cuenta Entra de quien opera para listar; la creación del contenedor es una operación de control y no necesita rol de datos. (verificar en el portal)
10. Roles: la cuenta → **Access control (IAM)** → **Add** → **Add role assignment**, tres veces, una por rol: **Storage Blob Data Owner**, **Storage Queue Data Contributor**, **Storage Table Data Contributor** → **Assign access to** = **Managed identity** → **+ Select members** → **User-assigned managed identity** → `cmv8-mi-<sufijo>` → **Select** → **Review + assign**.

La propiedad **Default to Microsoft Entra authorization** también se puede corregir después en la cuenta → **Settings** → **Configuration**.

## Verificación

```bash
az storage account show -g "$RG" -n "$STORAGE_HOST" \
  --query '{kind:kind, sku:sku.name, tier:accessTier, tls:minimumTlsVersion, https:enableHttpsTrafficOnly, publico:allowBlobPublicAccess, sharedKey:allowSharedKeyAccess, oauth:defaultToOAuthAuthentication, crossTenant:allowCrossTenantReplication, red:networkRuleSet.defaultAction}' -o json
```

Esperado: `StorageV2`, `Standard_LRS`, `Hot`, `TLS1_2`, `https: true`, `publico: false`, `sharedKey: false`, `oauth: true`, `crossTenant: false`, `red: Allow`.

```bash
az storage container-rm show -g "$RG" --storage-account "$STORAGE_HOST" -n "$CONTENEDOR_DESPLIEGUE" --query publicAccess -o tsv   # None
az role assignment list --scope "$STORAGE_HOST_ID" --assignee-object-id "$MI_PRINCIPAL_ID" --query '[].roleDefinitionName' -o tsv
# Storage Blob Data Owner / Storage Queue Data Contributor / Storage Table Data Contributor
```

Después del primer despliegue del código y de la primera carga, con una cuenta que tenga lectura de datos:

```bash
az storage queue list --account-name "$STORAGE_HOST" --auth-mode login --query '[].name' -o tsv
# ingesta-pendiente (y ingesta-pendiente-poison si algún mensaje agotó reintentos)
az storage container list --account-name "$STORAGE_HOST" --auth-mode login --query '[].name' -o tsv
# app-package-<sufijo>, azure-webjobs-hosts, azure-webjobs-secrets
```

En el portal: la cuenta → **Settings** → **Configuration** (Shared Key **Disabled**, Entra por defecto **Enabled**, TLS 1.2); **Data storage** → **Containers** y **Queues**.

## Errores que ya vimos

| Síntoma | Causa | Arreglo |
| --- | --- | --- |
| La identidad tiene además **Storage Blob Data Contributor** sobre esta cuenta en el sandbox | Se agregó a mano durante los spikes; no está en el Bicep | Es redundante con Blob Data Owner; en el Banco no asignarlo. En el sandbox se puede borrar |
| `az storage cors add` o `az storage queue create` sin `--auth-mode login` piden la clave de cuenta (anticipado) | Los comandos de datos usan Shared Key por defecto y la cuenta lo tiene apagado | Usar `--auth-mode login` con un rol de datos, o las variantes ARM (`container-rm`, `blob-service-properties`) |
| La Function no arranca: "Unable to access the deployment storage" (anticipado) | Faltan los roles, todavía no propagaron (hasta 10 minutos) o el destino del paquete apunta a otro contenedor | Guía 08; esperar propagación; revisar `functionAppConfig.deployment.storage.value` |
| `QueueTrigger` no dispara y el host registra 403 (anticipado) | Falta Storage Queue Data Contributor, o `AzureWebJobsStorage__clientId` tiene el principal id | Rol en guía 08; usar `MI_CLIENT_ID` |
| `StorageAccountAlreadyTaken` (anticipado) | El nombre de cuenta es global en Azure | Otro `SUFIJO` |
| `RoleAssignmentExists` al correr el Bicep sobre roles creados a mano (anticipado) | El Bicep nombra las asignaciones con `guid(…)` determinístico y a mano se crearon con otro nombre para el mismo rol, principal y alcance | Borrar las manuales antes de pasar a Bicep, o no mezclar los dos caminos en el mismo grupo |
