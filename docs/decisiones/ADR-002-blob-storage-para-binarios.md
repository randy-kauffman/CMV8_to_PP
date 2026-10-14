# ADR-002: Azure Blob Storage para los binarios, Dataverse para el registro

- Fecha: 2026-10-13
- Estado: Propuesta (pendiente de spikes S1 a S3)
- Modifica a ADR-001: reemplaza la columna de archivo de Dataverse como lugar del binario. El resto de ADR-001 (Function Java en Flex Consumption, jt400, OpenCMIS, managed identity, sin VNet delegada en Power Platform) se mantiene.

## Contexto

ADR-001 dejaba el video (hasta 100 MB) en una columna de archivo de Dataverse. Dos hallazgos posteriores lo hacen menos atractivo:

1. Cada chunk de descarga desde Dataverse cuenta como un Power Platform request del pool de usuarios no licenciados del tenant (25.000/día si el Banco solo tiene licencias Power Apps). ~28 requests por documento.
2. La capacidad File de Dataverse cuesta ~$2/GB/mes; Blob Storage en capa hot cuesta ~$0,02/GB/mes aprox. Cien veces menos.

Además, el Banco tiene varias soluciones que necesitan guardar adjuntos. Conviene un modelo de almacenamiento gobernado y reutilizable, no uno por proyecto.

## Decisión

- **Dataverse** guarda el registro: solicitud, cliente, estado, URL del blob, tamaño, hash, resultado del antivirus, cmisObjectId, errores. Es la fuente de verdad del tracking y lo que ve el usuario en la app.
- **Azure Blob Storage** guarda el binario, de forma transitoria, hasta que Content Manager lo archiva. Content Manager sigue siendo el archivo definitivo.
- El binario viaja **directo desde el navegador del usuario a Blob**, nunca por Power Automate ni por un conector.

## Cómo sube el archivo: opciones evaluadas

| Opción | Límite verificado | Veredicto |
| --- | --- | --- |
| Conector Azure Blob Storage desde la canvas app (Create blob V2) | 50 MB sin chunking. El chunking (hasta 1.024 MB) es una opción de acciones en flows, no de llamadas desde Power Fx. El conector no soporta gateway ni cuentas detrás de firewall. Autenticación Entra ID integrada o service principal "no compartible" (cada usuario crea su conexión). | Descartado para videos de 100 MB. |
| Subir primero a Dataverse y que la Function lo mueva a Blob | Funciona, pero conserva el consumo del pool de requests y la capacidad File. | Descartado: no resuelve lo que motivó este ADR. |
| **Componente PCF en la canvas app + SAS de delegación de usuario emitida por la Function** | Patrón documentado por Microsoft para carga desde navegador. Sin límite práctico (bloques de 4 MB en paralelo con el SDK de Storage para JavaScript). | **Elegido**, sujeto a spike S1. |

## Flujo

```mermaid
sequenceDiagram
    participant U as Usuario (Canvas App + PCF)
    participant D as Dataverse
    participant F as Function HTTP /upload-url
    participant B as Blob Storage
    participant DF as Defender for Storage
    participant EG as Event Grid
    participant W as Function Worker
    participant A as AS400
    participant C as Content Manager

    U->>D: 1. Crea SolicitudIngesta (estado=Pendiente, sin archivo)
    U->>F: 2. GET /upload-url?solicitudId (token Entra del usuario)
    F->>D: valida que la solicitud existe, está Pendiente y pertenece al usuario
    F-->>U: SAS de delegación de usuario: write-only, 15 min, un solo blob
    U->>B: 3. Sube en bloques de 4 MB directo al blob cmv8-ingesta/{solicitudId}/{archivo}
    U->>D: 4. PATCH estado=Subido, blobUrl, tamaño, hash
    B-->>DF: 5. Escaneo antivirus al subir
    DF-->>EG: resultado (etiqueta en el blob + evento)
    EG-->>W: 6. Dispara con el evento de resultado de escaneo
    W->>D: lee la solicitud; si el blob tiene malware → estado=Rechazado y fin
    W->>B: 7. Lee el blob como stream (managed identity, private endpoint)
    W->>A: 8. Resuelve metadatos (jt400)
    W->>C: 9. createDocument + metadatos (OpenCMIS)
    W->>D: 10. PATCH estado=Archivado, cmisObjectId
    W->>B: 11. Borra el blob (la política de ciclo de vida es la red de seguridad)
```

