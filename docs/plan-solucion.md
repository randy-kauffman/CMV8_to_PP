# Plan de solución: Power Apps → Blob Storage → Azure Function → Content Manager V8

- Fecha: 2026-10-13
- Base: ADR-001 (conectividad, Function Java, jt400, OpenCMIS) y ADR-002 (Blob para binarios, Dataverse para registro, SAS, Event Grid).

## 1. Cómo funciona

```
┌──────────────────────────────── POWER PLATFORM ────────────────────────────────┐
│                                                                                │
│  Canvas App                                                                    │
│   ├─ Pantalla "Nueva solicitud": ID cliente ──▶ custom connector ──▶ Function  │
│   │     GET /clientes/{id}  ◀── nombre, otro ID, afiliado padre, propietario   │
│   ├─ PCF "Subir archivo": pide SAS ──▶ Function POST /solicitudes/{id}/upload-url│
│   │     y sube bloques de 4 MB DIRECTO a Blob (no pasa por Power Platform)     │
│   ├─ Pantalla "Mis solicitudes": lista y estados desde Dataverse               │
│   └─ Botón "Ver": pide SAS de lectura ──▶ Function GET /documentos/{id}/read-url│
│                                                                                │
│  Dataverse: tabla SolicitudIngesta (registro, estado, blobPath, cmisObjectId)  │
│  Flow (opcional): notificaciones cuando estado = Archivado o Error             │
└────────────────────────────────────────────────────────────────────────────────┘
                 │ custom connector (Entra ID)                 ▲ PATCH estado
                 ▼                                             │
┌──────────────────────────────────── AZURE ─────────────────────────────────────┐
│                                                                                │
│  Function App (Java, Flex Consumption, VNet integration, managed identity)     │
│   ├─ HTTP  GET  /clientes/{id}                → jt400 → AS400                  │
│   ├─ HTTP  POST /solicitudes/{id}/upload-url  → valida en Dataverse → SAS cw   │
│   ├─ HTTP  GET  /documentos/{id}/read-url     → valida → SAS r (Blob o staging)│
│   ├─ HTTP  GET  /documentos?clienteId=        → CMIS query en Content Manager  │
│   ├─ Event Grid trigger (resultado antivirus) → WORKER                         │
│   └─ Timer (cada 10 min)                      → watchdog de atascadas          │
│                                                                                │
│  WORKER: lee blob (stream) → AS400 metadatos → CMIS createDocument             │
│          → PATCH Dataverse Archivado + cmisObjectId → borra blob               │
│                                                                                │
│  Blob Storage (cuenta "adjuntos", contenedor cmv8-ingesta, Shared Key OFF,     │
│  private endpoint, Defender antivirus, ciclo de vida 7 días)                   │
│  Event Grid · Key Vault (contraseña AS400) · App Insights · Log Analytics      │
└────────────────────────────────────────────────────────────────────────────────┘
                 │ ExpressRoute / VPN existente (misma red que las VMs)
                 ▼
┌──────────────────────────────── RED INTERNA DEL BANCO ───────────────────────────────┐
│  AS400 (Db2 for i, puertos 8471/9471 y 8476/9476)                              │
│  IBM Content Manager V8 (CMIS 1.1 Browser binding, HTTPS)                      │
└────────────────────────────────────────────────────────────────────────────────┘
```

Secuencia de una subida:

1. El usuario escribe el ID de cliente. La app llama al custom connector, la Function consulta AS400 con jt400 y devuelve nombre, segundo ID, afiliado padre y propietario. La app los muestra y crea la fila SolicitudIngesta en Dataverse (estado Pendiente).
2. El PCF pide la SAS de subida. La Function verifica en Dataverse que la fila es del usuario y emite SAS de escritura, 15 min, un blob.
3. El PCF sube en bloques directo a Blob. Al terminar, la app actualiza la fila: estado Subido, blobPath, tamaño, hash.
4. Defender escanea el blob. Event Grid dispara el worker con el resultado.
5. El worker: si hay malware, estado Rechazado. Si está limpio, lee el blob como stream, vuelve a resolver metadatos en AS400 (fuente de verdad en el momento del archivado), crea el documento en Content Manager por CMIS con los metadatos obligatorios, actualiza la fila a Archivado con cmisObjectId y borra el blob.
6. El watchdog reencola cualquier solicitud en Subido más de 15 minutos sin resultado. El worker es idempotente: si la fila ya tiene cmisObjectId, no vuelve a subir.

## 2. Qué se puede hacer con Content Manager desde la app

