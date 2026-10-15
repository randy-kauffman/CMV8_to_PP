# 09. Key Vault

> Rol que lo ejecuta: Cloud (creación y roles) y el operador de secretos (carga de valores). Depende de: 03 a 08 (grupo de recursos e identidad administrada asignada por el usuario: `MI_PRINCIPAL_ID`, `MI_ID`). Lo necesitan: 10 (las referencias `@Microsoft.KeyVault(...)` de `AS400_PASSWORD` y `CMIS_PASSWORD` y `keyVaultReferenceIdentity`), 16 (la Function arranca con las referencias resueltas). Costo: menos de 1 USD/mes (operaciones de secretos, SKU standard; `docs/costos-estimados.md` §2).

## Qué es y qué hace en esta solución

Un Azure Key Vault con autorización RBAC (sin access policies) que guarda las dos únicas contraseñas de la solución: la del perfil de servicio del IBM i (`as400-password`) y la del usuario de servicio de Content Manager (`cmis-password`). La Function no recibe los valores: sus app settings `AS400_PASSWORD` y `CMIS_PASSWORD` contienen una **referencia** del tipo `@Microsoft.KeyVault(VaultName=<vault>;SecretName=as400-password)` que la plataforma App Service resuelve al arrancar, con la identidad administrada asignada por el usuario (`keyVaultReferenceIdentity`). El código Java lee una variable de entorno común y corriente y nunca sabe que vino de Key Vault.

Tres piezas lo componen:

1. El vault: SKU standard, `enableRbacAuthorization=true`, soft delete de 7 días, acceso de red público según `keyVaultAccesoPublico` y excepción para servicios de confianza de Azure (`bypass: AzureServices`).
2. Dos asignaciones de rol: **Key Vault Secrets User** para la identidad de la Function (solo lectura de valores) y **Key Vault Secrets Officer** para la persona o pipeline que carga los secretos (escritura, sin poder administrar el vault).
3. Los secretos, que se cargan fuera de banda con `infra/cargar-secretos.sh`. Nunca pasan por el Bicep, por un `.bicepparam`, por el repo ni por el chat (hallazgo 6b).

Las referencias van **sin versión** a propósito: App Service toma la última versión habilitada del secreto. Rotar una contraseña es cargar una versión nueva y reiniciar la Function (o esperar: App Service vuelve a leer las referencias cada 24 horas). No hay redeploy ni cambio de configuración.

## ¿Imprescindible?

**Veredicto: Prescindible por decisión**

- Qué aporta: ningún secreto queda en el Bicep, en los parámetros, en el repo ni en el historial de despliegues; la rotación no requiere redeploy (referencia sin versión); el acceso a los valores queda auditado (logs de diagnóstico del vault) y separado por rol: quien carga no es quien lee, y quien administra la Function no ve el valor.
- Qué pasa si no está: las contraseñas tendrían que ir como app settings con su valor. Siguen cifradas en reposo, pero cualquiera con permiso de lectura de la configuración de la Function (rol Contributor o Website Contributor sobre el sitio, o `Microsoft.Web/sites/config/list/action`) las ve en claro en el portal o con `az functionapp config appsettings list`, y quedan en el historial de despliegues de ARM si se pasan como parámetro.
- Qué decisión lo haría prescindible (o distinto): que el Banco ya tenga un vault central para secretos de aplicaciones. En ese caso **no se crea** este vault: se le da a la identidad de la Function el rol Key Vault Secrets User sobre el vault del Banco (o sobre los dos secretos puntuales) y las referencias apuntan a `VaultName=<vault del Banco>`. Requiere ajustar `nombreKeyVault` en el Bicep (hoy se deriva del prefijo) o fijar las referencias a mano (ver "Con Azure CLI").
- Alternativa y su tradeoff: **contraseñas como app settings**. Es más simple (un recurso menos, sin roles, sin propagación) pero el secreto lo ve cualquiera con lectura de configuración, rotar exige tocar la configuración del sitio (y por lo tanto un despliegue o un cambio manual que se desalinea del Bicep), y no hay registro de quién leyó el valor. Se eligió Key Vault por el hallazgo 6b: rotación sin redeploy, ningún secreto en Bicep/parámetros/repo y auditoría de acceso.

