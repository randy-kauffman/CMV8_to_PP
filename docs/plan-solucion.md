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
| 3 | Identidad: managed identity de la Function; application user en Dataverse con rol custom; app registration del custom connector; app registration SPA para el PCF (MSAL); roles RBAC en el contenedor | Seguridad / Identidad | 1 |
| 4 | Dataverse: tabla SolicitudIngesta con estados (Pendiente, Subido, Procesando, Archivado, Rechazado, Error), columnas de trazabilidad, roles de seguridad, habilitar PCF en el entorno | Maker Power Platform | — |
| 5 | Function Java: endpoints HTTP, worker Event Grid, watchdog; módulos DataverseClient, SasBroker, As400Resolver (jt400, consultas del negocio), CmisGateway (OpenCMIS), MetadataMapper; pruebas unitarias | Dev Java senior | 1, 2, 3 |
| 6 | PCF de carga directa: selección de archivo, SAS, bloques en paralelo, progreso, reintentos, token MSAL | Dev TypeScript | 3 |
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
| 6 | S1b | ¿El PCF obtiene token Entra para Storage con MSAL y usa SAS vinculada al usuario? | La SAS falla con el token de otro usuario; modo block de la cuenta no rompe la carga. Si no es viable, se documenta y se queda la defensa por vigencia, un blob, IP y HTTPS. |
| 7 | S3 | ¿Defender for Storage emite el evento de escaneo y dispara la Function? Latencia para 100 MB. | Resultado en menos de 5 minutos; EICAR detectado y marcado. |
| 8 | S4b | ¿El worker procesa 100 MB en streaming sin cargarlo en memoria? | Instancia de 2 GB, heap bajo 500 MB, documento en CM íntegro (hash coincide). |
| 9 | S5 | ¿A quién se atribuyen las peticiones a Dataverse hechas con suplantación y con OBO? | 1.000 llamadas de cada tipo; el reporte de requests del admin center muestra dónde cayeron. Se elige el mecanismo que no consuma el pool de no licenciados. |
| 10 | S7 | ¿El custom connector con Entra ID funciona desde la canvas app contra la Function, y la Function valida el token? | Llamada autenticada desde la app; token rechazado si viene de otra app o expirado. |

Prerrequisitos para arrancar S0 y S4a esta semana: grupo de recursos de sandbox con VNet conectada a la red interna, perfil de IBM i de solo lectura, usuario de servicio en Content Manager con permiso de crear documentos en el tipo de documento destino, y las consultas de AS400.

## 4b. Dónde se corre cada spike

Estrategia: probar todo lo posible en un tenant y suscripción propios (fuera del Banco), empaquetar los artefactos y cruzarlos al tenant del Banco solo para lo que depende de sus sistemas.

### Fuera del Banco (tenant y suscripción Azure propios)

| Id | Qué se prueba afuera | Qué queda pendiente de confirmar adentro |
| --- | --- | --- |
| S1 | Mecánica completa del PCF: SAS, bloques en paralelo, progreso, reintentos, 100 y 250 MB | Proxy corporativo, firewall de IP de Storage con los rangos de salida del Banco, navegador corporativo y sus políticas |
| S1b | SAS vinculada al usuario con token MSAL desde el PCF; modo block en la cuenta | Consentimiento de administrador y Conditional Access del tenant del Banco |
| S2 | Broker: managed identity, Shared Key deshabilitado, validación de acceso contra Dataverse (OBO y suplantación), 403 al usuario sin acceso | Application user y rol custom recreados en el entorno del Banco |
| S3 | Defender for Storage: evento de resultado de escaneo, latencia, EICAR, disparo de la Function por Event Grid | Que la suscripción del Banco tenga Defender for Storage habilitado y presupuestado |
| S4b | Worker en streaming: lectura del blob por private endpoint y entrega a OpenCMIS **contra un servidor CMIS 1.1 de prueba** (Apache Chemistry InMemory o Alfresco Community en contenedor). Valida memoria, bloques y el uso de OpenCMIS con Browser binding | Nada de IBM CM se valida afuera. Ver S4a |
| S5 | Atribución de peticiones (suplantación vs OBO) en el reporte de requests del admin center del tenant propio | Repetir una muestra corta en el Banco: el mecanismo debería ser el mismo, pero el mix de licencias es distinto |
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

Riesgos típicos del cruce de tenant, para revisarlos antes de la repetición: política DLP que bloquee el custom connector o el conector de Blob, Conditional Access que exija dispositivo administrado para el token MSAL del PCF, firewall de IP de Dataverse sin la IP de salida de la Function, DNS privado de Storage sin resolver desde la subnet, y suscripción del Banco sin Defender for Storage.

## 5. Después de los spikes

Con S0, S4a, S6 y S1 en verde se congela el diseño y se pasa a la etapa de definición formal: especificación de la tabla, contrato OpenAPI de la Function, mapeo de metadatos, estimación por componente y plan de entrega. Si S1 falla, el plan B documentado es la columna de archivo de Dataverse con chunks grandes (ADR-001).
