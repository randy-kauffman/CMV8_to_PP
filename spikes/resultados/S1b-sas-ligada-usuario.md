# S1b. SAS ligada al usuario (`sduoid`) con token de Storage por OBO

- Fecha: 2026-10-14
- Estado: **confirmado** para la SAS ligada (la SAS sola falla, el bearer solo falla, otro principal falla, SAS + bearer del usuario = 201).
  **Hallazgo abierto**: la política de cuenta `requireUserBoundUserDelegationSas=true` + `Block` quedó configurada pero
  **no se observó su efecto** (las SAS de delegación NO ligadas siguieron funcionando ~20 min después de aplicarla). Ver "Pendiente".
- Entorno: Function `cmv8-func-supfcopn` (Flex Consumption, Java 21), Storage `cmv8adjsupfcopn`, contenedor `cmv8-ingesta`,
  fila de prueba `1a39931e-9fc7-f111-a05a-000d3a3b5b91` (estado Creada, dueño admin).

## Hipótesis

Si el broker firma la SAS con `sduoid` = oid del usuario, una SAS robada (logs, captura de red, consola del navegador,
otro usuario de la app) no sirve sin un token Entra **de ese mismo usuario** para Azure Storage. Y como el usuario no tiene
RBAC sobre el contenedor, ese token tampoco sirve solo. Hacen falta las dos piezas a la vez.

## Diseño: OBO en el broker en vez de MSAL en el PCF

| Opción | Por qué sí / por qué no |
| --- | --- |
| MSAL.js dentro del PCF | Descartada por el arquitecto: el PCF corre en orígenes variables (`*.island.powerapps.com`, regionales) que no se pueden registrar como redirect URI de una SPA, y los popups/iframes de MSAL dentro del host de Power Apps no son confiables. |
| **OBO en la Function (elegida)** | El usuario ya llega a la Function con un token Entra (conector personalizado, S7). La Function lo usa como *assertion* del flujo On-Behalf-Of (MSAL4J `ConfidentialClientApplication` + `OnBehalfOfParameters` + `UserAssertion`) y pide `https://storage.azure.com/user_impersonation`. Devuelve ese token junto con la SAS. El PCF lo manda como `Authorization: Bearer` en cada Put Block / Put Block List. |

Flujo:

1. App → conector → `POST /api/sas/subida` con el token del usuario (aud = broker).
2. Easy Auth valida el token; `LectorLlamador` toma `oid`/`tid` y el bearer (fallback `X-MS-TOKEN-AAD-ACCESS-TOKEN`).
3. `EmitirSasSubida`: con `SAS_LIGADA_USUARIO=true` exige oid + bearer (si faltan: **401** antes de tocar Dataverse);
   Dataverse autoriza la fila (S2); OBO → token de Storage (`IntercambiadorTokenObo`, caché en memoria por oid mientras
   le quede vigencia de SAS + 5 min); recién después registra la ruta y firma la SAS con `sduoid` + `skdutid`.
   Si el OBO falla: **502** con el código de Entra (`invalid_grant`, `interaction_required`, …), sin cambiar la fila.
4. Respuesta: `uploadUrl`, `rutaBlob`, `expiraUtc`, `bloqueBytes`, `versionServicio`, **`tokenStorage`, `tokenStorageExpiraUtc`**.
5. PCF 0.0.5: entrada `tokenStorage` → `Authorization: Bearer` en Put Block y Put Block List (`x-ms-version: 2025-07-05`).

Modelo de seguridad resultante:

| Lo que tiene el atacante | Resultado en Storage |
| --- | --- |
| Solo la SAS (ligada) | 403 `AuthenticationFailed` — "sduoid is in sas parameters, but no delegated token found" |
| Solo el token de Storage del usuario | 403 `AuthorizationPermissionMismatch` (el usuario no tiene RBAC de datos en el contenedor) |
| SAS + token de **otro** principal | 403 `AuthenticationFailed` — "skdutid/sduoid does not equal delegated token's tid or oid" |
| SAS + token del mismo usuario | 201 (solo ese blob, solo `cw`, solo hasta `se`) |

## Configuración aplicada

