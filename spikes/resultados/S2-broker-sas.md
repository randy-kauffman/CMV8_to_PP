# S2. Broker de SAS con mínimo privilegio

- Fecha: 2026-10-14
- Entorno: suscripción CM8_TO_PP, `rg-cmv8-spikes`, `eastus2`. Function `cmv8-func-supfcopn`, Storage `cmv8adjsupfcopn`, contenedor `cmv8-ingesta`.
- Estado: **parcial, confirmado en la parte de Storage**. Falta la parte de Dataverse (autorización por fila con impersonación), que corre cuando `AUTORIZACION_MODO=dataverse`.

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
- Pendiente para cerrar S2: pasar a `AUTORIZACION_MODO=dataverse`, crear una fila en `cmv8_solicitudingesta`, pedir SAS con un usuario con acceso (200) y con uno sin acceso (403). El usuario de aplicación de la Function ya existe con el rol CMV8 Worker.

## Costo

Sin dato de Cost Management todavía (suscripción creada el mismo día). Consumo real estimado: una ejecución de Function y 1 MB en Storage, menos de un centavo.

## Aprendizajes

- `func azure functionapp publish --java` en Flex Consumption reporta éxito pero no despliega funciones. Usar `mvn clean package azure-functions:deploy`.
- `x-ms-version: 2025-07-05` en el PUT fue aceptado aunque la SAS lleve `sv=2026-10-06`.
- El SDK de Java expone `setDelegatedUserObjectId` (sduoid), así que S1b no necesita cambios de código, solo configuración y el bearer del usuario.