| Operación | ¿Se puede? | Cómo |
| --- | --- | --- |
| Archivar un documento con metadatos obligatorios | Sí, es el núcleo | Worker → CMIS createDocument |
| Ver o descargar un documento ya archivado | Sí | Function trae el documento de CM por CMIS a un contenedor de staging en Blob y emite SAS de lectura de 5 min. Mismo modelo de autorización. Alternativa sin código: deep link al visor web de Content Manager si los usuarios tienen cuenta ahí. |
| Buscar documentos de un cliente en CM | Sí | Function → CMIS Query (lenguaje tipo SQL) → custom connector → galería en la app. Para lo archivado desde esta app, la lista sale directo de Dataverse, sin tocar CM. |
| Actualizar metadatos de un documento | Sí | CMIS updateProperties. Exponer solo si el negocio lo pide. |
| Nueva versión de un documento | Sí, si el tipo de documento en CM es versionable | CMIS checkOut / checkIn |
| Borrar en CM | Técnicamente sí | No exponer. Content Manager es el sistema de registro; el borrado se gobierna allá. |

Un solo custom connector, generado desde la definición OpenAPI de la Function, con autenticación Entra ID. Todas las operaciones de la app pasan por él. Power Automate no participa en el camino de datos.

## 3. Qué hay que construir

| # | Componente | Responsable | Depende de |
| --- | --- | --- | --- |
| 1 | Infraestructura Azure como código: grupo de recursos, cuenta de Storage endurecida, contenedores, Key Vault, Function App Flex Consumption con VNet, Event Grid, App Insights, Defender for Storage, Azure Policy | Cloud / DevOps | Suscripción y VNet existentes |
| 2 | Red: reglas desde la subnet de la Function hacia AS400 (8471/9471, 8476/9476), hacia CM (HTTPS) y salida a Dataverse y Entra; private endpoint y DNS de Storage; CORS y firewall de IP en Storage | Redes | 1 |
| 3 | Identidad: managed identity de la Function; application user en Dataverse con rol custom; app registration del custom connector; app registration del broker con permiso delegado Azure Storage para el OBO (sin app SPA para el PCF, ver §4c); roles RBAC en el contenedor | Seguridad / Identidad | 1 |
| 4 | Dataverse: tabla SolicitudIngesta con estados (Pendiente, Subido, Procesando, Archivado, Rechazado, Error), columnas de trazabilidad, roles de seguridad, habilitar PCF en el entorno | Maker Power Platform | — |
| 5 | Function Java: endpoints HTTP, worker Event Grid, watchdog; módulos DataverseClient, SasBroker, As400Resolver (jt400, consultas del negocio), CmisGateway (OpenCMIS), MetadataMapper; pruebas unitarias | Dev Java senior | 1, 2, 3 |
| 6 | PCF de carga directa: selección de archivo, SAS, bloques en paralelo, progreso, reintentos, envío del token de Storage que devuelve el broker (`tokenStorage`) | Dev TypeScript | 3 |
| 7 | Custom connector desde OpenAPI de la Function, autenticación Entra ID | Maker + Dev | 5 |
| 8 | Canvas app: nueva solicitud, subida, lista, ver documento | Maker | 4, 6, 7 |
| 9 | Flow de notificaciones (opcional) | Maker | 4 |
| 10 | Operación: alertas (poison, 403, blobs viejos, cap antivirus), dashboard, runbook de reproceso, pipeline CI/CD | DevOps | 5 |
| 11 | Revisión de seguridad: modelo de amenazas, pruebas de SAS, revisión de código del broker | Seguridad | 5, 6 |

## 4. Spikes, en orden de riesgo

Cada spike es de 1 a 3 días, con un criterio de éxito escrito antes de empezar. El orden va del sistema que menos conocemos al que más.