Watchdog (timer): solicitudes en Subido durante más de 15 minutos sin Archivado ni Error se reprocesan. Idempotencia: el worker no sube a CMIS si la solicitud ya tiene cmisObjectId.

Nota sobre el disparador: Event Grid reemplaza al flow + custom connector + Function HTTP de ingesta de ADR-001. Ventajas: no consume Power Platform requests, reintentos de 24 horas y dead-letter nativos, y espera el resultado del antivirus sin polling. El flow queda solo si se quiere para notificaciones. Si Defender for Storage no se aprueba, el disparador es el evento BlobCreated.

## Modelo de seguridad y gobierno del almacenamiento

Pensado para que varias soluciones del Banco compartan el mismo modelo.

### Estructura

- Una cuenta de almacenamiento por ambiente (dev, test, prod) para adjuntos de soluciones de negocio, tipo StorageV2 o BlockBlob, LRS o ZRS según el requisito de resiliencia (los blobs son transitorios; CM es el archivo).
- Un **contenedor por solución**: `cmv8-ingesta`, `otra-solucion`, etc. Nunca un contenedor compartido entre soluciones.
- Ruta del blob: `{solicitudId}/{nombreArchivo}`. Etiquetas de índice: `solucion`, `solicitudId`, `clienteId` (sin datos sensibles en nombres ni etiquetas).
- Cuenta separada solo si una solución tiene una clasificación de datos distinta (por ejemplo, datos regulados con clave propia).

### Identidad y acceso

- **Shared Key deshabilitado** en la cuenta (`allowSharedKeyAccess = false`). Esto invalida claves de cuenta, service SAS y account SAS. Solo quedan Entra ID y SAS de delegación de usuario.
- Acceso anónimo deshabilitado a nivel de cuenta (`allowBlobPublicAccess = false`).
- Cada solución tiene su propia managed identity con rol **Storage Blob Data Contributor** con alcance en SU contenedor, no en la cuenta.
- La Function de cada solución emite SAS de delegación de usuario: permiso solo de escritura (`cw`), 15 minutos, alcance a un solo blob, solo HTTPS. Revocación inmediata vía `Revoke User Delegation Keys`.
- Los usuarios finales no tienen ningún rol en Storage. Solo reciben una SAS puntual a través de la Function, que valida contra Dataverse que la solicitud es suya.

### Red

- **Private endpoint** para el acceso de la Function (lectura y borrado) desde su VNet.
- El endpoint público queda habilitado únicamente para la carga desde el navegador con SAS, con firewall de IP a los rangos de salida corporativos del Banco si los usuarios siempre están en la red del banco. CORS limitado a los orígenes de Power Apps.
- Si seguridad exige endpoint público cerrado, la alternativa es subir a través del conector de Blob con VNet delegada en Power Platform, que cae en el límite de 50 MB. Esa combinación no cumple el requisito de 100 MB; hay que decidirlo explícitamente.

### Protección de datos

- Cifrado en reposo con claves administradas por Microsoft por defecto; **claves administradas por el cliente en Key Vault** si cumplimiento lo exige (habitual en banca).
- TLS 1.2 mínimo, solo HTTPS.
- Soft delete de blobs 7 días, versionado desactivado (los blobs son transitorios).
- Política de ciclo de vida: borrar blobs con más de 7 días. Es la red de seguridad; el worker borra al archivar.
- **Defender for Storage con escaneo antivirus al subir**: el resultado queda en una etiqueta de índice del blob y en un evento de Event Grid. El worker no sube nada a Content Manager sin resultado limpio. Límite de escaneo configurable por cuenta por mes.

### Operación

- Diagnósticos de Storage a Log Analytics (lecturas, escrituras, borrados, SAS emitidas).
- Alertas: blobs en el contenedor con más de 24 horas, errores 403 repetidos, consumo del cap de antivirus.
- Azure Policy sobre la suscripción: HTTPS obligatorio, TLS 1.2, sin acceso público, sin Shared Key, diagnósticos habilitados. Así cualquier cuenta nueva de otra solución nace cumpliendo.
- Etiquetas de recursos para asignación de costos por solución.

## Autorización por archivo: quién puede ver qué

Blob Storage no tiene seguridad por fila. Azure RBAC se asigna por cuenta o por contenedor, y ABAC permite condiciones por etiqueta o prefijo, pero ninguna de las dos sirve para "este usuario ve este archivo y aquel no" cuando hay miles de archivos y cientos de usuarios. Por eso la decisión de autorización NO vive en Storage.