| Pieza | Valor |
| --- | --- |
| App del broker `fe2f40d6-cac3-451b-b24d-ab9e93c80482` | permiso delegado **Azure Storage** (`e406a681-f3d4-42a8-90b6-c2b029497af1`) / `user_impersonation` (scope `03e0da56-190b-40ad-a80c-ea378c433f7f`), consentimiento de administrador (grant `AllPrincipals`) |
| Secreto del broker | `obo-storage-s1b`, keyId `4f775fe7-f537-47b9-b55e-fc7632bfad98`, vence 2027-04-14. **Solo** en el app setting `BROKER_CLIENT_SECRET` (nunca en archivos ni en Bicep) |
| App settings nuevos | `BROKER_CLIENT_ID`, `BROKER_TENANT_ID` (Bicep), `BROKER_CLIENT_SECRET` (fuera de banda), `SAS_LIGADA_USUARIO=true` |
| Storage `sasPolicy` | `requireUserBoundUserDelegationSas: true`, `requireUserBoundUserDelegationSasAction: Block` (primero `Log`), `expirationAction: Block`, `sasExpirationPeriod: 1h` |
| Código | `msal4j` 1.26.0 (última estable en Maven Central; la misma que trae `azure-identity` 1.18.7) |
| PCF | `Banco.CargaAdjuntoBlob` 0.0.5, entrada `tokenStorage` |
| Conector | `EmitirSasSubida` y `EmitirSasLectura` devuelven `tokenStorage` y `tokenStorageExpiraUtc` |

Ojo con Bicep: `Microsoft.Web/sites/config` `appsettings` **reemplaza todos** los settings. `infra/desplegar.sh` lee
`BROKER_CLIENT_SECRET` antes del despliegue (solo en memoria) y lo vuelve a aplicar después; se verificó en los dos
despliegues ("Restaurando BROKER_CLIENT_SECRET…"). En el Banco se reemplaza por `referenciaKeyVaultBroker`.

## Evidencia

SAS emitida (sin la firma): `sv=2026-10-06 … skdutid=7bcfee03-… scid=1a39931e-… sduoid=eab4619b-d1f6-475c-a392-69aa8cf30e4f sr=b sp=cw`.
Claims del `tokenStorage`: `aud=https://storage.azure.com`, `oid=eab4619b-…` (admin), `scp=user_impersonation`,
`appid=fe2f40d6-…` (el broker), `idtyp=user`, vida ~60-75 min.

| # | Prueba (PUT de 1 MiB, `x-ms-version: 2025-07-05`) | Modo `Log` | Modo `Block` | Request id (Block) |
| --- | --- | --- | --- | --- |
| 1 | Broker `POST /api/sas/subida` sin token | — | 401 (Easy Auth) | — |
| 2 | Broker `POST /api/sas/subida` con token admin | 200 + `tokenStorage` | 200 + `tokenStorage` | — |
| 3 | Put Block, SAS ligada **sin** bearer | 403 `AuthenticationFailed` | 403 `AuthenticationFailed` | ccd0ad66-… |
| 4 | Put Block List, SAS ligada sin bearer | 403 `AuthenticationFailed` | 403 `AuthenticationFailed` | b42c0015-… |
| 5 | Put Block, SAS ligada **+ bearer del usuario** | **201** | **201** | 30e1b58e-… |
| 6 | Put Block List, SAS ligada + bearer del usuario | **201** | **201** | 99784e25-… |
| 7 | Put Block solo bearer del usuario (sin SAS) | 403 `AuthorizationPermissionMismatch` | 403 `AuthorizationPermissionMismatch` | d7dee78a-… |
| 8 | Put Block, SAS ligada + bearer de **otro principal** (SP del broker, client credentials, oid `0404cfb0-…`) | 403 `AuthenticationFailed` | 403 `AuthenticationFailed` | eabd8140-… |
| 9 | Put Block List, SAS ligada + bearer de otro principal | 403 `AuthenticationFailed` | 403 `AuthenticationFailed` | 6fd8288f-… |
| 10 | GET con SAS de delegación **NO ligada** (firmada por el admin, Blob Data Reader, `sp=r`) | 200 | **200** (esperado 403) a los 4, 8, 14 y 16 min | 08f9ead1-…, c1656660-…, 0977cff2-…, 6fdb5677-… |
| 11 | Put Block + Put Block List con SAS **NO ligada** del broker (`SAS_LIGADA_USUARIO=false` por 2 min) | — | **201 / 201** (esperado 403) | 50a37b2e-…, c5428801-… |
| 12 | Igual que 11 pero con bearer de otro principal | — | 201 / 201 (Storage autoriza por la SAS e ignora el bearer) | bad6ce7c-…, c56fe130-… |

