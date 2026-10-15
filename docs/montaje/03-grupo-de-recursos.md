# 03. Grupo de recursos

> Rol que lo ejecuta: Cloud. Depende de: suscripción del Banco y la región elegida (sección 0 de `docs/guia-montaje-banco.md`). Lo necesitan: todas las guías de Azure (04 a 13): todo vive adentro. Costo: sin costo propio.

## Qué es y qué hace en esta solución

Es el contenedor lógico de todos los recursos de un ambiente (`rg-cmv8-<ambiente>`): identidad administrada, observabilidad, las dos cuentas de Storage, Key Vault, plan y Function App. En el flujo de ingesta no "pasa" ningún dato por acá; lo que hace es fijar tres fronteras que el resto del montaje da por sentadas:

- **Permisos**: quien monta necesita **Owner** sobre el grupo porque el despliegue crea asignaciones de rol (guía 08). Contributor alcanza para crear recursos pero no para asignar roles.
- **Costos**: las etiquetas `proyecto=CMV8_to_PP` y `ambiente=<ambiente>` permiten cortar el costo de la solución en Cost Management y colgar un presupuesto con alertas.
- **Región**: la región del grupo es la que usa el Bicep por defecto (`location = resourceGroup().location`), y tiene que soportar Flex Consumption con Java 21.

Además, antes de crear nada, la suscripción tiene que tener registrados los proveedores de recursos que el Bicep usa.

## ¿Imprescindible?

**Veredicto: Imprescindible**

- Qué aporta: un único alcance de permisos (Owner para montar, IAM heredado), de costos (etiquetas y presupuesto) y de ciclo de vida (borrar el ambiente es borrar el grupo).
- Qué pasa si no está: no se puede desplegar nada; todo recurso de Azure Resource Manager vive en un grupo de recursos.
- Qué decisión lo haría distinto: si el Banco ya tiene un grupo asignado a la solución (por ejemplo creado por su plataforma de landing zones), **se reutiliza** siempre que den **Owner** sobre él (o Contributor + **Role Based Access Control Administrator** restringido a los roles de la guía 08). En ese caso esta guía se reduce a verificar región, etiquetas y proveedores.
- Alternativa y su tradeoff: poner la solución en un grupo compartido con otras soluciones. Ahorra un pedido de acceso, pero mezcla costos, hace que un Owner de la solución pueda tocar recursos ajenos y obliga a nombrar con más cuidado. No se recomienda.

## Valores del cuaderno

| Variable | Produce / consume | Cómo se obtiene |
| --- | --- | --- |
| `AMBIENTE` | Consume | Decisión: `dev`, `qa` o `prod` |
| `RG` | Produce | `rg-cmv8-$AMBIENTE` (o el nombre que asigne el Banco) |
| `REGION` | Consume | Decisión de Cloud; validada abajo con `list-flexconsumption-runtimes` |
| `TENANT_ID` | Consume | `az account show --query tenantId -o tsv` |
| `SUB_ID` | Produce | `az account show --query id -o tsv` después de `az account set` |
| `PREFIJO` | Produce | `cmv8` (el mismo default del parámetro `prefijo` del Bicep) |
| `SUFIJO` | Produce | 8 caracteres en minúsculas y dígitos elegidos por el operador (solo si se monta a mano; con Bicep lo calcula `uniqueString`) |

Ejemplo de las líneas nuevas para agregar al cuaderno (`~/cmv8-<ambiente>.env`):

```bash
SUB_ID=          # az account show --query id -o tsv
PREFIJO=cmv8
SUFIJO=          # p. ej. b4nc0d01: 8 caracteres [a-z0-9], solo si se monta a mano
```

## Con Bicep

El grupo de recursos **no** está en `infra/main.bicep`: la plantilla tiene `targetScope = 'resourceGroup'` (línea 16) y se despliega DENTRO de un grupo que ya existe. Lo crea `infra/desplegar.sh`:

- Líneas 15-18: registra los nueve proveedores (`Microsoft.Web`, `Microsoft.Storage`, `Microsoft.EventGrid`, `Microsoft.Insights`, `Microsoft.OperationalInsights`, `Microsoft.ManagedIdentity`, `Microsoft.Security`, `Microsoft.KeyVault`, `Microsoft.App`) con `--wait`.
- Líneas 20-21: lista las versiones de Java en Flex Consumption para la región.
- Línea 23: `az group create` con `proyecto=CMV8_to_PP` y `ambiente=${AMBIENTE:-spike}`.

Parámetros que lo gobiernan: los argumentos posicionales del script (`<resource-group> <region>`) y la variable de entorno `AMBIENTE`. En el Banco hay que **exportar `AMBIENTE` antes de correr el script**; si no, el grupo queda etiquetado `ambiente=spike`.

```bash
export AMBIENTE
bash infra/desplegar.sh "$RG" "$REGION" infra/banco-$AMBIENTE.bicepparam
```

Dentro del Bicep, `location` (línea 19) toma la región del grupo y `token` (línea 109) se calcula con `uniqueString(subscription().id, resourceGroup().id, location)`: **el sufijo de todos los nombres depende del id del grupo**. Recrear el grupo con otro nombre cambia todos los nombres de recursos.

## Con Azure CLI