Principio: **Dataverse decide, la Function emite, Storage solo verifica el token.**

- Dataverse ya tiene el modelo completo: roles de seguridad, unidades de negocio, equipos, compartir registros, seguridad a nivel de columna. Cada archivo tiene una fila de registro en Dataverse. Si el usuario no puede leer la fila, no puede ni pedir el archivo.
- La Function recibe la petición con el token Entra del usuario y verifica en Dataverse que ese usuario puede leer la fila. Dos formas, ambas delegan la decisión a Dataverse:
  - **Suplantación**: header `CallerObjectId` con el object ID de Entra del usuario (preferido por Microsoft; `MSCRMCallerID` con systemuserid es el modo legado). El application user necesita el privilegio `prvActOnBehalfOfAnotherUser` (rol Delegate). La documentación no especifica si estas peticiones se atribuyen al pool de no licenciados o al usuario suplantado; es 1 request por emisión de SAS, así que el impacto es marginal en cualquier caso.
  - **On-behalf-of (OBO)**: la Function intercambia el token del usuario por un token de Dataverse en nombre del usuario y hace el GET con ese token. La petición es inequívocamente del usuario y cuenta contra sus 40.000 requests diarios, no contra el pool. Preferir OBO si el tenant no tiene licencias Dynamics 365 (pool de 25.000).
  - Alternativa puntual: la función `RetrievePrincipalAccess` de la Web API devuelve los derechos (ReadAccess, WriteAccess...) de un usuario sobre un registro.
  Si Dataverse devuelve 403 o 404, se termina.
- Solo si Dataverse autoriza, la Function emite una SAS de delegación de usuario para ESE blob. Los usuarios finales nunca tienen roles en Storage ni conocen una URL utilizable.
- La columna con la ruta del blob en Dataverse guarda solo la ruta (`cmv8-ingesta/{id}/archivo`), nunca una URL firmada. Si una solución quiere ocultar incluso la existencia del archivo, aplica seguridad de columna sobre esa columna.

Qué es una SAS: una URL del blob más una cadena de consulta firmada que dice recurso, permisos, vigencia, protocolo e IP permitida. Es un token de capacidad: quien lo tenga puede usarlo mientras dure. Por eso cada SAS que emite la Function cumple:

| Propiedad | Valor | Para qué |
| --- | --- | --- |
| Recurso (`sr`) | un solo blob | Nunca contenedor ni prefijo |
| Permisos (`sp`) | `r` para ver, `cw` para subir | Nunca borrar ni listar |
| Vigencia (`se`) | 5 min para ver, 15 min para subir | Si expira, la app pide otra |
| Protocolo (`spr`) | solo HTTPS | |
| IP (`sip`) | rangos de salida del Banco, si los usuarios están siempre en la red | Un token filtrado no sirve desde afuera |
| Firma | clave de delegación de la managed identity de la Function | Revocable con `Revoke User Delegation Keys`; el token nunca puede más que la identidad que lo firmó |
| Usuario vinculado (`sduoid`) | object ID del usuario (versión de servicio 2025-07-05 o superior) | **La SAS solo funciona si quien la usa presenta además un token Entra con ese mismo oid.** Un token filtrado es inútil para cualquier otra persona. La cuenta puede exigirlo con `requireUserBoundUserDelegationSas` = true y acción `block`. |
| Auditoría (`saoid` o `suoid`, `scid`) | object ID del usuario y un id de correlación | Quedan en los logs de diagnóstico de Storage y se cruzan con el log de la Function: quién pidió qué archivo, cuándo, con qué resultado |

Flujo de lectura para soluciones cuyo archivo permanece en Blob:

1. La app muestra la lista desde Dataverse. Dataverse ya filtró las filas que el usuario puede ver.
2. El usuario abre un registro. El PCF llama a la Function `GET /read-url?registroId`.
3. La Function suplanta al usuario contra Dataverse. Sin acceso, 403.
4. Con acceso, emite SAS de lectura de 5 minutos, un blob, vinculada al usuario, con id de correlación, y registra el evento.
5. El visor de la app carga la URL. Si expira, se pide otra. Nada se persiste en el cliente.