Detalle de los 403 (cuerpo XML, `AuthenticationErrorDetail`):

- Sin bearer: `[Identity Based SAS] Authentication Failed. Delegated user check failed. sduoid is in sas parameters, but no delegated token found.`
- Otro principal: `[Identity Based SAS] Delegated user check failed. skdutid/sduoid does not equal delegated token's tid or oid.`
- SAS no ligada sin `st` (primer intento de la prueba 10): `Policy violated by no signed start.` — la política de expiración de la cuenta exige `st`.

### StorageBlobLogs (workspace `cmv8-log-supfcopn`)

| Operación | Status | AuthenticationType | RequesterObjectId | RequesterUpn | RequesterAppId | SasExpiryStatus |
| --- | --- | --- | --- | --- | --- | --- |
| PutBlock / PutBlockList, SAS ligada + bearer | 201 | `DelegationSas` | `22f7328c-…` (UAMI que firmó la clave) | **`admin@55xljh.onmicrosoft.com`** (usuario delegado) | `fe2f40d6-…` (broker, `appid` del token OBO) | No policy violation. |
| PutBlock solo bearer | 403 `AuthorizationPermissionMismatch` | `OAuth` | `eab4619b-…` (admin) | admin@… | `fe2f40d6-…` | — |
| GetBlob SAS no ligada | 200 | `DelegationSas` | `eab4619b-…` (firmante) | vacío | vacío | No policy violation. |
| PutBlock / PutBlockList SAS no ligada del broker | 201 | `DelegationSas` | `22f7328c-…` | vacío | vacío / broker | No policy violation. |

- Los 403 `AuthenticationFailed` (pruebas 3, 4, 8, 9) **no aparecen** en StorageBlobLogs: fallan antes de autenticar. Para
  auditar intentos con SAS robada hay que mirar las métricas de transacciones (`ResponseType = AuthenticationError`), no los logs.
- El usuario delegado queda en `RequesterUpn` (no hay columna `RequesterDelegatedUserObjectId`); `RequesterObjectId` es el firmante.
  La correlación con la solicitud sigue siendo `scid` en la URI.
- App Insights (`AppTraces`): `SAS de subida emitida … ligadaUsuario=true tokenStorageExpira=… llamador=ContextoLlamador[oid=eab4619b-…, token=***]`.
  Tres llamadas seguidas mostraron el mismo `tokenStorageExpira`: la caché por oid funciona (un solo OBO).

Limpieza: `az storage blob delete --auth-mode login` falló (el admin solo tiene Blob Data Reader). El blob
`1a39931e-…/s1b.bin` (1 MiB) queda; lo borra la regla de ciclo de vida a los 7 días.

### Re-prueba de la política Block (misma fecha, más de una hora después de aplicarla)

GET con una SAS de delegación de lectura **no ligada** (`az storage blob generate-sas --as-user --permissions r`, sin `sduoid`) sobre un blob del contenedor: **206 Partial Content** (rango de 1 KiB). La política seguía en `true` + `Block` según ARM (api 2025-06-01). Conclusión: el bloqueo no se aplicó en ninguna de las ventanas probadas (minutos y más de una hora). Pendiente reprobar en días y, si persiste, caso con Microsoft.

## Trade-offs

- **El token de Storage viaja en la respuesta del conector.** Queda en memoria de la app canvas (variable de Power Fx) y en el
  historial de ejecución si se usara desde un flujo. Mitigaciones: el token solo sirve para Storage (`aud`), el usuario no
  tiene RBAC, así que sin la SAS no hace nada (prueba 7); la SAS sola tampoco (prueba 3). El robo tiene que ser de las dos
  piezas y del mismo usuario, dentro de la vida de la SAS (15 min). Nunca guardarlo en Dataverse ni mostrarlo en controles.
  No usar esta operación desde Power Automate con historial de ejecución visible sin `secure outputs`.
- **Vida del token vs vida de la SAS.** El token OBO vive 60-90 min y la SAS 15 min. El broker reutiliza el token mientras le
  quede al menos la vigencia de la SAS + 5 min, así que nunca entrega un token que venza antes que la SAS. Una subida que dure
  más que la SAS falla igual que antes (pedir otra SAS); no hay refresco dentro del PCF.
- **Token store de Easy Auth.** Está habilitado; el código usa el `Authorization` original (que Easy Auth deja pasar) y solo
  si faltara usa `X-MS-TOKEN-AAD-ACCESS-TOKEN` (que solo existe con login interactivo, no con bearer del conector).
