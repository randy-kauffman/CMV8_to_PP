# 08. Asignaciones de rol

> Rol que lo ejecuta: Cloud, con **Owner** (o Role Based Access Control Administrator) sobre el grupo. Depende de: 04 (principal), 05, 06, 07 y 09 (alcances; `KV_NAME` sale de la 09). Lo necesitan: 10 (la Function no arranca sin los roles del Storage host), 15 y 16 (el broker necesita la clave de delegación), 17 (referencias de Key Vault) y la telemetría de la 05. Costo: sin costo propio.

## Qué es y qué hace en esta solución

Son las asignaciones RBAC de **mínimo privilegio** que convierten a la identidad administrada en "la Function" para cada recurso, más una para la persona que carga los secretos. Ningún usuario de negocio recibe roles de Azure: los usuarios solo obtienen una SAS puntual a través del broker, y Dataverse decide si les corresponde.

Dos decisiones de alcance son el núcleo del modelo de ADR-002:

- **Storage Blob Data Contributor va al CONTENEDOR `cmv8-ingesta`, no a la cuenta.** Una SAS de delegación de usuario solo puede abrir lo que puede abrir la identidad que pidió la clave; con el rol limitado al contenedor, ninguna SAS que emita el broker puede tocar otro contenedor de la cuenta (por ejemplo, el de otra solución del Banco).
- **Storage Blob Delegator va a la CUENTA.** La operación *Get User Delegation Key* actúa a nivel de cuenta (`Microsoft.Storage/storageAccounts/blobServices/generateUserDelegationKey`), así que el permiso para pedirla tiene que estar en la cuenta, el grupo o la suscripción. Un Delegator en el contenedor no sirve. Delegator solo permite pedir la clave; no da acceso a datos.

## ¿Imprescindible?

**Veredicto: Imprescindible** (todas)

- Qué aporta: sin secretos, cada componente accede exactamente a lo que necesita.
- Qué pasa si falta alguna:
  - Roles del Storage host: el runtime no lee el paquete de despliegue ni `AzureWebJobsStorage`; la Function no arranca y la cola no funciona.
  - Blob Delegator: el broker no obtiene la clave de delegación; no hay SAS (403 `AuthorizationPermissionMismatch` en *Get User Delegation Key*).
  - Blob Data Contributor en el contenedor: la SAS que se firma no autoriza nada útil y el worker no puede leer ni borrar el blob.
  - Key Vault Secrets User: las referencias `@Microsoft.KeyVault(...)` quedan sin resolver; la Function no tiene contraseñas de AS400 ni de CMIS.
  - Monitoring Metrics Publisher: con `DisableLocalAuth = true`, no llega telemetría a Application Insights.
  - Key Vault Secrets Officer: nadie puede cargar los secretos (guía 17); es la única que puede omitirse si los carga alguien que ya tiene ese rol por herencia.
- Qué decisión lo haría distinto: asignar a nivel de grupo de recursos en lugar de cada recurso. Menos asignaciones, pero rompe el mínimo privilegio (Contributor de blobs sobre todas las cuentas del grupo). No se recomienda.
- Alternativa y su tradeoff: roles personalizados más finos (por ejemplo, sin `delete` en el contenedor). Más control, más mantenimiento; el worker necesita borrar, así que la ganancia es mínima.

## Tabla de asignaciones (todas las del Bicep)

| # | Recurso Bicep | Alcance exacto | Rol | GUID de definición | Principal | Para qué |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `rolHostOwner` | Cuenta `cmv8host<sufijo>` | Storage Blob Data Owner | `b7e6dc6d-f1e8-4753-8033-0f276bb0955b` | Identidad (ServicePrincipal) | Paquete de despliegue, claves del host, leases de timers |
| 2 | `rolHostQueue` | Cuenta `cmv8host<sufijo>` | Storage Queue Data Contributor | `974c5e8b-45b9-4653-ba55-5f855dd0fb88` | Identidad | Cola `ingesta-pendiente` (`@QueueOutput` y `@QueueTrigger`) |
| 3 | `rolHostTable` | Cuenta `cmv8host<sufijo>` | Storage Table Data Contributor | `0a9a7e1f-b9d0-4cc4-a60d-0319b160aaa3` | Identidad | Tablas que el host pueda usar en `AzureWebJobsStorage`; se mantiene por paridad con el quickstart de Flex Consumption (este código no usa Durable Functions) |
| 4 | `rolAdjuntosContenedor` | Contenedor `cmv8adj<sufijo>/blobServices/default/containers/cmv8-ingesta` | Storage Blob Data Contributor | `ba92f5b4-2d11-453d-a403-e96b0029c9fe` | Identidad | Alcance de las SAS que firma el broker; lectura y borrado del worker |
| 5 | `rolAdjuntosDelegator` | Cuenta `cmv8adj<sufijo>` | Storage Blob Delegator | `db58b8e5-c6ad-4a2a-8342-4190687cbf4a` | Identidad | Pedir la clave de delegación de usuario (operación de cuenta) |
| 6 | `rolAppInsights` | Componente `cmv8-appi-<sufijo>` | Monitoring Metrics Publisher | `3913510d-42f4-4e42-8a64-420c390055eb` | Identidad | Ingesta de telemetría con Entra (`DisableLocalAuth = true`) |
| 7 | `rolKeyVaultFunction` | Vault `cmv8-kv-<sufijo>` | Key Vault Secrets User | `4633458b-17de-408a-b874-0445c86b69e6` | Identidad | Resolver `AS400_PASSWORD` y `CMIS_PASSWORD` (`keyVaultReferenceIdentity`) |
| 8 | `rolKeyVaultAdministrador` (solo si `oidOperadorKeyVault` no está vacío) | Vault `cmv8-kv-<sufijo>` | Key Vault Secrets Officer | `b86a8fe4-44ce-4948-aee5-eccb2c155cd7` | Persona operadora (`principalType: User`) | Cargar los secretos fuera de banda (`infra/cargar-secretos.sh`) |