Revocación: deshabilitar al usuario en Entra o quitarle el rol en Dataverse corta las SAS nuevas de inmediato; las emitidas mueren en minutos. Ante un incidente, revocar las claves de delegación invalida todas las SAS vigentes de la cuenta.

### Amenaza analizada: un usuario técnico intercepta la SAS desde la app

Qué puede y qué no puede hacer con ella, según el mecanismo de firma documentado por Azure Storage:

- La firma (`sig`) es un HMAC-SHA256 sobre la "string-to-sign", que incluye el `canonicalizedResource`: `/blob/{cuenta}/{contenedor}/{ruta-del-blob}`. Cambiar un solo carácter de la ruta invalida la firma y Storage responde 403 (`AuthenticationFailed`, "Signature did not match"). **Una SAS de un blob no abre ningún otro blob**, aunque el atacante conozca todas las rutas del contenedor.
- El permiso de listar (`l`) solo existe para SAS de contenedor o prefijo (`sr=c`, `sr=d`). Con `sr=b` y `sp=r` no hay forma de enumerar nada. El endpoint de Blob no tiene interfaz navegable.
- Lo único que obtiene: volver a descargar, durante 5 minutos como máximo, el archivo que Dataverse ya le autorizó a ver. Con `sduoid` ni siquiera eso desde otra identidad: Storage exige un token Entra con el mismo object ID.
- Interceptar significa mirar su propio tráfico (herramientas del navegador). TLS impide que un tercero lo vea en la red.
- Quien está autorizado a ver un archivo puede guardarlo en su disco; eso no lo resuelve ninguna SAS. Es territorio de DLP, Purview y las políticas de Content Manager.

Dónde vive el riesgo real, y su control:

| Riesgo | Control |
| --- | --- |
| El broker emite por error una SAS de contenedor o prefijo | Pruebas unitarias que afirman `sr=b`, sin `l` ni `d`; revisión de código; alerta en Log Analytics ante cualquier petición con SAS cuyo recurso registrado sea un contenedor |
| Alguien crea una account SAS o service SAS con la clave de cuenta | Shared Key deshabilitado: esas SAS no pueden existir |
| Un usuario recibe un rol RBAC sobre el contenedor | Azure Policy y revisión de accesos: solo managed identities tienen roles de datos |
| Intentos de manipular SAS | Alerta ante picos de 403 `AuthenticationFailed` en los diagnósticos de Storage |

Regla para toda solución del Banco: ningún enlace a un blob se manda por correo ni se guarda en una tabla. El enlace es siempre al registro en la app; la app pide la SAS en el momento.

Para no repetir esta lógica por solución: un único servicio **broker de adjuntos** (una Function app) que recibe (solución, tabla, registroId, operación), valida contra Dataverse por suplantación y emite la SAS sobre el contenedor de esa solución. Cada solución se registra con su contenedor, su tabla y su columna de ruta. ADR-002 propone construir el broker para CMV8 y reutilizarlo.

Nota sobre el PCF: la SAS vinculada al usuario exige que el navegador mande un token Entra para Storage junto con la SAS. El PCF debe obtenerlo con MSAL (app registration tipo SPA con permiso `user_impersonation` de Azure Storage y consentimiento de administrador). Spike S1b lo valida; si no fuera viable, la defensa queda en vigencia corta, un solo blob, IP y HTTPS.

## Spikes antes de cerrar este ADR

| Id | Pregunta | Criterio de éxito |
| --- | --- | --- |
| S1 | ¿Un PCF en canvas app sube 100 MB a Blob con SAS de delegación de usuario, desde la red del Banco y desde el navegador corporativo? | Video de 100 MB subido en menos de 2 minutos, con CORS y firewall de Storage configurados. Probar también 250 MB. |
| S1b | ¿El PCF puede obtener con MSAL un token Entra para Azure Storage y usar una SAS vinculada al usuario (`sduoid`)? | La SAS funciona con el token del usuario correcto y falla con el de otro usuario; `requireUserBoundUserDelegationSas` en modo block no rompe la carga. |
| S2 | ¿La Function emite SAS de delegación de usuario con su managed identity y Shared Key deshabilitado, tras validar acceso en Dataverse por suplantación? | SAS de 15 min, write-only, un blob; con Shared Key deshabilitado la SAS sigue funcionando; un usuario sin acceso a la fila recibe 403. |
| S3 | ¿Defender for Storage emite el evento de resultado de escaneo y la Function se dispara con él? Tiempo entre subida y resultado para 100 MB. | Resultado en menos de 5 minutos; evento recibido; blob con malware de prueba (EICAR) marcado. |
| S4 | ¿El worker en Java lee el blob como stream por private endpoint y lo entrega a OpenCMIS sin cargarlo en memoria? | 100 MB procesados con instancia de 2 GB sin superar 500 MB de heap. |
| S5 | ¿A quién se atribuyen las peticiones a Dataverse que hace la Function al validar acceso: con suplantación (`CallerObjectId`) y con OBO? | Ejecutar 1.000 validaciones de cada tipo en un entorno de prueba y leer al día siguiente el reporte de Power Platform requests del admin center (usuario licenciado vs no licenciado). Documentar el resultado y elegir el mecanismo que no consuma el pool de no licenciados. |