- **Secreto del broker.** El OBO necesita credencial de la app del broker. Antes S7 no tenía secreto (Easy Auth solo valida).
  Ahora hay uno, con rotación a los 6 meses.
- **Latencia.** Primer OBO por usuario: ~0,4 s extra en `SasSubida`; después, caché.

## Lo que queda para el Banco

1. ~~Secreto → Key Vault o certificado~~ **Hecho el 2026-10-14**: la app del broker confía en la identidad administrada de la Function como credencial federada (`uami-cmv8-broker`, audiencia `api://AzureADTokenExchange`); el OBO firma con `ClientCredentialFactory.createFromCallback` y un token de la identidad. `BROKER_CLIENT_SECRET` eliminado de app settings, Bicep y `desplegar.sh`; secreto `obo-storage-s1b` borrado. Verificado: SAS + bearer → 201 sin secreto.
2. **Acceso condicional.** El OBO hereda las políticas del usuario sobre Azure Storage: si CA pide MFA o dispositivo para
   "Azure Storage" y el token del conector no lo satisface, Entra devuelve `interaction_required` y el broker responde **502**
   con ese código. Hay que alinear las políticas de CA (el conector y Storage en el mismo alcance) o excluir Storage
   delegado vía la app del broker. Probar con un usuario real del Banco.
3. **Consentimiento de administrador** del permiso Azure Storage / `user_impersonation` en la app del broker del tenant del Banco.
4. **`requireUserBoundUserDelegationSas=true` + `Block` como política de cuenta.** Está configurado y el ARM lo devuelve, pero no se
   observó el bloqueo (pruebas 10-12). Reprobar después de varias horas con una SAS no ligada de lectura (`az storage blob generate-sas --as-user --auth-mode login --permissions r --start <ahora-5m> --expiry <ahora+10m> --https-only --full-uri …` y `curl` GET: hoy 200, esperado 403); si sigue sin bloquear,
   abrir caso con Microsoft (puede ser despliegue regional del enforcement). Mientras tanto, la garantía la da el broker
   (siempre firma con `sduoid` cuando `SAS_LIGADA_USUARIO=true`) y que solo la UAMI tiene Blob Delegator.
5. Usuarios de otro tenant (B2B): requieren `allowCrossTenantDelegationSas` y `skdutid`; el código ya pide la clave con el tenant del usuario.

## Cómo repetir

