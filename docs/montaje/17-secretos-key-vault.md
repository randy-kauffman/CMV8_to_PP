# 17. Carga y rotación de secretos en Key Vault

> Rol que lo ejecuta: quien tenga el rol de operador del vault (Key Vault Secrets Officer, asignado por el Bicep a `oidOperadorKeyVault`). Depende de: `09-key-vault.md` (el vault y el rol Key Vault Secrets User de la identidad), 16 (la Function publicada, para el reinicio). Lo necesitan: el worker de ingesta (IBM i y Content Manager), 22 (humo de punta a punta), 23 (rotación). Costo: sin costo propio relevante (operaciones de secretos de Key Vault, centavos al mes; el vault se cuenta en la guía 09).

## Qué es y qué hace en esta solución

Las contraseñas del IBM i (`AS400_PASSWORD`) y de Content Manager (`CMIS_PASSWORD`) viven en Key Vault; la Function las lee como referencias `@Microsoft.KeyVault(VaultName=…;SecretName=…)` con su identidad administrada. El Bicep ya dejó puestas las referencias en los app settings y los permisos; esta guía **carga los valores** y hace que la Function los resuelva.

La creación del vault, su modo RBAC, el acceso público y el private endpoint están en `09-key-vault.md`. Esta guía cubre solo la carga y la rotación.

El script `infra/cargar-secretos.sh` toma las contraseñas de un archivo con el formato de `function/local.settings.json` (`{"Values": {...}}`), busca el vault `cmv8-kv-*` del grupo de recursos, escribe `as400-password` y `cmis-password`, y nunca las imprime.

## ¿Imprescindible?

**Veredicto: Imprescindible** (si existe Key Vault, guía 09)

- Qué aporta: las dos contraseñas de sistemas que no hablan Entra (IBM i y Content Manager) quedan fuera de app settings, pipelines y scripts. La Function sigue leyendo una variable de entorno y no agrega dependencia del SDK de Key Vault.
- Qué pasa si no está: las referencias quedan sin resolver; el worker no puede abrir conexión a IBM i ni a Content Manager y las solicitudes quedan en Error.
- Qué decisión lo haría prescindible (o distinto): que el Banco decida no usar Key Vault y guardar las contraseñas como app settings (decisión y tradeoff en la guía 09). En ese caso esta guía se reemplaza por fijar los dos settings con `az functionapp config appsettings set`, desde una estación autorizada, sin dejar el valor en el historial de la terminal.
- Alternativa y su tradeoff: leer el vault desde el código con el SDK. Solo se justifica si hace falta rotación sin reinicio; agrega dependencia y código. Descartada (`backlog-tecnico.md` §6b).

## Valores del cuaderno

| Variable | Produce / consume | Cómo se obtiene |
| --- | --- | --- |
| `AMBIENTE`, `RG` | Consume | Cuaderno |
| `FUNCTION_NAME` | Consume | Salida del Bicep |
| `KV_NAME` | Consume (informativo; el script lo busca solo) | Salida `keyVaultName` del Bicep |
| `SUB` | Produce (local) | `az account show --query id -o tsv` |
| Contraseñas de IBM i y CM | Consume, NUNCA se anotan | Las entregan los responsables de IBM i y de Content Manager, directo a quien las carga |

## Con comandos

```bash
# archivo FUERA del repo, solo con las dos claves, permisos 600
cat > ~/cmv8-$AMBIENTE-secretos.json <<'EOF'
{ "Values": { "AS400_PASSWORD": "<contraseña del perfil de IBM i>", "CMIS_PASSWORD": "<contraseña del usuario de CM>" } }
EOF
chmod 600 ~/cmv8-$AMBIENTE-secretos.json
bash infra/cargar-secretos.sh "$RG" ~/cmv8-$AMBIENTE-secretos.json
rm ~/cmv8-$AMBIENTE-secretos.json
az functionapp restart -g "$RG" -n "$FUNCTION_NAME"
```

El archivo se escribe con un editor (no pegando la contraseña en la línea de comandos, para que no quede en el historial del shell). Si una de las dos claves falta en el archivo, el script avisa y no la carga; se puede cargar una sola.

La Function toma el valor nuevo al reiniciar (o sola en 24 h): las referencias usan el nombre del secreto sin versión, así que una rotación no requiere redeploy.

### Rotación

Mismo procedimiento con el valor nuevo: archivo temporal, `cargar-secretos.sh`, borrar el archivo, `az functionapp restart`. El orden con el sistema de origen importa: cambiar la contraseña en IBM i o en Content Manager y cargarla en el vault en la misma ventana, y reiniciar enseguida; mientras tanto, el worker reintenta (la cola da 5 intentos antes de mandar a `-poison`). La periodicidad la fija la política del Banco (guía 23).

## Con el portal

En el **Azure portal**:

1. Key vault `KV_NAME` → **Objects** → **Secrets** → **Generate/Import**.
2. **Upload options**: **Manual**. **Name**: `as400-password`. **Secret value**: la contraseña. Opcional: **Set expiration date** alineado con la expiración del perfil en IBM i. **Create**.
3. Repetir con **Name** `cmis-password`.
4. Para rotar: abrir el secreto → **New Version** → nuevo valor → **Create**. La referencia sin versión toma la última.
5. Function App → **Overview** → **Restart**.
6. Function App → **Settings** → **Environment variables** → **App settings**: `AS400_PASSWORD` y `CMIS_PASSWORD` muestran como origen una referencia de Key Vault con estado resuelto (verificar en el portal el indicador exacto; en versiones recientes es un ícono verde junto a "Key vault Reference").

Por portal la contraseña pasa por el portapapeles de quien la carga; es aceptable si la estación es la autorizada. El script tiene la ventaja de no mostrarla nunca.

## Verificación

Todas las referencias en `Resolved`:

```bash
SUB=$(az account show --query id -o tsv)
az rest --method GET \
  --url "https://management.azure.com/subscriptions/$SUB/resourceGroups/$RG/providers/Microsoft.Web/sites/$FUNCTION_NAME/config/configreferences/appsettings?api-version=2023-12-01" \
  --query 'value[].{setting:name, estado:properties.status}' -o table
```

## Errores que ya vimos

| Síntoma | Causa | Arreglo |
| --- | --- | --- |
| Una referencia queda en `UnauthorizedClient` o `AccessToKeyVaultDenied` | El rol Key Vault Secrets User de la identidad todavía no propagó (hasta 10 minutos) | Esperar y reiniciar la Function |
| Igual que arriba, y no se resuelve con el tiempo | El vault quedó con `keyVaultAccesoPublico=false` sin private endpoint | Guía 09 (acceso público o private endpoint) |
| (Mensaje del script) `No hay Key Vault cmv8-kv-* en <rg>` al correr el script | Grupo de recursos equivocado o el Bicep no creó el vault | Revisar `RG` y la guía 09 |
| (No observado, esperable) `cargar-secretos.sh` falla con permiso denegado al escribir | Quien carga no es `oidOperadorKeyVault` (no tiene Key Vault Secrets Officer) | Corregir el parámetro y redesplegar el Bicep, o asignar el rol (guía 09) |