Todas las asignaciones a la identidad usan su **principal id** (`MI_PRINCIPAL_ID`), nunca el client id.

## Valores del cuaderno

| Variable | Produce / consume | Cómo se obtiene |
| --- | --- | --- |
| `MI_PRINCIPAL_ID` | Consume | Guía 04 |
| `STORAGE_HOST_ID` | Consume | Guía 06 |
| `STORAGE_ADJ_ID`, `CONTENEDOR_ADJ` | Consume | Guía 07 |
| `APPI_ID` | Consume | Guía 05 |
| `KV_NAME` | Consume | Salida `keyVaultName` del Bicep (salidas del despliegue, sección 2 de `docs/guia-montaje-banco.md`) |
| `KV_ID` | Produce | `az keyvault show -g "$RG" -n "$KV_NAME" --query id -o tsv` |
| `OPERADOR_KV_OID` | Consume | `az ad signed-in-user show --query id -o tsv` de la persona que carga secretos (parámetro `oidOperadorKeyVault`) |
| `CONTENEDOR_ADJ_ID` | Produce | `${STORAGE_ADJ_ID}/blobServices/default/containers/${CONTENEDOR_ADJ}` |

```bash
KV_ID=             # /subscriptions/.../vaults/cmv8-kv-<sufijo>
OPERADOR_KV_OID=   # object id de quien carga los secretos
```

## Con Bicep

Recursos `Microsoft.Authorization/roleAssignments@2022-04-01` en `infra/main.bicep`:

- Definiciones de rol como variables, líneas 122-129 (`subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '<guid>')`).
- Storage host: `rolHostOwner` (311-319), `rolHostQueue` (321-329), `rolHostTable` (331-339).
- Storage adjuntos: `rolAdjuntosContenedor` (342-350, `scope: storageAdjuntos::blobs::ingesta`) y `rolAdjuntosDelegator` (353-361, `scope: storageAdjuntos`).
- App Insights: `rolAppInsights` (363-371).
- Key Vault: `rolKeyVaultFunction` (395-403) y `rolKeyVaultAdministrador` (406-414, condicional).
- La Function declara `dependsOn` sobre `rolHostOwner`, `rolHostQueue`, `rolHostTable` y `rolKeyVaultFunction` (líneas 477-482): no se crea hasta que esos permisos existen.

Nombre de cada asignación: `guid(<alcance>.id, <identidad>.id, <rol>)`, determinístico; redeployar no duplica. Único parámetro: `oidOperadorKeyVault` (en el Banco, el object id de quien carga los secretos; vacío si los carga alguien que ya tiene el rol heredado).

## Con Azure CLI

