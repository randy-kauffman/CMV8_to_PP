# 04. Identidad administrada

> Rol que lo ejecuta: Cloud. Depende de: 03. Lo necesitan: 05 (rol Monitoring Metrics Publisher), 06 y 07 (roles de datos), 08 (todas las asignaciones), 10 (Function App), 14 (credencial federada del broker) y 18 (usuario de aplicación de Dataverse). Costo: sin costo propio.

## Qué es y qué hace en esta solución

Es una identidad administrada **asignada por el usuario** (`cmv8-mi-<sufijo>`), una por solución y por ambiente. Es "quién es" la Function frente a todo lo demás, sin ningún secreto:

- **Storage host**: el runtime de Functions la usa para leer el paquete de despliegue y para la cola `ingesta-pendiente` y los timers (`AzureWebJobsStorage__clientId`).
- **Storage adjuntos**: con ella el broker pide la clave de delegación de usuario y firma la SAS; con ella el worker lee y borra el blob después de archivarlo en Content Manager.
- **Broker (OBO)**: su **principal id** es el `subject` de la credencial federada de la app registration del broker; así el intercambio On-Behalf-Of hacia Azure Storage se firma sin client secret.
- **Dataverse**: su **client id** es el usuario de aplicación (`APP_CLIENT_ID` en `dataverse/crear-roles.sh`) con el rol CMV8 Worker.
- **Key Vault** y **Application Insights**: resuelve las referencias `@Microsoft.KeyVault(...)` y envía telemetría con autenticación Entra (la autenticación local de App Insights está apagada).

## ¿Imprescindible?

**Veredicto: Imprescindible** (para este diseño)

- Qué aporta: una sola identidad sin secretos para los cinco destinos (dos Storage, Key Vault, App Insights, Dataverse) más la confianza federada del broker. Al ser un recurso independiente, su principal id y su client id **no cambian** aunque se borre y recree la Function App.
- Qué pasa si no está: la Function no arranca (no puede leer su paquete ni `AzureWebJobsStorage`, porque Shared Key está apagado), el broker no puede firmar SAS, el OBO no tiene credencial y Dataverse no tiene a quién autorizar.
- Qué decisión lo haría prescindible: usar la identidad **asignada por el sistema** de la Function App. Tradeoff (ver "Decisiones embebidas" en `infra/README.md`):
  - **Dependencia circular en el despliegue**: la identidad del sistema nace con el sitio, pero el sitio necesita los roles sobre el Storage host (paquete de despliegue) para arrancar. Con la asignada por el usuario, los roles se crean ANTES del sitio (`dependsOn` en `main.bicep`, líneas 477-482). Es la misma elección del quickstart oficial de Flex Consumption.
  - **El principal id cambia si se recrea la Function**: rompe la credencial federada del broker (`AADSTS700211`/`AADSTS70021` en el OBO) y deja huérfano el usuario de aplicación de Dataverse (502 del broker). Hay que rehacer los guías 14 y 18 y todas las asignaciones de la guía 08.
- Alternativa y su tradeoff: un service principal con certificado o secreto. Agrega un secreto a rotar y a custodiar, que es exactamente lo que el diseño eliminó (hallazgo 6b y nota de ADR-002 "Estado tras los spikes"). No se recomienda.

## Valores del cuaderno

| Variable | Produce / consume | Cómo se obtiene |
| --- | --- | --- |
| `RG`, `REGION`, `PREFIJO`, `SUFIJO` | Consume | Guía 03 |
| `MI_NAME` | Produce | `${PREFIJO}-mi-${SUFIJO}` (a mano) o el nombre que creó el Bicep |
| `MI_ID` | Produce | `az identity show -g "$RG" -n "$MI_NAME" --query id -o tsv` (resource id; lo usan la Function y el Bicep) |
| `MI_CLIENT_ID` | Produce | `az identity show … --query clientId -o tsv`. Va a `AZURE_CLIENT_ID`, `AzureWebJobsStorage__clientId`, `APPLICATIONINSIGHTS_AUTHENTICATION_STRING` y al usuario de aplicación de Dataverse |
| `MI_PRINCIPAL_ID` | Produce | `az identity show … --query principalId -o tsv`. Es el **object id** del service principal: lo usan TODAS las asignaciones de rol (guía 08) y el `subject` de la credencial federada |

```bash
MI_NAME=         # cmv8-mi-<sufijo>
MI_ID=           # /subscriptions/.../userAssignedIdentities/cmv8-mi-<sufijo>
```

Regla para no confundirse: **client id → quien "se presenta"** (app settings, Dataverse); **principal id → a quien "se le da permiso"** (RBAC, credencial federada).

## Con Bicep

