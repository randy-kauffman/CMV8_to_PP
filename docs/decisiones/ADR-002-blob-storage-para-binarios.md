# ADR-002: Azure Blob Storage para los binarios, Dataverse para el registro

- Fecha: 2026-10-13
- Estado: Aceptada con ajustes tras los spikes fuera del Banco (2026-10-14); ver «Estado tras los spikes». Pendiente de validar dentro del Banco: S0 red, S3 Defender, S4a contra CM V8 real
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
- El endpoint público queda habilitado únicamente para la carga desde el navegador con SAS, con firewall de IP a los rangos de salida corporativos del Banco si los usuarios siempre están en la red del banco. CORS: ver «Estado tras los spikes»; los orígenes de Power Apps son variables y no enumerables, quedó `*`.
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
  - **On-behalf-of (OBO)**: la Function intercambia el token del usuario por un token de Dataverse en nombre del usuario y hace el GET con ese token. La petición es inequívocamente del usuario y cuenta contra sus 40.000 requests diarios, no contra el pool. Preferir OBO si el tenant no tiene licencias Dynamics 365 (pool de 25.000). *Superado el 2026-10-14: ver la decisión de abajo, se mantiene suplantación.*
  - Alternativa puntual: la función `RetrievePrincipalAccess` de la Web API devuelve los derechos (ReadAccess, WriteAccess...) de un usuario sobre un registro.
  Si Dataverse devuelve 403 o 404, se termina.
  - **Decisión (2026-10-14, spike S5):** se mantiene la **suplantación** (`CallerObjectId`) y no se usa OBO contra Dataverse. El reporte de requests del admin center excluye Dataverse, por lo que la preferencia por OBO de arriba no se puede sustentar con datos; el OBO queda reservado para el token de Storage. Ver "Estado tras los spikes".
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
| Auditoría (`scid`; `sduoid` en la SAS ligada) | un id de correlación y el object ID del usuario ligado | `saoid`/`suoid` descartados (exigen Blob Data Owner, ver «Estado tras los spikes»). `scid` y el `RequesterUpn` del usuario delegado quedan en los logs de diagnóstico de Storage y se cruzan con el log de la Function: quién pidió qué archivo, cuándo, con qué resultado |

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
| Intentos de manipular SAS | Alerta sobre la métrica `Transactions` con `ResponseType = AuthenticationError` / `SASAuthorizationError`; los 403 `AuthenticationFailed` no quedan en `StorageBlobLogs` (S1b) |

Regla para toda solución del Banco: ningún enlace a un blob se manda por correo ni se guarda en una tabla. El enlace es siempre al registro en la app; la app pide la SAS en el momento.

Para no repetir esta lógica por solución: un único servicio **broker de adjuntos** (una Function app) que recibe (solución, tabla, registroId, operación), valida contra Dataverse por suplantación y emite la SAS sobre el contenedor de esa solución. Cada solución se registra con su contenedor, su tabla y su columna de ruta. ADR-002 propone construir el broker para CMV8 y reutilizarlo.

Nota sobre el PCF: la SAS vinculada al usuario exige que el navegador mande un token Entra para Storage junto con la SAS. El diseño original pedía que el PCF lo obtuviera con MSAL; S1b lo descartó (orígenes de Power Apps no registrables como redirect) y el token lo obtiene el broker por OBO y lo devuelve con la SAS. Ver «Estado tras los spikes».

## Spikes antes de cerrar este ADR