## Valores del cuaderno

| Variable | Produce / consume | Cómo se obtiene |
| --- | --- | --- |
| `RG`, `REGION`, `TENANT_ID` | Consume | Cuaderno (guía 0) |
| `SUB` | Produce (local) | `az account show --query id -o tsv` |
| `PREFIJO`, `SUFIJO` | Consume | Convención de nombres: `PREFIJO=cmv8`, `SUFIJO` = 8 minúsculas (el Bicep usa `take(uniqueString(...), 8)`; a mano se elige una vez y se reutiliza en todas las guías) |
| `KV_NAME` | Produce | Bicep: salida `keyVaultName`. A mano: `${PREFIJO}-kv-${SUFIJO}` |
| `KV_ID` | Produce | `az keyvault show -g "$RG" -n "$KV_NAME" --query id -o tsv` |
| `MI_PRINCIPAL_ID` | Consume | Salida `identidadPrincipalId` (guías 03 a 08) |
| `MI_ID` | Consume | Resource id de la identidad: `az identity show -g "$RG" -n "${PREFIJO}-mi-${SUFIJO}" --query id -o tsv` |
| `OPERADOR_OID` | Consume | Object id de quien carga los secretos: `az ad signed-in-user show --query id -o tsv` (es el parámetro `oidOperadorKeyVault`) |
| `FUNCTION_NAME` | Consume (verificación) | Guía 10 |

## Con Bicep

Está en `infra/main.bicep`, sección "Key Vault" (recurso `keyVault`, líneas 376 a 392, API `Microsoft.KeyVault/vaults@2024-11-01`), más dos asignaciones de rol (`rolKeyVaultFunction`, líneas 395 a 403, y `rolKeyVaultAdministrador`, líneas 406 a 414, condicional). Las referencias se arman en las variables `ajustesAs400` y `ajustesCmis` (líneas 132 a 146) y `keyVaultReferenceIdentity` se fija en la Function (línea 446). La Function depende de `rolKeyVaultFunction` (`dependsOn`, línea 481) para que el rol exista antes de que el sitio intente resolver las referencias.

| Parámetro | Qué gobierna | Valor recomendado en el Banco |
| --- | --- | --- |
| `prefijo` | Nombre `${prefijo}-kv-<sufijo>` (Key Vault admite 3 a 24 caracteres; con prefijo de 8 son 20) | `cmv8` |
| `keyVaultAccesoPublico` | `publicNetworkAccess` (`Enabled`/`Disabled`) y `networkAcls.defaultAction` (`Allow`/`Deny`) | `true` mientras la Function no tenga VNet; `false` **con private endpoint** cuando exista la VNet (S0). Ver nota abajo |
| `oidOperadorKeyVault` | Si no está vacío, crea la asignación Key Vault Secrets Officer para ese object id con `principalType: 'User'` | Object id de la persona que carga los secretos. Si lo hace un pipeline, hay que cambiar `principalType` a `ServicePrincipal` en el Bicep |
| `as400Host` / `cmisUrl` | Si están vacíos, no se crean los app settings de AS400 / CMIS, ni sus referencias | Valores reales (guía 10) |

Lo que el Bicep fija y no es parámetro: SKU `standard` (familia `A`), `enableRbacAuthorization: true`, `enableSoftDelete: true`, `softDeleteRetentionInDays: 7`, `networkAcls.bypass: 'AzureServices'`, etiqueta `proyecto: CMV8_to_PP`. **No** habilita purge protection: un vault borrado se puede purgar antes de los 7 días. En el Banco conviene evaluar `enablePurgeProtection: true` (es irreversible: una vez activado no se puede desactivar).

**Pendiente en el Bicep: private endpoint.** Con `keyVaultAccesoPublico=false` el Bicep cierra el acceso público pero **no crea** el private endpoint, la zona DNS privada `privatelink.vaultcore.azure.net` ni la integración de VNet de la Function. Desplegar `false` sin esas tres piezas deja las referencias en error (`AccessToKeyVaultDenied`) y la Function arranca sin contraseñas. Se agrega como módulo opcional cuando se resuelva S0 (`infra/README.md`, "Qué NO está en este Bicep todavía").