```bash
source ~/cmv8-$AMBIENTE.env
export PYTHONWARNINGS=ignore
CONTENEDOR_ADJ_ID="${STORAGE_ADJ_ID}/blobServices/default/containers/${CONTENEDOR_ADJ:-cmv8-ingesta}"
KV_ID=$(az keyvault show -g "$RG" -n "$KV_NAME" --query id -o tsv)

asignar() {   # $1 = GUID del rol, $2 = alcance
  az role assignment create --assignee-object-id "$MI_PRINCIPAL_ID" --assignee-principal-type ServicePrincipal \
    --role "$1" --scope "$2" -o none
}

# 1-3. Storage host
asignar b7e6dc6d-f1e8-4753-8033-0f276bb0955b "$STORAGE_HOST_ID"     # Storage Blob Data Owner
asignar 974c5e8b-45b9-4653-ba55-5f855dd0fb88 "$STORAGE_HOST_ID"     # Storage Queue Data Contributor
asignar 0a9a7e1f-b9d0-4cc4-a60d-0319b160aaa3 "$STORAGE_HOST_ID"     # Storage Table Data Contributor

# 4. Storage adjuntos: Contributor SOLO en el contenedor
asignar ba92f5b4-2d11-453d-a403-e96b0029c9fe "$CONTENEDOR_ADJ_ID"   # Storage Blob Data Contributor

# 5. Storage adjuntos: Delegator en la CUENTA (la clave de delegación es de cuenta)
asignar db58b8e5-c6ad-4a2a-8342-4190687cbf4a "$STORAGE_ADJ_ID"      # Storage Blob Delegator

# 6. Application Insights
asignar 3913510d-42f4-4e42-8a64-420c390055eb "$APPI_ID"             # Monitoring Metrics Publisher

# 7. Key Vault: la Function solo lee
asignar 4633458b-17de-408a-b874-0445c86b69e6 "$KV_ID"               # Key Vault Secrets User

# 8. Key Vault: la persona que carga los secretos (opcional)
if [ -n "${OPERADOR_KV_OID:-}" ]; then
  az role assignment create --assignee-object-id "$OPERADOR_KV_OID" --assignee-principal-type User \
    --role b86a8fe4-44ce-4948-aee5-eccb2c155cd7 --scope "$KV_ID" -o none   # Key Vault Secrets Officer
fi
```

Notas:

- `--role` acepta el GUID de definición: es inmune al idioma y a renombres del rol.
- `--assignee-object-id` + `--assignee-principal-type` evita la consulta a Graph y el error por replicación cuando la identidad es recién creada.
- Si el operador es un pipeline (service principal) y no una persona, usar `--assignee-principal-type ServicePrincipal`. El Bicep, en cambio, fija `principalType: 'User'` para esta asignación (ver "Errores que ya vimos").
- Los roles tardan hasta 10 minutos en propagarse. No reiniciar la Function ni probar el broker antes.
- Las asignaciones creadas a mano tienen nombre (GUID) aleatorio. Si después se corre el Bicep sobre el mismo grupo, falla con `RoleAssignmentExists`: o se borran las manuales antes, o no se mezclan los dos caminos.

## Con el portal

El procedimiento es el mismo para cada fila de la tabla; lo que cambia es **dónde** se abre **Access control (IAM)**:

| # | Dónde abrir **Access control (IAM)** |
| --- | --- |
| 1-3 | Cuenta `cmv8host<sufijo>` → menú de la cuenta |
| 4 | Cuenta `cmv8adj<sufijo>` → **Data storage** → **Containers** → `cmv8-ingesta` → menú del contenedor. **No** usar el IAM de la cuenta |
| 5 | Cuenta `cmv8adj<sufijo>` → menú de la cuenta |
| 6 | Componente `cmv8-appi-<sufijo>` |
| 7-8 | Vault `cmv8-kv-<sufijo>` |

Pasos para cada asignación:

1. Abrir el recurso del alcance indicado → **Access control (IAM)** → pestaña **Role assignments** para ver lo existente.
2. **Add** → **Add role assignment**. Si la opción está deshabilitada, quien opera no tiene permiso para asignar roles en ese alcance.
3. Pestaña **Role** (roles de función de trabajo, **Job function roles**): buscar el rol por nombre (por ejemplo `Storage Blob Delegator`), seleccionarlo → **Next**. **No** elegir roles privilegiados (**Privileged administrator roles**) como Owner o Contributor.
4. Pestaña **Members**:
   - Filas 1 a 7: **Assign access to** = **Managed identity** → **+ Select members** → **Managed identity** = **User-assigned managed identity** → elegir `cmv8-mi-<sufijo>` → **Select**.
   - Fila 8: **Assign access to** = **User, group, or service principal** → **+ Select members** → buscar a la persona operadora → **Select**.
   - **Description** (opcional): por ejemplo "CMV8: clave de delegación del broker".
5. **Next** hasta **Review + assign** → **Review + assign**. Si aparece una pestaña **Conditions**, dejarla sin condiciones (el Bicep no usa ABAC).
6. Repetir para cada fila. Para la fila 4, confirmar en **Role assignments** que el **Scope** dice "This resource" sobre el contenedor y no "Parent resource". (verificar en el portal el rótulo exacto de la columna de alcance)

## Verificación