| Id | Pregunta | Criterio de éxito | Resultado (2026-10-14) |
| --- | --- | --- | --- |
| S1 | ¿Un PCF en canvas app sube 100 MB a Blob con SAS de delegación de usuario, desde la red del Banco y desde el navegador corporativo? | Video de 100 MB subido en menos de 2 minutos, con CORS y firewall de Storage configurados. Probar también 250 MB. | Confirmado desde Studio: 100 MB en 15,1 s, 25 bloques de 4 MB, 53 Mbps; el techo es el uplink del usuario. 250 MB, player publicado y red del Banco pendientes. |
| S1b | ¿El PCF puede obtener con MSAL un token Entra para Azure Storage y usar una SAS vinculada al usuario (`sduoid`)? | La SAS funciona con el token del usuario correcto y falla con el de otro usuario; `requireUserBoundUserDelegationSas` en modo block no rompe la carga. | Confirmado con otra mecánica: el token lo obtiene el broker por OBO, no el PCF con MSAL. SAS sola 403, bearer solo 403, otro principal 403, ambas 201. Política de cuenta `Block` aplicada pero no observada. Ver "Estado tras los spikes". |
| S2 | ¿La Function emite SAS de delegación de usuario con su managed identity y Shared Key deshabilitado, tras validar acceso en Dataverse por suplantación? | SAS de 15 min, write-only, un blob; con Shared Key deshabilitado la SAS sigue funcionando; un usuario sin acceso a la fila recibe 403. | Confirmado: SAS de un blob (otro blob 403, listar 403); Dataverse 200 con acceso, 403 sin rol, 403 fila inexistente, 409 en Subido, 400 en tamaño 0 o nombre con `..`. |
| S3 | ¿Defender for Storage emite el evento de resultado de escaneo y la Function se dispara con él? Tiempo entre subida y resultado para 100 MB. | Resultado en menos de 5 minutos; evento recibido; blob con malware de prueba (EICAR) marcado. | No se corre fuera del Banco (decisión del 2026-10-14, costo fijo ~$9,78/mes por cuenta). Cuantificado en `docs/justificacion-financiera-blob-vs-dataverse.md` §8. |
| S4 | ¿El worker en Java lee el blob como stream por private endpoint y lo entrega a OpenCMIS sin cargarlo en memoria? | 100 MB procesados con instancia de 2 GB sin superar 500 MB de heap. | Confirmado contra OpenCMIS InMemory 1.1.0 (S4b): 100 MB con `-Xmx64m` en 8,9 s, contenido idéntico. Contra IBM CM V8 real queda en S4a, dentro del Banco. |
| S5 | ¿A quién se atribuyen las peticiones a Dataverse que hace la Function al validar acceso: con suplantación (`CallerObjectId`) y con OBO? | Ejecutar 1.000 validaciones de cada tipo en un entorno de prueba y leer al día siguiente el reporte de Power Platform requests del admin center (usuario licenciado vs no licenciado). Documentar el resultado y elegir el mecanismo que no consuma el pool de no licenciados. | Cambió de naturaleza: el reporte de requests del admin center excluye Dataverse, así que no hay forma de leer la atribución de licenciamiento. Se hicieron 50 llamadas con suplantación (≈150 a 200 requests) y queda en lectura el análisis de Dataverse. Decisión: se mantiene suplantación. |

## Consecuencias

- Power Automate sale del camino de datos y del camino crítico. Queda disponible para notificaciones.
- El pool de Power Platform requests baja a ~3 operaciones por documento (crear, PATCH Subido, PATCH Archivado).
- Se agrega un componente pro-code en la canvas app (PCF) y hay que habilitar "Power Apps component framework para canvas apps" en el entorno.
- Se agrega un modelo de gobierno de Storage reutilizable por otras soluciones del Banco.
- Costo: Blob hot ~$0,02/GB/mes aprox.; Defender for Storage por cuenta más escaneo por GB (precios a confirmar en la calculadora); Event Grid despreciable a este volumen.


## Estado tras los spikes (2026-10-14)

Resultados de los spikes fuera del Banco: `spikes/resultados/` y `docs/plan-solucion.md` sección 4c. Esta sección integra las dos notas posteriores anteriores (spikes S2/S7 y S1b) y reemplaza lo que contradicen en el cuerpo del ADR (la nota sobre MSAL en el PCF, `saoid` en la tabla de propiedades de la SAS y "CORS limitado a los orígenes de Power Apps").

### Diseño de dos llaves

- La SAS se firma con **`sduoid`** (object ID del usuario) y `skdutid` (tenant). Solo sirve si quien la usa presenta además un token Entra de ese mismo usuario para Azure Storage.
- El token de Storage **no lo obtiene el PCF con MSAL**: Power Apps corre desde orígenes `*.island.powerapps.com` variables, que no se pueden registrar como redirect de una SPA, y los popups o iframes de MSAL dentro del host no son confiables. Lo obtiene el **broker con On-Behalf-Of** (MSAL4J) a partir del token con el que el usuario llama al conector, pidiendo `https://storage.azure.com/user_impersonation`.
- El conector devuelve **juntos** `uploadUrl` (la SAS) y `tokenStorage` (más `tokenStorageExpiraUtc`). El PCF (0.0.5, entrada `tokenStorage`) manda **ambas llaves en cada PUT**: la SAS en la URL y el token como `Authorization: Bearer` en Put Block y Put Block List, con `x-ms-version: 2025-07-05`.
- El usuario no tiene RBAC de datos en el contenedor, así que ninguna de las dos piezas sirve sola. Verificado:

| Lo que presenta el atacante | Resultado |
| --- | --- |
| Solo la SAS | 403 `AuthenticationFailed` (`sduoid` en la SAS, sin token delegado) |
| Solo el token de Storage del usuario | 403 `AuthorizationPermissionMismatch` |
| SAS + token de otro principal | 403 `AuthenticationFailed` (`skdutid`/`sduoid` no coinciden con el tid/oid del token) |
| SAS + token del mismo usuario | 201, solo ese blob, solo `cw`, solo hasta `se` |

- Desde Studio (12:30 UTC): 25 `PutBlock` + 1 `PutBlockList`, todos 201, `AuthenticationType=DelegationSas`, `RequesterUpn` del usuario delegado; la fila pasó a Subido en el mismo segundo del `PutBlockList`.
- Costo del diseño: la app del broker necesita el permiso delegado Azure Storage / `user_impersonation` con consentimiento de administrador y una **credencial propia** (secreto en el spike; Key Vault o credencial federada con la identidad administrada en el Banco). El broker cachea el token por oid y nunca entrega uno que venza antes que la SAS (vida del token 60 a 90 min, SAS de 15 min).

### `saoid` descartado

`saoid` / `suoid` en la SAS **no se usan**: Storage exige que la identidad que pide la clave de delegación tenga `runAsSuperUser` (Storage Blob Data Owner) para aceptarlos, lo que rompe el mínimo privilegio de Contributor en el contenedor. Sin ese rol, toda operación devuelve 403 `AuthorizationPermissionMismatch`. Son campos pensados para Hadoop/Ranger sobre Data Lake. La auditoría se hace con `scid` (= solicitudId) en la URI registrada por Storage, con `RequesterUpn` (usuario delegado) y con la traza del llamador (oid) en App Insights.

### CORS `*` en Storage y en la Function

Power Apps ejecuta las apps desde hosts regionales variables (por ejemplo `https://authoring.us-il301.gateway.prod.island.powerapps.com`), que no se pueden enumerar. La documentación de Storage (*Set Blob Service Properties*) sí admite un comodín de subdominio en `AllowedOrigins` (por ejemplo `https://*.powerapps.com`); falta probar que cubra varios niveles de subdominio. App Service (la Function) solo admite orígenes exactos o `*`, pero al broker solo lo llama el conector desde servidores de Microsoft, así que ahí CORS no interviene. CORS no es frontera de seguridad: la dan la SAS y el bearer. El Banco debe decidir si lo acepta o lo restringe con un proxy delante.

### Política de cuenta y auditoría

- `requireUserBoundUserDelegationSas=true` con acción `Block` está aplicada (el ARM la devuelve) pero **no se observó su efecto**: una SAS de delegación no ligada siguió funcionando, incluso más de una hora después. El diseño no depende de ella, porque el broker siempre firma con `sduoid`. Se reprueba en unos días; si persiste, caso con Microsoft.
- Los 403 `AuthenticationFailed` **no quedan en `StorageBlobLogs`** (fallan antes de autenticar). La alerta de la tabla de riesgos sobre picos de 403 `AuthenticationFailed` en los diagnósticos no es implementable con logs: hay que usar métricas de transacciones (dimensión `ResponseType`).

### Dataverse: suplantación, no OBO

Se mantiene la **suplantación** (`CallerObjectId`) y no se usa OBO contra Dataverse. Razón (S5): el reporte "Microsoft Power Platform requests" del admin center solo incluye Power Automate y excluye Dataverse; la aplicación de límites no empieza hasta al menos seis meses después de que sea GA. No existe hoy un reporte que diga a qué pool se descuentan las requests de Dataverse, así que no hay dato que justifique la complejidad adicional del OBO. Solo se puede leer a qué identidad cuenta Dataverse las operaciones (análisis de Dataverse, Usuarios activos). El pool de 25.000 por día queda como riesgo documentado y se revisa cuando Microsoft publique el reporte para Dataverse.

### Otros riesgos que dejó el programa

- **Token de Storage en la respuesta del conector**: vive en una variable Power Fx. Nunca persistirlo en Dataverse ni usar la operación desde Power Automate sin salidas seguras.
- **Acceso condicional**: el OBO hereda las políticas del usuario sobre Azure Storage; si exigen MFA o dispositivo que el token del conector no satisface, Entra devuelve `interaction_required` y el broker responde 502. Alinear políticas antes de salir.
- **OpenCMIS 1.1.0** (2017) funciona en Java 21 con Browser binding y `DefaultHttpInvoker` envía siempre `Transfer-Encoding: chunked` (65535), sin modo de largo fijo. Riesgo a validar en S4a: un proxy o IHS delante de CM V8 que lo rechace. La librería no tiene mantenimiento activo; se acepta el riesgo porque el binding Browser es JSON puro y el código de cliente es pequeño.