Los secretos no los crea el Bicep. Después del despliegue (y antes o después de publicar el código, da igual) el operador corre `infra/cargar-secretos.sh` (ver "Con Azure CLI", paso 4).

## Con Azure CLI

Equivale a lo que hace el Bicep. Verificado con `az --help` (az CLI 2.91).

### 1. Crear el vault

```bash
source ~/cmv8-$AMBIENTE.env
SUB=$(az account show --query id -o tsv)
KV_NAME="${PREFIJO}-kv-${SUFIJO}"
KV_PUBLICO=Enabled   # Disabled en el Banco SOLO cuando exista el private endpoint

az keyvault create \
  --resource-group "$RG" \
  --name "$KV_NAME" \
  --location "$REGION" \
  --sku standard \
  --enable-rbac-authorization true \
  --retention-days 7 \
  --public-network-access "$KV_PUBLICO" \
  --bypass AzureServices \
  --default-action "$([ "$KV_PUBLICO" = Enabled ] && echo Allow || echo Deny)" \
  --tags proyecto=CMV8_to_PP \
  -o none

KV_ID=$(az keyvault show -g "$RG" -n "$KV_NAME" --query id -o tsv)
```

Notas:

- `--enable-rbac-authorization` ya es `true` por defecto en az 2.91 (lo dice `az keyvault create --help`); se escribe explícito para que el comando documente la intención.
- `--retention-days` por defecto es 90; el Bicep usa 7. El valor solo se puede fijar al crear.
- Soft delete ya no se puede desactivar: no hay flag, siempre queda habilitado.
- Si en el tenant existe un vault borrado con el mismo nombre en estado soft-deleted, el create falla. Ver "Errores que ya vimos".

### 2. Roles

```bash
# la identidad de la Function solo LEE secretos
az role assignment create \
  --assignee-object-id "$MI_PRINCIPAL_ID" \
  --assignee-principal-type ServicePrincipal \
  --role "Key Vault Secrets User" \
  --scope "$KV_ID" -o none

# el operador CARGA secretos (no administra el vault)
OPERADOR_OID=$(az ad signed-in-user show --query id -o tsv)   # o el object id de la persona designada
az role assignment create \
  --assignee-object-id "$OPERADOR_OID" \
  --assignee-principal-type User \
  --role "Key Vault Secrets Officer" \
  --scope "$KV_ID" -o none
```

`--assignee-object-id` con `--assignee-principal-type` evita que la CLI consulte Graph para resolver el principal (falla con identidades recién creadas que todavía no replicaron). Los ids de los roles son los mismos del Bicep: Secrets User `4633458b-17de-408a-b874-0445c86b69e6`, Secrets Officer `b86a8fe4-44ce-4948-aee5-eccb2c155cd7`.

**Vault central del Banco** (alternativa): en vez de los pasos 1 y 2, una sola asignación sobre el vault existente, idealmente con alcance en cada secreto y no en el vault:

```bash
KV_NAME="<vault del Banco>"
KV_ID=$(az keyvault show -n "$KV_NAME" --query id -o tsv)
for s in as400-password cmis-password; do
  az role assignment create --assignee-object-id "$MI_PRINCIPAL_ID" --assignee-principal-type ServicePrincipal \
    --role "Key Vault Secrets User" --scope "$KV_ID/secrets/$s" -o none
done
```

### 3. Referencias en la Function

Se fijan en la guía 10 junto con el resto de los app settings. Lo relevante para el vault:

```bash
az functionapp config appsettings set -g "$RG" -n "$FUNCTION_NAME" -o none --settings \
  "AS400_PASSWORD=@Microsoft.KeyVault(VaultName=$KV_NAME;SecretName=as400-password)" \
  "CMIS_PASSWORD=@Microsoft.KeyVault(VaultName=$KV_NAME;SecretName=cmis-password)"

# la plataforma resuelve las referencias con la identidad asignada por el usuario, no con una del sistema
az resource update --ids "/subscriptions/$SUB/resourceGroups/$RG/providers/Microsoft.Web/sites/$FUNCTION_NAME" \
  --set properties.keyVaultReferenceIdentity="$MI_ID" -o none
```

Sin `keyVaultReferenceIdentity` la plataforma intenta resolver con la identidad asignada por el sistema, que esta Function no tiene, y las referencias quedan en error aunque el rol esté bien asignado.