| Orden | Id | Pregunta | Éxito si |
| --- | --- | --- | --- |
| 1 | S0 | ¿Una Function en Flex Consumption con VNet integration llega a AS400, a Content Manager y a Dataverse/Entra desde su subnet? | Un endpoint de prueba hace ping TCP a 8471 y 8476, GET al endpoint CMIS de CM y GET a la Web API de Dataverse. Los tres responden. |
| 2 | S4a | ¿OpenCMIS con Browser binding crea un documento en IBM CM V8 con TODOS los metadatos obligatorios? | Documento creado desde Java, visible en CM con metadatos correctos. Mapeo de tipos y propiedades documentado. Es el spike más importante: valida el sistema que peor conocemos. |
| 3 | S6 | ¿jt400 ejecuta las consultas de resolución de cliente desde la Function? | Las consultas del negocio devuelven los datos esperados; codificación de caracteres (CCSID) y TLS resueltos; perfil de solo lectura funciona. |
| 4 | S1 | ¿Un PCF en canvas app sube 100 MB y 250 MB a Blob con SAS desde la red y el navegador del Banco? | Menos de 2 minutos para 100 MB con CORS y firewall de IP activos. |
| 5 | S2 | ¿La Function emite SAS con su managed identity y Shared Key deshabilitado, tras validar acceso en Dataverse? | SAS funciona con Shared Key apagado; usuario sin acceso a la fila recibe 403. |
| 6 | S1b | ¿Se puede ligar la SAS al usuario (`sduoid`) y que el PCF mande junto con ella un token Entra de Storage de ese usuario? (Criterio original: token con MSAL en el PCF; se resolvió con OBO en el broker, ver §4c) | La SAS falla sin token, con el token de otro usuario y el token falla sin la SAS; modo block de la cuenta no rompe la carga. |
| 7 | S3 | ¿Defender for Storage emite el evento de escaneo y dispara la Function? Latencia para 100 MB. | Resultado en menos de 5 minutos; EICAR detectado y marcado. |
| 8 | S4b | ¿El worker procesa 100 MB en streaming sin cargarlo en memoria? | Instancia de 2 GB, heap bajo 500 MB, documento en CM íntegro (hash coincide). |
| 9 | S5 | ¿A qué identidad contabiliza Dataverse las peticiones hechas con suplantación (`CallerObjectId`)? | 50 llamadas con suplantación (≈150 a 200 requests de Dataverse); el análisis de Dataverse del admin center (Usuarios activos) muestra a qué identidad se cuentan las operaciones. El reporte de Power Platform requests excluye Dataverse, así que la atribución de licenciamiento no se puede leer: se mantiene la suplantación y el pool de 25.000 queda como riesgo documentado. |
| 10 | S7 | ¿El custom connector con Entra ID funciona desde la canvas app contra la Function, y la Function valida el token? | Llamada autenticada desde la app; token rechazado si viene de otra app o expirado. |

Prerrequisitos para arrancar S0 y S4a esta semana: grupo de recursos de sandbox con VNet conectada a la red interna, perfil de IBM i de solo lectura, usuario de servicio en Content Manager con permiso de crear documentos en el tipo de documento destino, y las consultas de AS400.

## 4b. Dónde se corre cada spike

Estrategia: probar todo lo posible en un tenant y suscripción propios (fuera del Banco), empaquetar los artefactos y cruzarlos al tenant del Banco solo para lo que depende de sus sistemas.

### Fuera del Banco (tenant y suscripción Azure propios)

| Id | Qué se prueba afuera | Qué queda pendiente de confirmar adentro |
| --- | --- | --- |
| S1 | Mecánica completa del PCF: SAS, bloques en paralelo, progreso, reintentos, 100 y 250 MB | Proxy corporativo, firewall de IP de Storage con los rangos de salida del Banco, navegador corporativo y sus políticas |
| S1b | SAS vinculada al usuario (`sduoid`) con token de Storage obtenido por OBO en el broker (no con MSAL en el PCF) y enviado por el PCF 0.0.5 en cada PUT; modo block en la cuenta (aplicado, efecto no observado) | Consentimiento de administrador del permiso Azure Storage, Conditional Access del tenant del Banco (el OBO lo hereda) y credencial del broker en Key Vault |
| S2 | Broker: managed identity, Shared Key deshabilitado, validación de acceso contra Dataverse (OBO y suplantación), 403 al usuario sin acceso | Application user y rol custom recreados en el entorno del Banco |
| S3 | No se corre afuera (decisión del 2026-10-14, por el costo fijo de ~$9,78/mes por cuenta). Cuantificado en `docs/justificacion-financiera-blob-vs-dataverse.md`, sección 8; el Bicep lo soporta con `habilitarDefender=true` | Todo: evento de resultado de escaneo, latencia, EICAR y disparo de la Function, si la suscripción del Banco tiene Defender for Storage habilitado y presupuestado |
| S4b | Worker en streaming: lectura del blob por private endpoint y entrega a OpenCMIS **contra un servidor CMIS 1.1 de prueba** (Apache Chemistry InMemory o Alfresco Community en contenedor). Valida memoria, bloques y el uso de OpenCMIS con Browser binding | Nada de IBM CM se valida afuera. Ver S4a |
| S5 | Carga de 50 llamadas con suplantación; lectura en el análisis de Dataverse del admin center (el reporte de Power Platform requests excluye Dataverse). Solo permite ver a qué identidad se cuentan las operaciones, no el pool de licenciamiento | Repetir una muestra corta en el Banco: el mecanismo debería ser el mismo, pero el mix de licencias es distinto |
| S7 | Custom connector con Entra ID desde la canvas app hacia la Function; validación del token en la Function | App registrations recreadas en el Banco; política DLP del Banco que permita el custom connector |
| S6 parcial | Mecánica de jt400 (driver, TLS, CCSID) contra un IBM i público de desarrollo como PUB400, si se decide usarlo | Las consultas del negocio solo se prueban contra el AS400 del Banco |