```bash
# código
source ~/.sdkman/bin/sdkman-init.sh
cd function && mvn clean package azure-functions:deploy -DfunctionAppName=cmv8-func-supfcopn -DresourceGroup=rg-cmv8-spikes -Dregion=eastus2
curl -s -o /dev/null -w '%{http_code}\n' https://cmv8-func-supfcopn.azurewebsites.net/api/salud   # 200

# infra (requerirSasLigadaAUsuario=true, accionSasNoLigada='Log' y luego 'Block' en infra/spikes.bicepparam)
bash infra/desplegar.sh rg-cmv8-spikes eastus2
SUB=$(az account show --query id -o tsv)
az rest --method get --url "https://management.azure.com/subscriptions/$SUB/resourceGroups/rg-cmv8-spikes/providers/Microsoft.Storage/storageAccounts/cmv8adjsupfcopn?api-version=2025-08-01" --query properties.sasPolicy

# Entra (una sola vez)
az ad app permission add --id fe2f40d6-cac3-451b-b24d-ab9e93c80482 --api e406a681-f3d4-42a8-90b6-c2b029497af1 --api-permissions 03e0da56-190b-40ad-a80c-ea378c433f7f=Scope
az ad app permission grant --id fe2f40d6-cac3-451b-b24d-ab9e93c80482 --api e406a681-f3d4-42a8-90b6-c2b029497af1 --scope user_impersonation
# secreto directo al app setting, sin pasar por archivos ni consola:
S=$(az ad app credential reset --id fe2f40d6-cac3-451b-b24d-ab9e93c80482 --append --display-name obo-storage-s1b --end-date <fecha> --query password -o tsv)
az functionapp config appsettings set -g rg-cmv8-spikes -n cmv8-func-supfcopn --settings "BROKER_CLIENT_SECRET=$S" -o none; unset S

# prueba de punta a punta (no imprime tokens ni firmas): pasos 2-9 de la tabla
TB=$(az account get-access-token --resource api://fe2f40d6-cac3-451b-b24d-ab9e93c80482 --query accessToken -o tsv)
R=$(curl -s -X POST https://cmv8-func-supfcopn.azurewebsites.net/api/sas/subida -H "Authorization: Bearer $TB" \
  -H 'Content-Type: application/json' -d '{"solicitudId":"1a39931e-9fc7-f111-a05a-000d3a3b5b91","nombreArchivo":"s1b.bin","tamanoBytes":1048576}')
URL=$(echo "$R" | jq -r .uploadUrl); TS=$(echo "$R" | jq -r .tokenStorage)
BID=$(printf '%06d' 0 | base64)   # MDAwMDAw
head -c 1048576 /dev/urandom > /tmp/s1b.bin
curl -s -o /dev/null -w '%{http_code}\n' -X PUT -H 'x-ms-version: 2025-07-05' --data-binary @/tmp/s1b.bin "$URL&comp=block&blockid=$BID"                                  # 403
curl -s -o /dev/null -w '%{http_code}\n' -X PUT -H 'x-ms-version: 2025-07-05' -H "Authorization: Bearer $TS" --data-binary @/tmp/s1b.bin "$URL&comp=block&blockid=$BID"   # 201
curl -s -o /dev/null -w '%{http_code}\n' -X PUT -H 'x-ms-version: 2025-07-05' -H "Authorization: Bearer $TS" -H 'Content-Type: application/xml' \
  --data-binary "<?xml version=\"1.0\" encoding=\"utf-8\"?><BlockList><Latest>$BID</Latest></BlockList>" "$URL&comp=blocklist"                                          # 201

# logs
az rest --method post --url https://api.loganalytics.io/v1/workspaces/22e24388-1a27-4dfa-913c-4da8cf83309d/query --resource https://api.loganalytics.io \
  --body '{"query":"StorageBlobLogs | where TimeGenerated > ago(1h) | where ObjectKey has \"s1b.bin\" | project TimeGenerated, OperationName, StatusCode=toint(StatusCode), AuthenticationType, RequesterObjectId, RequesterUpn, RequesterAppId"}'
```

## Para probar desde Studio

1. Volver a agregar/actualizar el componente (Insertar > Obtener más componentes > Código) para tomar **0.0.5**.
2. Refrescar el conector `CMV8 Broker` en la app (Datos > … > Actualizar) para ver los campos nuevos.
3. Guardar la respuesta del conector y cablear:
   - `CargaAdjuntoBlob.uploadUrl` = `varSas.uploadUrl`
   - `CargaAdjuntoBlob.tokenStorage` = `varSas.tokenStorage`
   - (opcional) `CargaAdjuntoBlob.bloqueBytes` = `varSas.bloqueBytes`

   con `Set(varSas, 'CMV8Broker'.EmitirSasSubida({solicitudId: …, nombreArchivo: …, tamanoBytes: …}))`.
   Sin `tokenStorage`, la subida falla con "Storage rechazo la SAS (403)…": ahora todas las SAS salen ligadas.

## Prueba desde Studio (2026-10-14, 12:30 UTC): confirmada

Ejecutada por el usuario con el PCF 0.0.5 y el conector refrescado. Evidencia leída en Log Analytics y App Insights:

| Fuente | Evidencia |
| --- | --- |
| App Insights, 12:30:22Z | `SAS de subida emitida… ligadaUsuario=true tokenStorageExpira=13:57:03Z` para la solicitud `b9cd9800-cbc7-f111-a05a-000d3a3b5b91` |
| StorageBlobLogs, 12:30:37Z a 12:30:52Z | 25 `PutBlock` + 1 `PutBlockList`, todos **201**, `AuthenticationType=DelegationSas`, `RequesterUpn=admin@55xljh.onmicrosoft.com`, IP del usuario (no la de la Function), 4 carriles en paralelo, 100 MiB |
| Dataverse | La fila pasó a **Subido** a las 12:30:52Z, el mismo segundo del `PutBlockList`: el `OnChange` del componente con `Patch` funciona |

Duración de la subida: 15 s. El negativo (sin `tokenStorage`) no deja rastro en logs porque los 403 `AuthenticationFailed` no se registran;
queda demostrado con las pruebas por CLI de arriba.

Observación menor: la fila quedó con `cmv8_nombrearchivo` y `cmv8_tamanobytes` vacíos. El `Patch` de la app todavía no los escribe; agregarlos
en el `Patch` final junto con el estado Subido.
