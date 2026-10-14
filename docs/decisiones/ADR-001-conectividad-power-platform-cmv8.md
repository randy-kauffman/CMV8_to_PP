# ADR-001: Conectividad Power Platform → IBM Content Manager V8 (CMIS)

- Fecha: 2026-10-13
- Estado: Aceptada (dirección elegida, diseño detallado pendiente)
- Modificada por ADR-002 (2026-10-13): el binario ya no vive en una columna de archivo de Dataverse sino en Azure Blob Storage, y el disparador del worker pasa a ser Event Grid. Dataverse conserva el registro y el estado. Ver `ADR-002-blob-storage-para-binarios.md`.

## Contexto

Queremos que Power Apps y Power Automate archiven documentos (incluidos videos de hasta 100 MB) en IBM Content Manager V8 (CMV8) a través de su API CMIS 1.1. CMV8 corre en un servidor Red Hat accesible solo desde la red interna. La red interna permite salida a internet por 443 pero no entrada.

CMV8 exige metadatos obligatorios que hoy se resuelven consultando AS400 (IBM i): el cliente tiene dos ID únicos; dado uno, hay que obtener el otro, el nombre, y si es afiliado, el afiliado padre y el ID del propietario del negocio. Hoy lo hace un programa de escritorio de un tercero (usa usuario y contraseña contra AS400; driver desconocido).

Infraestructura disponible: suscripción Azure, Managed Environments en Power Platform, VMs en Azure con línea de red a AS400 y a CMV8. Los entornos de Power Platform NO usan clave de cifrado propia (BYOK).

## Opciones evaluadas

### A. On-premises data gateway + custom connector — DESCARTADA

- El gateway tiene un límite de 2 MB por operación de escritura y 8 MB comprimidos de respuesta. No sirve para ningún archivo, mucho menos videos de 100 MB.
- Además requiere una máquina Windows dedicada y es sincrónico.

### B. Push desde Power Platform hacia un worker en Azure — ELEGIDA

- Dataverse como mesa de entrada: tabla `SolicitudIngesta` con columna de archivo (máximo configurado al crearla, con margen sobre 100 MB).
- Trigger push de Dataverse ("When a row is added", callback registration) → flow → custom connector → Azure Function. El flow pasa SOLO el ID de la solicitud, nunca el archivo (límite de mensaje de Power Automate: 100 MB de payload total).
- La Function baja el archivo directo de Dataverse por Web API en chunks, resuelve metadatos en AS400, crea el documento por CMIS y actualiza el estado.
- Sabor B1 (elegido para arrancar): Function con endpoint público protegido por Entra ID. NO requiere delegar el entorno de Power Platform a una VNet.
- Sabor B2 (reserva): Function con private endpoint + VNet delegada en Power Platform. Solo si la política de seguridad prohíbe endpoints públicos. Implica que TODOS los conectores soportados del entorno salen por la subnet delegada.

### C. Worker on-prem que hace pull contra Dataverse — RESERVA

- Worker en la VM que consulta Dataverse cada 15-30 s por solicitudes pendientes. Solo tráfico saliente 443. Cero VNet, cero custom connector.
- Descartada por ahora porque el equipo prefiere el disparo push y ya cuenta con Azure. Sigue siendo válida como plan B si B1 se traba en seguridad o licencias.

### Azure API Management — NO POR AHORA

- Una sola API con un solo consumidor en el mismo tenant. APIM agrega costo mensual y operación sin aportar valor hoy.
- Reconsiderar si: (1) la organización ya tiene APIM y la gobernanza exige que toda API pase por ahí (usar "Export to Power Platform" para generar el custom connector), o (2) otros sistemas van a consumir esta API.

### Conector DB2 de Power Automate para consultar AS400 — DESCARTADA

- Solo hace CRUD por tabla (sin stored procedures ni SQL libre en Power Automate), exige gateway igual, 100 llamadas/min por conexión, licencia premium.
- La regla de afiliado/padre/propietario es lógica de negocio y debe vivir en un solo lugar: el servicio de resolución.

## Decisión

Opción B1. Azure Function como worker, con tres funciones:

1. HTTP trigger: valida el ID, encola, responde 202 (una función HTTP tiene 230 s máximo para responder, sin importar el plan).
2. Queue trigger: descarga en chunks desde Dataverse, resuelve AS400, CMIS createDocument, PATCH estado, borra el archivo de Dataverse (capacidad File).
3. Timer trigger: watchdog que reencola solicitudes en Pendiente más de X minutos.

Identidad: managed identity de la Function registrada como application user de Dataverse (se acepta el Application ID de la managed identity en el admin center), con rol custom limitado a la tabla de solicitudes.

Plan de hosting: **Flex Consumption** (serverless, escala a cero, VNet integration, sin contenedor que mantener). Java 17 o 21 están soportados en Flex Consumption. El plan Consumption clásico es legacy y no soporta VNet.

Lenguaje: **Java** (decidido 2026-10-13, ver sección de acceso a AS400). Librerías: jt400 (JTOpen) para AS400 por JDBC, Apache Chemistry OpenCMIS con `BindingType.BROWSER` para CMIS 1.1, azure-identity para la managed identity, y HTTP/OData a mano contra la Web API de Dataverse (no hay SDK oficial de Dataverse para Java).