### 4. Cargar los secretos

Lo hace quien tiene Key Vault Secrets Officer, desde una estación autorizada. `infra/cargar-secretos.sh`:

1. Busca en `$RG` el vault cuyo nombre empieza con `cmv8-kv-` (con `az keyvault list`); si no hay, termina con error.
2. Lee `AS400_PASSWORD` y `CMIS_PASSWORD` del bloque `Values` de un archivo con el formato de `function/local.settings.json` (por defecto ese mismo archivo, que está en `.gitignore`; se puede pasar otro como segundo argumento).
3. Por cada uno con valor, ejecuta `az keyvault secret set` con nombre `as400-password` / `cmis-password`, descarta la salida y solo imprime "cargado en <vault>". Si la clave falta, avisa y sigue. Nunca imprime el valor.
4. Recuerda reiniciar la Function.

```bash
# archivo FUERA del repo, permisos 600, solo con las dos claves (formato en docs/guia-montaje-banco.md §6)
bash infra/cargar-secretos.sh "$RG" ~/cmv8-$AMBIENTE-secretos.json
rm ~/cmv8-$AMBIENTE-secretos.json
az functionapp restart -g "$RG" -n "$FUNCTION_NAME"
```

Limitación del script: busca el vault por el prefijo literal `cmv8-kv-`. Con otro prefijo o con el vault central del Banco no lo encuentra; en ese caso se usa `az keyvault secret set --vault-name "$KV_NAME" --name as400-password --file <archivo con el valor>` (con `--file` el valor no queda en el historial del shell).

**Rotación**: cargar la versión nueva con el mismo nombre y forzar la relectura, sin reiniciar:

```bash
az rest --method POST \
  --url "https://management.azure.com/subscriptions/$SUB/resourceGroups/$RG/providers/Microsoft.Web/sites/$FUNCTION_NAME/config/configreferences/appsettings/refresh?api-version=2022-03-01"
```

O `az functionapp restart`. Sin ninguna de las dos, App Service toma la versión nueva sola dentro de las 24 horas.

## Con el portal

1. **Create a resource** → buscar **Key Vault** → **Create**.
2. Pestaña **Basics**:
   - **Subscription** y **Resource group**: los del ambiente (`$RG`).
   - **Key vault name**: `cmv8-kv-<sufijo>`.
   - **Region**: la del ambiente.
   - **Pricing tier**: **Standard** (default).
   - **Days to retain deleted vaults**: **7** (CAMBIAR: el default es 90).
   - **Purge protection**: dejar **Disable purge protection** salvo que el Banco lo exija (irreversible).
3. Pestaña **Access configuration**: **Permission model** = **Azure role-based access control** (en versiones recientes es el default; verificar que no esté en **Vault access policy**).
4. Pestaña **Networking**:
   - **Enable public access**: activado mientras `keyVaultAccesoPublico=true`. En el Banco con private endpoint: desactivado y crear el private endpoint en la misma pestaña (verificar en el portal).
   - **Allow trusted Microsoft services to bypass this firewall**: marcado.
5. **Tags**: `proyecto = CMV8_to_PP`. **Review + create** → **Create**.
6. En el vault creado → **Access control (IAM)** → **Add** → **Add role assignment**:
   - Rol **Key Vault Secrets User** → **Assign access to**: **Managed identity** → **Select members** → la identidad `cmv8-mi-<sufijo>` → **Review + assign**.
   - Repetir con **Key Vault Secrets Officer** → **Assign access to**: **User, group, or service principal** → la persona que carga los secretos.
7. Secretos (con el rol Secrets Officer): **Objects** → **Secrets** → **Generate/Import** → **Name** `as400-password`, **Secret value** pegado desde el gestor de contraseñas del Banco → **Create**. Repetir con `cmis-password`. Preferir el script: el portal obliga a pasar el valor por el portapapeles.
8. Referencias en la Function: en la Function → **Settings** → **Environment variables** → **App settings** → editar `AS400_PASSWORD` y `CMIS_PASSWORD` con el texto `@Microsoft.KeyVault(VaultName=cmv8-kv-<sufijo>;SecretName=...)` → **Apply** → **Confirm**. La columna **Source** pasa a mostrar **Key vault Reference** con un indicador de estado (verificar en el portal).
9. `keyVaultReferenceIdentity`: no encontramos una opción documentada en el portal para elegir la identidad con la que se resuelven las referencias (verificar en el portal). Fijarla con el comando `az resource update` del paso 3 de "Con Azure CLI".