Artefactos que salen de esta fase y se cruzan tal cual: plantillas Bicep de toda la infraestructura, código de la Function, solución de Dataverse con tabla, roles y PCF, definición OpenAPI y del custom connector, runbook de despliegue.

### Dentro del Banco

| Id | Por qué solo adentro |
| --- | --- |
| S0 | Alcance de red desde la subnet de la Function hacia AS400, Content Manager, Dataverse y Entra por la VNet y el ExpressRoute o VPN del Banco |
| S4a | IBM Content Manager V8: Browser binding real, tipos de documento, metadatos obligatorios, usuario de servicio. No existe sustituto afuera |
| S6 | Consultas de resolución de cliente contra el AS400 real, con el perfil de solo lectura |
| S1, S1b, S2, S5, S7 (repetición corta) | Confirmación de lo ya probado afuera bajo las políticas del tenant: DLP, Conditional Access, Managed Environments, firewall de IP de Dataverse, proxy y navegador corporativos |

Riesgos típicos del cruce de tenant, para revisarlos antes de la repetición: política DLP que bloquee el custom connector o el conector de Blob, Conditional Access sobre Azure Storage que el token OBO del broker no pueda satisfacer (`interaction_required`), firewall de IP de Dataverse sin la IP de salida de la Function, DNS privado de Storage sin resolver desde la subnet, y suscripción del Banco sin Defender for Storage.

## 4c. Resultados de los spikes fuera del Banco (2026-10-14)

Detalle, comandos y salidas en `spikes/resultados/`. Todos corridos el 2026-10-13/14 en el tenant y la suscripción propios.

### Veredictos

| Id | Qué probaba | Veredicto | Evidencia clave |
| --- | --- | --- | --- |
| S1 | PCF sube 100 MB directo a Blob con SAS de un solo blob, sin Dataverse ni Power Automate | Confirmado | 100 MB en 25 bloques de 4 MB, 15,1 s del primer al último bloque, 53 Mbps, 0 errores. Storage tarda 0,24 s por bloque: el techo es el uplink del usuario (ni 8 MB de bloque ni 8 carriles lo movieron) |
| S2 | Broker con mínimo privilegio: Shared Key apagado, Contributor en el contenedor, SAS de un solo blob, autorización contra Dataverse | Confirmado | PUT al blob firmado 201; otro blob 403; listar el contenedor 403. Dataverse: 200 con acceso, 403 sin rol, 403 fila inexistente (no revela existencia), 409 en estado Subido, 400 con tamaño 0 o nombre con `..` |
| S7 | Custom connector con Entra ID y Function protegida por Easy Auth | Confirmado | 401 sin token, 200 con token, conexión creada desde Studio con consentimiento del usuario, subida de 100 MB en 19,3 s (43,4 Mbps) con la SAS entregada por el conector |
| S4b | Worker lleva 100 MB de Blob a CMIS 1.1 (Browser binding) en streaming | Confirmado contra OpenCMIS InMemory 1.1.0 (no contra IBM CM) | 100 MB con `-Xmx64m` en 8,9 s (heap máx. muestreado 60 MiB), contenido idéntico, `buscarPorSolicitud` devuelve el mismo objectId |
| S1b | SAS ligada al usuario (`sduoid`) más token de Storage por OBO en el broker | Confirmado; política de cuenta abierta (hallazgo 1) | SAS sola 403 `AuthenticationFailed`; bearer solo 403 `AuthorizationPermissionMismatch`; bearer de otro principal 403; SAS + bearer del usuario 201. Desde Studio (12:30 UTC): 25 `PutBlock` + `PutBlockList` 201 con `RequesterUpn` del usuario, fila a Subido en el mismo segundo |
| S6 | Mecánica de jt400 contra PUB400 (IBM i 7.5): signon, contraseña expirada, TLS, consultas | Confirmado (mecánica; consultas y red reales solo en el Banco) | jt400 21.0.7 desde Java 21, TLS 9471 sin configurar truststore, conexión 3,6 s, consulta ~120 ms reutilizando la `PreparedStatement`, ciclo conectar+consultar+cerrar 4,5 s con TLS |
| S3 | Defender for Storage | No se corre, por decisión | Costo fijo de ~$9,78/mes por cuenta, cuantificado en `docs/justificacion-financiera-blob-vs-dataverse.md` §8 (≈ $20/mes en el escenario chico, ≈ $109/mes en el mediano) |
| S5 | Atribución de requests de Dataverse con suplantación | En lectura; cambió de naturaleza (hallazgo 3) | 50 llamadas el 2026-10-14 07:34 UTC (≈150 a 200 requests de Dataverse). El reporte de requests del admin center excluye Dataverse; solo se lee el análisis de Dataverse |