```bash
az role assignment list --assignee-object-id "$MI_PRINCIPAL_ID" --all \
  --query "[].[roleDefinitionName, roleDefinitionId, scope]" -o tsv \
| python3 -c '
import sys
for linea in sys.stdin:
    rol, definicion, alcance = linea.rstrip("\n").split("\t")
    print(f"{rol:34} {definicion.split("/")[-1]}  {alcance.split("/providers/")[-1]}")
'
```

Salida esperada (7 filas; el orden puede variar):

```text
Storage Blob Data Owner            b7e6dc6d-f1e8-4753-8033-0f276bb0955b  Microsoft.Storage/storageAccounts/cmv8host<sufijo>
Storage Queue Data Contributor     974c5e8b-45b9-4653-ba55-5f855dd0fb88  Microsoft.Storage/storageAccounts/cmv8host<sufijo>
Storage Table Data Contributor     0a9a7e1f-b9d0-4cc4-a60d-0319b160aaa3  Microsoft.Storage/storageAccounts/cmv8host<sufijo>
Storage Blob Data Contributor      ba92f5b4-2d11-453d-a403-e96b0029c9fe  Microsoft.Storage/storageAccounts/cmv8adj<sufijo>/blobServices/default/containers/cmv8-ingesta
Storage Blob Delegator             db58b8e5-c6ad-4a2a-8342-4190687cbf4a  Microsoft.Storage/storageAccounts/cmv8adj<sufijo>
Monitoring Metrics Publisher       3913510d-42f4-4e42-8a64-420c390055eb  Microsoft.Insights/components/cmv8-appi-<sufijo>
Key Vault Secrets User             4633458b-17de-408a-b874-0445c86b69e6  Microsoft.KeyVault/vaults/cmv8-kv-<sufijo>
```

Señales de error en esa salida: Blob Data Contributor con alcance en la **cuenta** de adjuntos (sobra privilegio); cualquier rol sobre el **grupo de recursos**; más de 7 filas.

```bash
# Fila 8
az role assignment list --scope "$KV_ID" --assignee-object-id "$OPERADOR_KV_OID" --query '[].roleDefinitionName' -o tsv
# Key Vault Secrets Officer
```

Prueba funcional: después de publicar el código y configurar Easy Auth (guías 15 y 16), `/api/salud` responde 200 (roles del host) y las referencias de Key Vault están en `Resolved` (guía 17).

En el portal: la identidad `cmv8-mi-<sufijo>` → **Azure role assignments** muestra las siete; o cada recurso → **Access control (IAM)** → **Role assignments**.

## Errores que ya vimos

| Síntoma | Causa | Arreglo |
| --- | --- | --- |
| La identidad tiene en el sandbox una octava asignación: Storage Blob Data Contributor sobre la cuenta host | Agregada a mano durante los spikes; no está en el Bicep | Redundante con Blob Data Owner. En el Banco no crearla |
| Referencias de Key Vault en `UnauthorizedClient` / `AccessToKeyVaultDenied` | Key Vault Secrets User todavía no propagó, o vault cerrado sin private endpoint | Esperar hasta 10 minutos y `az functionapp restart`; revisar `keyVaultAccesoPublico` |
| 403 `AuthorizationPermissionMismatch` al usar `saoid`/`suoid` en la SAS | Storage exige Storage Blob Data Owner (`runAsSuperUser`) para aceptar esos campos | No usarlos (descartados en ADR-002); la auditoría va por `scid`, `RequesterUpn` y App Insights |
| El broker no obtiene la clave de delegación (anticipado) | Blob Delegator asignado al contenedor en lugar de la cuenta | Reasignar a la cuenta (fila 5) |
| Las SAS funcionan sobre otros contenedores de la cuenta (anticipado) | Blob Data Contributor asignado a la cuenta en lugar del contenedor | Borrar esa asignación y crear la de la fila 4 |
| `RoleAssignmentExists` al desplegar el Bicep (anticipado) | Asignaciones creadas a mano con otro nombre para el mismo rol, principal y alcance | Borrar las manuales (`az role assignment delete --ids …`) y redeployar |
| `UnmatchedPrincipalType` en `rolKeyVaultAdministrador` (anticipado) | `oidOperadorKeyVault` es un service principal (pipeline) y el Bicep fija `principalType: 'User'` | Usar el object id de una persona, o ajustar el Bicep para recibir el tipo como parámetro |
| `AuthorizationFailed` al crear asignaciones (anticipado) | Quien opera es Contributor, no Owner | Owner o Role Based Access Control Administrator sobre el grupo (guía 03) |

> **v2 (ADR-003)**: a las asignaciones de esta guía se suman Blob Data Contributor sobre `cmv8-almacenados` (o Blob Data Owner acotado a ese contenedor con `wormHabilitado`) y Table Data Contributor acotado a las tablas `ingesta` y `depuracion`. Los comandos están en la guía 07.