## Consecuencias

- Power Automate sale del camino de datos y del camino crítico. Queda disponible para notificaciones.
- El pool de Power Platform requests baja a ~3 operaciones por documento (crear, PATCH Subido, PATCH Archivado).
- Se agrega un componente pro-code en la canvas app (PCF) y hay que habilitar "Power Apps component framework para canvas apps" en el entorno.
- Se agrega un modelo de gobierno de Storage reutilizable por otras soluciones del Banco.
- Costo: Blob hot ~$0,02/GB/mes aprox.; Defender for Storage por cuenta más escaneo por GB (precios a confirmar en la calculadora); Event Grid despreciable a este volumen.


## Nota posterior (2026-10-14, spikes S2/S7)

- `saoid` / `suoid` en la SAS **no se usan**: Storage exige que la identidad que pide la clave de delegación tenga `Microsoft.Storage/storageAccounts/blobServices/containers/blobs/runAsSuperUser/action` (Storage Blob Data Owner) para aceptarlos, lo que rompe el mínimo privilegio de Contributor en el contenedor. Sin ese rol, toda operación devuelve 403 `AuthorizationPermissionMismatch`. Son campos pensados para Hadoop/Ranger sobre Data Lake. La correlación de auditoría se hace con `scid` (= solicitudId) en los logs de Storage y con la traza del llamador (oid) en App Insights. `sduoid` (SAS ligada a usuario) es independiente y sigue en el plan para S1b.
- CORS en Storage y en la Function queda en `*`: Power Apps ejecuta las apps desde hosts regionales variables (`*.gateway.prod.island.powerapps.com`) que no se pueden enumerar y ninguno de los dos servicios admite comodines de subdominio. CORS no es un control de seguridad; la autorización la dan la SAS y Entra ID.
- OpenCMIS 1.1.0 (2017) funciona en Java 21 con Browser binding y envía siempre `Transfer-Encoding: chunked` (64 KB). Riesgo a validar en S4a: un proxy o IHS delante de CM V8 que rechace chunked. La librería no tiene mantenimiento activo; se acepta el riesgo porque el binding Browser es JSON puro y el código de cliente es pequeño.

## Nota posterior (2026-10-14, spike S1b)

- La SAS ligada al usuario (`sduoid`) **se adopta**, pero el token de Storage **no lo obtiene el PCF con MSAL** (orígenes
  `*.island.powerapps.com` variables, no registrables como redirect de SPA): lo obtiene el broker con **On-Behalf-Of**
  (MSAL4J) a partir del token con el que el usuario llama al conector, y lo devuelve como `tokenStorage` junto con la SAS.
  El PCF (0.0.5, entrada `tokenStorage`) lo manda como `Authorization: Bearer` en Put Block / Put Block List.
  Esto reemplaza la nota sobre MSAL en el PCF de la sección "Autorización por archivo".
- Verificado: SAS sola → 403 `AuthenticationFailed`; bearer solo → 403 `AuthorizationPermissionMismatch`; SAS + bearer de otro
  principal → 403; SAS + bearer del usuario → 201. En StorageBlobLogs el usuario delegado aparece en `RequesterUpn`.
- Costo: la app del broker necesita el permiso delegado Azure Storage / `user_impersonation` con consentimiento de administrador y
  una credencial propia (secreto en el spike; Key Vault o certificado en el Banco); el OBO queda sujeto al acceso condicional.
- Abierto: `requireUserBoundUserDelegationSas=true` + `Block` quedó configurado pero no se observó que bloquee SAS no ligadas.
  Detalle y comandos en `spikes/resultados/S1b-sas-ligada-usuario.md`.