```mermaid
sequenceDiagram
    participant U as Usuario (Canvas App)
    participant D as Dataverse
    participant F as Flow
    participant H as Function HTTP
    participant Q as Cola
    participant W as Function Worker
    participant A as AS400
    participant C as Content Manager (CMIS)

    U->>D: Crea SolicitudIngesta + sube video (<=100 MB)
    D-->>F: Trigger "row added" (push)
    F->>H: POST /ingest { solicitudId } (Entra ID)
    H->>Q: Encola solicitudId
    H-->>F: 202 Accepted
    Q-->>W: Dispara
    W->>D: Descarga archivo en chunks (managed identity)
    W->>A: Resuelve IDs, nombre, afiliado padre, propietario
    W->>C: createDocument + metadatos obligatorios
    W->>D: PATCH estado=Archivado, cmisObjectId; borra archivo
    D-->>U: La app muestra el estado
```

## Consecuencias

- Power Automate queda fuera del movimiento de datos pesados. Solo orquesta IDs y notificaciones.
- Dataverse es staging y auditoría, no archivo. El worker borra el binario al archivar.
- Reintentos y cola de mensajes envenenados los provee la plataforma de Functions.
- Alternativa pro-code al flow: webhook nativo de Dataverse (Plugin Registration Tool, autenticación WebhookKey) apuntando a la Function HTTP.

## Acceso a AS400 desde la Function (decidido 2026-10-13)

Opciones evaluadas:

- (a) IBM i Access ODBC para Linux — evaluada y descartada. Driver nativo del paquete "ACS Linux Application Package" (descarga desde IBM Entitled Software Support con entitlement de IBM i 7.2+). Obliga a contenedor custom (Azure Container Apps o Premium; Flex Consumption no soporta contenedores), a mantener la imagen base mes a mes, y deja abierta la duda de la licencia 5770-XW1.
- (b) Paquete NuGet Net.IBM.Data.Db2 — descartada: IBM exige licencia Db2 Connect para conectar a Db2 for i.
- (c) Servicio REST en el IBM i (Integrated Web Services) — descartada por ahora: el equipo de IBM i de la empresa tiene poca experiencia y podría tardar meses. Sigue siendo la opción más limpia a futuro.
- (d) **JTOpen/jt400 — ELEGIDA.** JDBC puro Java, mismo código que el IBM Toolbox for Java, open source bajo IBM Public License, sin costo, publicado en Maven Central. Sin driver nativo, sin contenedor, sin descarga desde IBM, sin pregunta de licencia. Permite Flex Consumption. Implica escribir la Function en Java; el equipo aceptó el cambio de lenguaje.

Motivo de la elección: no depender del equipo de IBM i, de descargas con entitlement ni de licencias adicionales, y evitar mantener una imagen de contenedor.

Lo que el cambio a Java NO resuelve (sigue pendiente igual):

- Perfil de usuario de IBM i dedicado al servicio, con permisos de solo lectura sobre las tablas de clientes/afiliados. No reutilizar el usuario del programa del tercero. Contraseña en Key Vault.
- Puertos de los host servers de IBM i abiertos desde la VNet de la Function: base de datos 8471 (9471 con TLS) y signon 8476 (9476 con TLS). jt400 usa los mismos host servers que ODBC.
- ~~Conocer QUÉ tablas y QUÉ consultas resuelven cliente, afiliado padre y propietario.~~ Resuelto: el responsable del proyecto conoce las consultas y las entregará en la etapa de diseño detallado del servicio de resolución.

## Licenciamiento del acceso de la Function a Dataverse (verificado 2026-10-13)

- El application user de Dataverse (la managed identity de la Function) **no requiere licencia**. Es un usuario no interactivo sin costo por diseño.
- Lo que SÍ consume es el **pool de Power Platform requests para usuarios no licenciados**, compartido por TODOS los application users, usuarios no interactivos, administrativos y SYSTEM del tenant, por 24 horas:
  - Tenant con solo licencias Power Apps / Power Automate: 25.000 requests base, sin acumulación por licencia.
  - Tenant con Dynamics 365 Enterprise o Professional: 500.000 base + 5.000 por licencia, hasta 10.000.000.
  - Si hay ambos tipos, aplica el mayor.
- Cada operación CRUD contra Dataverse cuenta como un request, incluidas las lecturas. Una descarga de 100 MB en chunks de 4 MB son 25 GET. Estimación por documento con chunks de 4 MB: ~28 requests (lectura de la fila, 25 chunks, PATCH de estado, DELETE del archivo). 500 docs/día ≈ 14.000 requests/día del pool.
- Límites de protección del servicio (independientes de las licencias): 6.000 requests por 5 minutos por usuario y por servidor web, 20 minutos de ejecución acumulada por 5 minutos, 52 concurrentes. Con el volumen previsto no se alcanzan; igual el cliente debe respetar `Retry-After` ante un 429.
- La aplicación estricta de los límites de entitlement todavía no está activa (Microsoft la anuncia con al menos seis meses de aviso tras la GA de los reportes), pero el diseño debe respetarlos.

Decisiones de diseño derivadas:

- Descargar con el chunk más grande que la API acepte (probar descarga en una sola petición o chunks de 16 a 32 MB en el spike inicial) para reducir requests por documento.
- Minimizar operaciones por documento: el mensaje de la cola lleva los datos de la fila que el worker necesita, para evitar el GET inicial cuando sea posible.
- Pendiente: confirmar si el tenant del Banco tiene licencias Dynamics 365 Enterprise/Professional (define si el pool es de 25.000 o de 500.000+ por día) y qué otras integraciones ya consumen ese pool.

## Pendientes
- Confirmar si seguridad acepta endpoint público con Entra ID (B1) o exige private endpoint (B2).
- Confirmar licencias Dynamics 365 en el tenant y consumo actual del pool de requests no licenciados (ver sección anterior).
- Definir el modelo de la tabla `SolicitudIngesta` (estados, errores, cmisObjectId, tamaño máximo de archivo).
