# S2. Broker de SAS con mínimo privilegio

- Fecha: 2026-10-14
- Entorno: suscripción CM8_TO_PP, `rg-cmv8-spikes`, `eastus2`. Function `cmv8-func-supfcopn`, Storage `cmv8adjsupfcopn`, contenedor `cmv8-ingesta`.
- Estado: **confirmado** (Storage y Dataverse), incluido el 403 con un usuario sin rol.

## Hipótesis

Con Shared Key deshabilitado en la cuenta, la identidad administrada de la Function con Storage Blob Data Contributor **solo en el contenedor** y Storage Blob Delegator en la cuenta puede emitir una SAS de delegación de usuario de un solo blob, y esa SAS no sirve para ningún otro blob ni para listar el contenedor.

## Configuración probada

- Cuenta: `allowSharedKeyAccess=false`, `allowBlobPublicAccess=false`, TLS 1.2, política de SAS: vencimiento máximo 1 h con acción Block.
- Identidad: user-assigned `cmv8-mi-supfcopn`, client id `d63d3d94-f8ab-4f9c-9a78-23e8390412db`.
- Function en modo `ninguna` (sin Dataverse) para aislar la mecánica de Storage.

## Pasos y evidencia

| Paso | Resultado |
| --- | --- |
| `GET /api/salud` | 200 `{"estado":"ok","version":"0.1.0"}` |
| `POST /api/sas/subida` con solicitudId `1111…`, `prueba.bin`, 1 MB | 200. SAS con `sr=b`, `sp=cw`, `spr=https`, `scid=<solicitudId>`, `sv=2026-10-06`, vigencia 20 min. Respuesta trae `rutaBlob`, `bloqueBytes=4194304` |
| `PUT` de 1 MB a `1111…/prueba.bin` con esa SAS | **201** en 1,52 s |
| `PUT` con la misma SAS a `1111…/otro.bin` | **403** AuthenticationFailed |
| `GET` del contenedor con `restype=container&comp=list` y la misma SAS | **403** AuthenticationFailed |
| `az storage blob delete --auth-mode login` con el admin | 403: el admin no tiene rol de datos sobre la cuenta. Correcto: Propietario de la suscripción no implica acceso a datos. El blob lo borra el lifecycle a los 7 días |

## Veredicto

- Confirmado: la SAS es de un solo blob y la firma incluye la ruta. Cambiar el nombre del blob o intentar listar devuelve 403. Coincide con el análisis de ADR-002.
- Confirmado: Shared Key apagado no impide emitir SAS de delegación de usuario; ninguna clave de cuenta intervino.
- Confirmado: Contributor scoped al contenedor + Delegator a nivel de cuenta alcanza para `getUserDelegationKey` y para la escritura.
- Parte Dataverse cerrada el mismo día: ver la sección «Parte Dataverse» más abajo (200 con acceso, 403 sin rol, 403 fila inexistente, 409 en Subido).

## Costo

Sin dato de Cost Management todavía (suscripción creada el mismo día). Consumo real estimado: una ejecución de Function y 1 MB en Storage, menos de un centavo.

## Aprendizajes

- `func azure functionapp publish --java` en Flex Consumption reporta éxito pero no despliega funciones. Usar `mvn clean package azure-functions:deploy`.
- `x-ms-version: 2025-07-05` en el PUT fue aceptado aunque la SAS lleve `sv=2026-10-06`.
- El SDK de Java expone `setDelegatedUserObjectId` (sduoid), así que S1b no necesita cambios de código, solo configuración y el bearer del usuario.

## Parte Dataverse (2026-10-14, con S7)

Con `AUTORIZACION_MODO=dataverse`, la identidad de la Function como usuario de aplicación con el rol **CMV8 Worker** (sin roles de sistema) y el usuario llamando por el conector con Entra ID:

| Paso | Evidencia |
| --- | --- |
| La app crea la fila con `Patch` | fila `c6e17c3c-…` en estado Creada, `createdon` 07:24:05Z |
| `EmitirSasSubida` por el conector | 200. Traza de App Insights: `SAS de subida emitida… llamador=ContextoLlamador[oid=eab4619b-…, tid=7bcfee03-…, ip=48.211.34.169]`. La IP es la salida de Power Platform: la llamada entró por el conector |
| El broker registra `cmv8_rutablob` en la fila | `c6e17c3c-…/video-100mb.bin` |
| Subida con esa SAS | blob de 104.857.600 bytes, 19,3 s, 43,4 Mbps promedio (línea de estado del PCF) |
| Fila inexistente | 403. El autorizador corre antes que la búsqueda y no distingue "no existe" de "sin acceso": no revela existencia de filas. Correcto, y se documenta como comportamiento esperado |
| Fila ya en Subido | 409 |
| Tamaño 0 y nombre con `..` | 400 |
| Usuario sin rol sobre la fila (`user1@55xljh`, oid `381bb2b4-…`, roles Basic User + uno ajeno, sin CMV8 Solicitante) pide SAS de subida sobre la fila del admin | **403** `No tiene permiso de escritura sobre la solicitud` |
| Mismo usuario pide SAS de lectura | **403** `No tiene permiso de lectura sobre la solicitud` |

Hallazgo: `saoid` en la SAS exige que la identidad firmante tenga `runAsSuperUser` (Blob Data Owner); con Contributor en el contenedor Storage responde 403 `AuthorizationPermissionMismatch` en cada bloque. Se eliminó `saoid`; la auditoría sigue por `scid` y la traza del llamador. Documentado en ADR-002.