### Hallazgos abiertos

Nivel A, problemas abiertos a seguir:

1. La política de cuenta `requireUserBoundUserDelegationSas=true` con acción `Block` está aplicada (ARM api 2025-06-01 la devuelve) pero no bloquea una SAS no ligada: un GET con SAS de lectura sin `sduoid` devuelve 206, re-probado más de una hora después. El diseño no depende de ella (el broker siempre firma con `sduoid`). Reprobar en unos días; si persiste, abrir caso con Microsoft.
2. Los 403 `AuthenticationFailed` no aparecen en `StorageBlobLogs`; auditar intentos con SAS robadas requiere métricas de transacciones (dimensión `ResponseType`), no logs.
3. S5 cambió de naturaleza: el reporte "Microsoft Power Platform requests" del admin center solo incluye Power Automate (Learn: `api-request-limits-allocations`, FAQ) y la aplicación de límites no empieza hasta al menos seis meses después de que sea GA. Solo se puede leer a qué identidad Dataverse contabiliza las operaciones (Administrar → Dataverse → análisis → Usuarios activos). Decisión: mantener suplantación; el pool de 25.000 queda como riesgo documentado.
4. Costo real del programa: Cost Management aún no publica datos de la suscripción.

Nivel B, decisiones de diseño a llevar a producción:

5. Pool de conexiones jt400 (HikariCP chico por instancia): hoy una conexión por invocación, 4,5 s con TLS contra ~120 ms por consulta en conexión abierta.
6. Secreto del broker (OBO) a Key Vault o credencial federada con la identidad administrada; lo mismo `AS400_PASSWORD` y la credencial CMIS.
7. El token de Storage viaja en la respuesta del conector y vive en una variable Power Fx: nunca persistirlo en Dataverse ni usar la operación desde Power Automate sin salidas seguras. Vida del token 60 a 90 min, SAS 15 min.
8. OpenCMIS `DefaultHttpInvoker` siempre usa `Transfer-Encoding: chunked` (65535) y no tiene modo de largo fijo; si un proxy o IHS delante de CM V8 lo rechaza, solo se verá en el Banco (S4a).
9. CORS `*` en Storage y Function porque Power Apps corre desde orígenes de isla variables (por ejemplo `https://authoring.us-il301.gateway.prod.island.powerapps.com`). No es frontera de seguridad (la dan la SAS y el bearer). Storage documenta comodín de subdominio en `AllowedOrigins` (`https://*.powerapps.com`, a probar con varios niveles); la Function solo la llama el conector, así que su CORS puede cerrarse. El Banco decide entre el comodín o un proxy.
10. Acceso condicional: el OBO hereda las políticas del usuario sobre Azure Storage; si piden MFA o dispositivo que el token del conector no satisface, Entra devuelve `interaction_required` y el broker responde 502. Alinear políticas antes de salir.

Nivel C, solo dentro del Banco:

11. S4a contra CM V8 real: item type y propiedades reales (no texto), idempotencia por atributo, versionado, TLS con CA interna, credenciales reales, límites y timeouts.
12. S6 real: puertos 9471/9476 desde la VNet, certificado del IBM i, perfil de servicio de solo lectura con política de expiración, consultas reales, CCSID con acentos y ñ.
13. S0 de red y repetición corta de S5 con el mix de licencias del Banco.

### Estado del programa

Todo lo que se podía probar afuera del Banco está confirmado. S5 queda en lectura (y ya no puede decidir entre suplantación y OBO por pool). S3 no se corre, por decisión, y su costo está cuantificado en `docs/justificacion-financiera-blob-vs-dataverse.md` §8.

## 5. Después de los spikes

Con S0, S4a, S6 y S1 en verde se congela el diseño y se pasa a la etapa de definición formal: especificación de la tabla, contrato OpenAPI de la Function, mapeo de metadatos, estimación por componente y plan de entrega. Si S1 falla, el plan B documentado es la columna de archivo de Dataverse con chunks grandes (ADR-001).
