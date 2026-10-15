# 23. Operación: rotaciones, publicaciones, monitoreo y errores conocidos

> Rol que lo ejecuta: Identidad (secreto del conector), Cloud (Function, secretos, monitoreo), Power Platform (conector y componente). Depende de: el montaje completo (01 a 22). Lo necesitan: el equipo de soporte. Costo: sin costo propio (el monitoreo se paga en Log Analytics y Application Insights, ver las guías de infraestructura).

## Qué es y qué hace en esta solución

Es el runbook de mantenimiento: qué hay que hacer periódicamente, cómo se publica una versión nueva de cada pieza, qué mirar, y qué significa cada error que ya se vio.

## ¿Imprescindible?

**Veredicto: No aplica: es operación, no un componente**

- Rotación **obligatoria**: el secreto del conector, antes de los 6 meses (si vence, nadie puede crear ni renovar conexiones del conector). Las contraseñas de IBM i y de Content Manager, según la política del Banco.
- Rotación que **no existe**: el broker no tiene secreto (credencial federada, guía 14) y Easy Auth no usa credencial. La identidad administrada no tiene nada que rotar.
- Lo que podría eliminar la única rotación de Entra: autenticar el conector con una identidad administrada del conector en vez de secreto (documentado por Microsoft, no probado en este proyecto; ver guía 02).

## Valores del cuaderno

Todos los del cuaderno (ver las guías de origen). Para rotar el secreto del conector hace falta además el **id del conector** (`pac connector list --environment "$ENV_ID"`).

## Con comandos

| Qué | Cuándo | Cómo |
| --- | --- | --- |
| Rotar el secreto del conector | Antes de los 6 meses | `az ad app credential reset … --append` al vault (guía 02, "Secreto del conector"), `pac connector update --connector-id <id>` con la copia temporal (guía 20), borrar la credencial vieja con `az ad app credential delete`. Las conexiones existentes siguen hasta que el usuario vuelva a iniciar sesión |
| Rotar contraseñas de IBM i o CM | Según la política del Banco | Guía 17 de nuevo y `az functionapp restart`; la Function toma el valor nuevo al reiniciar (o sola en 24 h) |
| Publicar una versión nueva de la Function | Cada cambio | Guía 16; `/api/salud` devuelve la versión |
| Publicar una versión nueva del componente | Cada cambio | Subir `version` en el manifiesto y repetir la guía 19; la app toma la versión nueva al abrirla en Studio |
| Cambiar la Function de hostname o de cuenta de Storage | Nunca sin plan | Hay que repetir la guía 19 (manifiesto) y la guía 20 (swagger) |
| Recrear el conector | Solo si es inevitable | Guía 20 y, después, registrar la URL de redirección nueva (guía 02): cambia el hash |
| Vigilar | Siempre | App Insights: trazas `SAS de subida emitida`, `OBO del broker con la identidad administrada`, excepciones del worker; `StorageBlobLogs` para 403; presupuesto con alertas |

### Rotación del secreto del conector, paso a paso

```bash
# 1. secreto nuevo, directo al vault (la credencial vieja sigue viva gracias a --append)
FIN=$(date -u -d '+6 months' +%Y-%m-%dT%H:%M:%SZ)
az keyvault secret set --vault-name "<vault de secretos de identidad del Banco>" --name cmv8-conector-secreto \
  --expires "$FIN" \
  --value "$(az ad app credential reset --id "$CONECTOR_APP_ID" --append --display-name power-platform-conector --end-date "$FIN" --query password -o tsv)" \
  --query attributes.expires -o tsv

# 2. actualizar el conector con la copia temporal fuera del repo (guía 20, "Actualizar un conector existente")

# 3. identificar y borrar la credencial vieja (la de vencimiento más cercano)
az ad app credential list --id "$CONECTOR_APP_ID" --query '[].{id:keyId, nombre:displayName, fin:endDateTime}' -o table
az ad app credential delete --id "$CONECTOR_APP_ID" --key-id "<keyId de la credencial vieja>"
```

Borrar la vieja recién después de probar una conexión nueva con el conector actualizado (operación `Salud` desde la pestaña **Test**: 200).

## Con el portal

- Secreto del conector: Entra admin center → **App registrations** → `CMV8 Conector (Power Platform)` → **Certificates & secrets** → **Client secrets**: ver vencimientos, crear el nuevo (**New client secret**) y borrar el viejo (ícono de papelera). El valor nuevo se pega en el conector (make.powerapps.com → **Custom connectors** → CMV8 Broker → **Edit** → **Security** → **Client secret** → **Update connector**) y en el vault.
- Contraseñas de IBM i y CM: guía 17, "Con el portal" (**New Version** del secreto y **Restart** de la Function).
- Monitoreo: Azure portal → Application Insights del ambiente → **Transaction search** / **Logs**; Log Analytics → tabla `StorageBlobLogs`; **Cost Management** → presupuesto del grupo de recursos.

## Verificación

- Después de rotar el secreto del conector: `az ad app credential list` muestra una sola credencial `power-platform-conector` con vencimiento a 6 meses; `Salud` desde **Test** devuelve 200; un piloto sube un archivo.
- Después de rotar contraseñas: referencias de Key Vault en `Resolved` (guía 17) y una solicitud llega a Archivado.

## Errores que ya vimos

| Síntoma | Causa | Dónde se arregla |
| --- | --- | --- |
| `AADSTS50011` al crear la conexión | Falta la URL de redirección específica del conector | `02-entra-conector.md` (y `20-conector-personalizado.md` para copiarla) |
| 401 de la Function con token válido | Audiencia o issuer de Easy Auth distintos de los del token (v1 contra v2, GUID contra `api://`) | `15-easy-auth.md` (y `01-entra-broker.md` para la versión del token) |
| 403 `AccesoDenegado` del broker | El usuario no tiene CMV8 Solicitante o la fila no es suya | `18-dataverse.md` |
| 409 del broker | La fila no está en Creada | `21-app-canvas.md`: la app crea la fila antes de pedir la SAS |
| 401 con `WWW-Authenticate: Bearer error="interaction_required"` | Acceso condicional sobre Azure Storage que el token del conector no satisface | Hallazgo 10 de la guía de spikes; `docs/backlog-tecnico.md` §10 |
| 403 `AuthorizationPermissionMismatch` al subir | Faltó el bearer de Storage junto con la SAS ligada, o el usuario tiene un rol de datos que no debería | `21-app-canvas.md` (cableado); roles del contenedor en las guías de infraestructura |
| 502 del broker al autorizar | Dataverse inaccesible: firewall de IP o usuario de aplicación sin rol | `18-dataverse.md` |
| `AADSTS700211` / `AADSTS70021` en el OBO | Credencial federada con subject equivocado | `14-credencial-federada.md` |
| `PessimisticConcurrencyConflict` en `pac connector create` | Operación previa en curso en el APIM interno | `20-conector-personalizado.md`: reintentar a los 5 minutos |
| Referencias de Key Vault en `UnauthorizedClient` / `AccessToKeyVaultDenied` | Rol de la identidad sin propagar, o vault sin acceso público ni private endpoint | `17-secretos-key-vault.md` y `09-key-vault.md` |
| `POST /api/sas/subida` sin token devuelve 200 | Easy Auth no aplicado | `15-easy-auth.md` |
