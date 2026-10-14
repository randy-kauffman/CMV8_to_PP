# Spikes fuera del Banco

Orden recomendado (docs/plan-solucion.md, sección 4b): S1, S2, S7, S4b primero porque producen artefactos; después S1b, S3, S5. S6 solo la mecánica de jt400 contra PUB400.

Cada spike termina con `spikes/resultados/S<n>-<nombre>.md`: hipótesis, pasos ejecutados, evidencia (salidas, capturas, métricas) y veredicto: confirmado / refutado / parcial.

## Prerrequisitos

```bash
az login
az account set -s "<subscription id>"
bash infra/desplegar.sh rg-cmv8-spikes eastus2          # infra base
source ~/.sdkman/bin/sdkman-init.sh                      # java 21 + maven
cd function && mvn clean package && func azure functionapp publish <functionAppName> --java && cd ..
DATAVERSE_URL=https://org36e60d9d.crm.dynamics.com bash dataverse/crear-tabla.sh
bash spikes/generar-fixtures.sh
```

## S1. Carga directa desde el PCF (100 y 250 MB)

Hipótesis: un control PCF en canvas sube 100 MB a Blob por bloques de 4 MB usando una SAS de delegación de usuario, en menos de 2 minutos en una conexión normal, sin pasar por Dataverse ni Power Automate.

1. Function con `AUTORIZACION_MODO=ninguna` (solo en este spike).
2. `cd pcf && npm start watch`. En el harness: `urlBroker` = https://<functionAppHostName>, `solicitudId` = cualquier GUID, elegir `spikes/fixtures/video-100mb.bin`.
3. Registrar: tiempo total, bloques reintentados, estado final, `rutaBlob`.
4. Repetir con 250 MB. Verificar el blob con `az storage blob show --auth-mode login`.
5. Comparar el SHA256 del blob descargado con `spikes/fixtures/SHA256SUMS`.

Refutación: si el harness o Power Apps limitan el `File` del navegador, o si CORS bloquea PUT con `comp=block`.

## S2. Broker de SAS con mínimo privilegio

Hipótesis: con Shared Key deshabilitado, la identidad administrada con Contributor en el contenedor y Delegator en la cuenta emite una SAS de un solo blob, y una SAS robada no abre ningún otro blob ni lista el contenedor.

1. `AUTORIZACION_MODO=dataverse`. Registrar el application user de la identidad en el entorno (admin center → usuarios de aplicación → client id de la salida `identidadClientId`) y darle el rol de worker.
2. Pedir SAS con un usuario con acceso a la fila → 200. Con un usuario sin acceso → 403. Sin fila → 404/403.
3. Con la SAS emitida: PUT al blob → 201. PUT a otro nombre de blob con la misma SAS → 403. GET con `restype=container&comp=list` → 403.
4. `az storage account keys list` sobre la cuenta → confirmar que ninguna operación de datos con clave funciona (403 KeyBasedAuthenticationNotPermitted).

## S7. Conector personalizado con Entra ID

Hipótesis: un custom connector generado del OpenAPI de la Function, con autenticación Entra ID (app registration + App Service auth en la Function), funciona desde canvas sin claves.

1. App registration para la Function (exponer API, scope `user_impersonation`), activar App Service Authentication con esa app.
2. App registration cliente para el conector; permiso al scope de la Function.
3. Crear el conector desde `function/openapi.json` (lo genera la Function en `/api/openapi` o se escribe a mano).
4. Desde canvas: llamar `salud` y `sas/subida`. Verificar que la Function recibe `X-MS-CLIENT-PRINCIPAL-ID` con el oid del usuario.

## S4b. Worker CMIS 1.1 por streaming

Hipótesis: OpenCMIS con Browser binding sube 100 MB a un servidor CMIS 1.1 de prueba sin cargar el archivo en memoria, y el objectId vuelve a la fila de Dataverse.

1. Servidor de prueba en Docker: Apache Chemistry InMemory (`docker run -p 8080:8080 ...`) o Alfresco Community. Documentar cuál.
2. Exponerlo a la Function (ngrok/devtunnel para el spike; la Function en Azure no llega a localhost).
3. Subir con el PCF, forzar el evento (BlobCreated con `crearSuscripcionEventGrid=true`), observar en App Insights memoria y tiempo del worker.
4. Verificar el documento en el servidor CMIS y el cmisObjectId en la fila.

## S1b. SAS ligada a usuario

`requerirSasLigadaAUsuario=true`, acción Log y luego Block. El PCF debe mandar el bearer del usuario (MSAL). Verificar que la SAS sin bearer del mismo oid → 403.

## S3. Defender for Storage

`habilitarDefender=true`. Subir `eicar.com` y el video. Verificar evento `Microsoft.Security.MalwareScanningResult` en la Function, blob del EICAR rechazado y fila en Rechazado; video limpio encolado. Medir latencia entre PutBlockList y el evento.

## S5. Atribución de requests en el admin center

Correr 50 ingestas con impersonación y 50 con OBO. Al día siguiente, leer el reporte de requests del admin center: a quién se atribuyen. Veredicto: cuál modo elegir en el Banco según el pool.

## S6. jt400 contra PUB400

Conexión JDBC desde la Function (o local) a `pub400.com:8471` con usuario propio, consulta trivial. Prueba solo la mecánica: driver, TLS, timeouts. Las consultas reales son del Banco.