- Recurso `identidad` (`Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31`), líneas 176-179. Sin propiedades: solo nombre y región.
- Nombre: variable `nombreIdentidad` (línea 114) = `${prefijo}-mi-${take(token, 8)}`.
- Parámetros que lo gobiernan: solo `prefijo` (valor en el Banco: `cmv8`) y `location`.
- Dónde se usa: asignaciones de rol (líneas 311-414, `principalId`), `identity.userAssignedIdentities` de la Function (líneas 436-441), `keyVaultReferenceIdentity` (línea 446), autenticación del paquete de despliegue (línea 463) y app settings (líneas 490-496).
- Salidas: `identidadClientId` e `identidadPrincipalId` → `MI_CLIENT_ID` y `MI_PRINCIPAL_ID` del cuaderno (salidas del despliegue, sección 2 de `docs/guia-montaje-banco.md`).

## Con Azure CLI

```bash
source ~/cmv8-$AMBIENTE.env
export PYTHONWARNINGS=ignore
MI_NAME="${PREFIJO}-mi-${SUFIJO}"

az identity create --resource-group "$RG" --name "$MI_NAME" --location "$REGION" -o none

MI_ID=$(az identity show -g "$RG" -n "$MI_NAME" --query id -o tsv)
MI_CLIENT_ID=$(az identity show -g "$RG" -n "$MI_NAME" --query clientId -o tsv)
MI_PRINCIPAL_ID=$(az identity show -g "$RG" -n "$MI_NAME" --query principalId -o tsv)
printf 'MI_NAME=%s\nMI_ID=%s\nMI_CLIENT_ID=%s\nMI_PRINCIPAL_ID=%s\n' "$MI_NAME" "$MI_ID" "$MI_CLIENT_ID" "$MI_PRINCIPAL_ID"
```

Copiar las cuatro líneas al cuaderno. La identidad tarda unos segundos en replicarse en Entra; si una asignación de rol inmediata falla con "principal not found", usar `--assignee-object-id` con `--assignee-principal-type ServicePrincipal` (así lo hace la guía 08) o esperar un minuto.

El Bicep no fija `--isolation-scope` ni etiquetas: no hace falta pasarlos.

## Con el portal

1. Buscar **Managed Identities** en la barra superior → **Create** (o **+ Create**).
2. Pestaña **Basics**:
   - **Subscription**: la del Banco.
   - **Resource group**: `rg-cmv8-<ambiente>`.
   - **Region**: la misma del grupo.
   - **Isolation scope**: dejar el valor por defecto (el Bicep no lo fija). Si el Banco elige **Regional**, la identidad solo podrá asignarse a recursos de esa región, lo que acá no cambia nada porque todo vive en la misma. Una vez creada, el portal no permite cambiarlo. (verificar en el portal: la opción puede no aparecer en todos los tenants)
   - **Name**: `cmv8-mi-<sufijo>`.
3. **Review + create** → **Create** → **Go to resource**.
4. En **Overview**, copiar **Client ID** → `MI_CLIENT_ID` y **Object (principal) ID** → `MI_PRINCIPAL_ID`. En **Properties** (o **JSON View** desde Overview) copiar el **Resource ID** → `MI_ID`. (verificar en el portal el rótulo exacto del principal id)

## Verificación

```bash
az identity show -g "$RG" -n "$MI_NAME" --query '{clientId:clientId, principalId:principalId, region:location}' -o json
az ad sp show --id "$MI_PRINCIPAL_ID" --query '{tipo:servicePrincipalType, appId:appId, nombre:displayName}' -o json
```

Esperado: el segundo comando devuelve `"tipo": "ManagedIdentity"`, `appId` **igual** a `MI_CLIENT_ID` y `nombre` igual a `MI_NAME`. Si `appId` no coincide, se copiaron mal los valores.

Después de la guía 08: `az role assignment list --assignee-object-id "$MI_PRINCIPAL_ID" --all -o table` muestra las asignaciones esperadas (lista en la guía 08).

En el portal: la identidad → **Overview** (client id y principal id); **Azure role assignments** (menú de la identidad) lista los roles.

## Errores que ya vimos

| Síntoma | Causa | Arreglo |
| --- | --- | --- |
| `AADSTS700211` / `AADSTS70021` en el primer `POST /api/sas/subida` | La credencial federada del broker tiene como `subject` el client id en vez del principal id | Rehacer el guía 14 con `MI_PRINCIPAL_ID` |
| 502 del broker al autorizar contra Dataverse | El usuario de aplicación se creó con el principal id, o la identidad se recreó y cambió de ids | `crear-roles.sh` con `APP_CLIENT_ID=$MI_CLIENT_ID` (paso 7.2) |
| La Function no arranca después de borrar y recrear la identidad (anticipado) | Ids nuevos: las asignaciones de rol apuntan al principal viejo (quedan como "Identity not found") | Rehacer la guía 08 y los guías 14 y 18; borrar las asignaciones huérfanas |
| `PrincipalNotFound` al asignar roles justo después de crear la identidad (anticipado) | Replicación de Entra | `--assignee-object-id` + `--assignee-principal-type ServicePrincipal`, o reintentar al minuto |
| App Insights no recibe trazas aunque la Function corre (anticipado) | `APPLICATIONINSIGHTS_AUTHENTICATION_STRING` con el principal id, o falta Monitoring Metrics Publisher | El valor es `ClientId=$MI_CLIENT_ID;Authorization=AAD`; rol en la guía 05/08 |