## Verificación

```bash
# propiedades del vault
az keyvault show -g "$RG" -n "$KV_NAME" \
  --query '{rbac:properties.enableRbacAuthorization, soft:properties.enableSoftDelete, dias:properties.softDeleteRetentionInDays, publico:properties.publicNetworkAccess, sku:properties.sku.name}'
# esperado: true, true, 7, Enabled (o Disabled en el Banco con PE), standard

# roles sobre el vault
az role assignment list --scope "$KV_ID" --query '[].{rol:roleDefinitionName, tipo:principalType}' -o table
# esperado: Key Vault Secrets User / ServicePrincipal y Key Vault Secrets Officer / User

# secretos presentes (solo nombres)
az keyvault secret list --vault-name "$KV_NAME" --query '[].{nombre:name, habilitado:attributes.enabled}' -o table

# estado de resolución de las referencias en la Function
az rest --method GET \
  --url "https://management.azure.com/subscriptions/$SUB/resourceGroups/$RG/providers/Microsoft.Web/sites/$FUNCTION_NAME/config/configreferences/appsettings?api-version=2023-12-01" \
  --query 'value[].{setting:name, estado:properties.status, identidad:properties.identityType}' -o table
# esperado: AS400_PASSWORD y CMIS_PASSWORD en Resolved con identidad UserAssigned
```

La respuesta de `configreferences/appsettings` lista cada referencia dos veces: con su nombre y con el prefijo `APPSETTING_` (visto en el sandbox). Es normal.

Estado real del sandbox (`cmv8-kv-supfcopn`, 2026-10-14): RBAC, soft delete 7 días, standard, público; un solo secreto (`as400-password`) porque el spike no configura CMIS (`cmisUrl` vacío); `AS400_PASSWORD` en `Resolved` con `UserAssigned`. `az keyvault show` devuelve `networkAcls: null` aunque el Bicep fija `bypass: AzureServices` con `defaultAction: Allow`: con acceso abierto y sin reglas, el recurso no las conserva. No es un error.

## Errores que ya vimos

| Síntoma | Causa | Arreglo |
| --- | --- | --- |
| Referencia en `AccessToKeyVaultDenied` o `UnauthorizedClient` justo después del despliegue | El rol Key Vault Secrets User todavía no propagó (hasta 10 minutos) | Esperar y `az functionapp restart`, o el POST a `configreferences/appsettings/refresh` |
| Referencia en `AccessToKeyVaultDenied` con el rol bien asignado | `keyVaultAccesoPublico=false` sin private endpoint ni VNet en la Function | Volver a `true` hasta que exista S0, o crear PE + zona DNS + integración de VNet |
| Referencia en error con identidad `SystemAssigned` | Falta `keyVaultReferenceIdentity` (la Function no tiene identidad del sistema) | `az resource update ... --set properties.keyVaultReferenceIdentity="$MI_ID"` |
| `SecretNotFound` en la referencia de CMIS | Se configuró `cmisUrl` pero nunca se cargó `cmis-password` (en el sandbox solo existe `as400-password`) | Correr `infra/cargar-secretos.sh` con `CMIS_PASSWORD` en el archivo |
| `cargar-secretos.sh` responde "No hay Key Vault cmv8-kv-* en ..." | El vault tiene otro prefijo o es el vault central del Banco | Cargar con `az keyvault secret set --vault-name ... --file ...` |
| `az keyvault secret set` con `Forbidden` (anticipado) | Quien carga no tiene Key Vault Secrets Officer (Owner del grupo NO alcanza en un vault RBAC: es un rol de plano de control) | Asignar Secrets Officer (parámetro `oidOperadorKeyVault`) |
| `VaultAlreadyExists` o conflicto de nombre al recrear el ambiente (anticipado) | Hay un vault soft-deleted con el mismo nombre | `az keyvault list-deleted` y `az keyvault recover`, o `az keyvault purge` si no hay purge protection |