```bash
source ~/cmv8-$AMBIENTE.env
export PYTHONWARNINGS=ignore

# 1. Suscripción correcta
az login --tenant "$TENANT_ID"
az account set -s "<subscription id del Banco>"
SUB_ID=$(az account show --query id -o tsv)
az account show --query '{nombre:name, id:id, tenant:tenantId}' -o table

# 2. Proveedores de recursos (idempotente; --wait espera a que quede Registered)
for ns in Microsoft.Web Microsoft.Storage Microsoft.EventGrid Microsoft.Insights \
          Microsoft.OperationalInsights Microsoft.ManagedIdentity Microsoft.Security \
          Microsoft.KeyVault Microsoft.App; do
  az provider register --namespace "$ns" --wait
done

# 3. La región soporta Flex Consumption con Java (tiene que aparecer 21.0)
az functionapp list-flexconsumption-runtimes --location "$REGION" --runtime java --query '[].version' -o tsv

# 4. Grupo de recursos con etiquetas de costo
az group create --name "$RG" --location "$REGION" \
  --tags proyecto=CMV8_to_PP "ambiente=$AMBIENTE" -o none
```

Notas:

- `az provider register` necesita el permiso `*/register/action` a nivel **suscripción** (Contributor u Owner de la suscripción). Si quien monta solo tiene Owner sobre el grupo, el registro lo hace Cloud del Banco una vez por suscripción.
- Si el grupo lo crea el Banco, saltear el paso 4 y verificar etiquetas y región (sección Verificación). Para agregar etiquetas a un grupo existente sin pisar las que ya tiene: `az group update --name "$RG" --set tags.proyecto=CMV8_to_PP "tags.ambiente=$AMBIENTE"`.
- Las etiquetas del grupo **no se heredan** a los recursos. El Bicep solo etiqueta el Key Vault; si el Banco corta costos por etiqueta de recurso, ver "Errores que ya vimos".

## Con el portal

1. Buscar **Subscriptions** en la barra superior y abrir la suscripción del Banco.
2. En el menú de la suscripción, **Settings** → **Resource providers**. Para cada uno de `Microsoft.Web`, `Microsoft.Storage`, `Microsoft.EventGrid`, `Microsoft.Insights`, `Microsoft.OperationalInsights`, `Microsoft.ManagedIdentity`, `Microsoft.Security`, `Microsoft.KeyVault` y `Microsoft.App`: escribirlo en el filtro, seleccionarlo y pulsar **Register** si su estado no es **Registered**. No hace falta esperar a que todos terminen para seguir.
3. La compatibilidad de la región con Flex Consumption + Java **no tiene una pantalla dedicada** en el portal; se verifica con el comando del paso 3 de la sección CLI (o al crear la Function App, en la guía de la Function, donde el portal solo ofrece las regiones compatibles). (verificar en el portal)
4. Buscar **Resource groups** → **Create**.
5. Pestaña **Basics**: **Subscription** = la del Banco; **Resource group** = `rg-cmv8-<ambiente>`; **Region** = la región validada.
6. Pestaña **Tags**: agregar `proyecto` = `CMV8_to_PP` y `ambiente` = `<ambiente>`. **El default del portal es sin etiquetas: hay que cargarlas.**
7. **Review + create** → **Create**.
8. Abrir el grupo → **Access control (IAM)** → **Check access** (o pestaña **Role assignments**) y confirmar que quien va a montar tiene **Owner** en este alcance.

## Verificación

```bash
az group show --name "$RG" --query '{region:location, tags:tags, estado:properties.provisioningState}' -o json
```

Salida esperada:

```json
{
  "estado": "Succeeded",
  "region": "<REGION>",
  "tags": { "ambiente": "<AMBIENTE>", "proyecto": "CMV8_to_PP" }
}
```

```bash
for ns in Microsoft.Web Microsoft.Storage Microsoft.EventGrid Microsoft.Insights Microsoft.OperationalInsights \
          Microsoft.ManagedIdentity Microsoft.Security Microsoft.KeyVault Microsoft.App; do
  printf '%-32s %s\n' "$ns" "$(az provider show --namespace "$ns" --query registrationState -o tsv)"
done   # todas: Registered

az role assignment list --scope "/subscriptions/$SUB_ID/resourceGroups/$RG" --assignee "$(az ad signed-in-user show --query id -o tsv)" \
  --include-inherited --query '[].roleDefinitionName' -o tsv   # debe incluir Owner
```

En el portal: el grupo → **Overview** muestra región y etiquetas; **Access control (IAM)** → **Role assignments** muestra el Owner.

## Errores que ya vimos

| Síntoma | Causa | Arreglo |
| --- | --- | --- |
| El grupo queda con `ambiente=spike` en el Banco | `desplegar.sh` usa `${AMBIENTE:-spike}` y la variable no estaba exportada | `export AMBIENTE` antes del script, o `az group update --set tags.ambiente=<ambiente>` |
| Cost Management no muestra el costo de las cuentas de Storage por etiqueta `proyecto` | Las etiquetas del grupo no se heredan; el Bicep solo etiqueta el Key Vault | Agrupar el costo por grupo de recursos, o asignar la política integrada de herencia de etiquetas desde el grupo (decisión del Banco, a nivel suscripción) |
| `AuthorizationFailed` en `az provider register` (anticipado) | Quien monta es Owner del grupo pero no tiene permiso de registro en la suscripción | Lo registra Cloud del Banco una vez por suscripción |
| `MissingSubscriptionRegistration` al desplegar (anticipado) | Un proveedor de los nueve no quedó registrado | Registrar el proveedor que nombra el error y redeployar |
| `list-flexconsumption-runtimes` no devuelve `21.0` (anticipado) | La región no ofrece Java 21 en Flex Consumption | Elegir otra región, o fijar `javaVersion` a una versión listada si el Banco lo acepta |
| `AuthorizationFailed` al crear asignaciones de rol en la guía 08 (anticipado) | Quien monta es Contributor, no Owner | Pedir Owner, o Role Based Access Control Administrator sobre el grupo |
