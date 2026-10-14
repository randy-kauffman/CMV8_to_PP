# Function CMV8 (Java 21, Flex Consumption)

Broker de SAS para la carga directa a Blob, resolución de cliente en AS400 (jt400) y worker que lleva el
archivo a IBM Content Manager V8 por CMIS 1.1 Browser binding (OpenCMIS). Ver `docs/plan-solucion.md`.

## Flujo

1. Power Apps crea la fila `SolicitudIngesta` (estado Creada) y pide `POST /api/sas/subida`.
2. La Function verifica en Dataverse que el usuario puede escribir la fila, guarda `cmv8_rutablob` y
   devuelve una SAS de delegación de usuario (create + write, un blob, HTTPS, 15 min).
3. El PCF sube en bloques de 4 MiB directo a Blob y la app pone la fila en Subido.
4. Defender for Storage escanea y publica en el tópico de Event Grid → `OnScanResult`.
   Sin Defender: `OnBlobCreated` desde el tópico de sistema (no hay escaneo).
5. Limpio → mensaje a la cola `COLA_INGESTA`. Malicioso → fila Rechazado y blob borrado.
6. `ProcesarIngesta` (cola): Procesando → (AS400 si falta el segundo id) → CMIS createDocument en streaming
   → Archivado + `cmv8_cmisobjectid` → borra el blob. Ante falla: Error y reintento de la cola
   (5 intentos, luego `<cola>-poison`).
7. `Watchdog` (cada 15 min) reencola las filas en Subido sin cambios hace más de 15 min.

## Estructura (hexagonal)

```
src/main/java/com/banco/cmv8/
├── domain/                 Modelo y reglas puras (sin Azure)
│   ├── SolicitudIngesta, EstadoSolicitud, Cliente, ContextoLlamador, MensajeIngesta, SasEmitida
│   ├── PoliticaSas         ruta {solicitudId}/{nombreSaneado}, saneo, límite de tamaño, vigencia
│   ├── ResultadoEscaneo
│   └── port/               RepositorioSolicitudes, AutorizadorSolicitud, EmisorSas, AlmacenAdjuntos,
│                           ArchivoDocumental, ResolutorCliente, ColaIngesta
├── application/            Casos de uso: EmitirSasSubida, EmitirSasLectura, ProcesarIngesta,
│                           RegistrarResultadoEscaneo, ReencolarEstancadas, ResolverCliente
├── adapters/in/            Funciones: SasHttp, ClientesHttp, SaludHttp, EventosEventGrid,
│                           ColaIngestaFunction, WatchdogTimer (+ LectorLlamador, UbicacionBlob)
├── adapters/out/
│   ├── blob/               EmisorSasBlob, AlmacenAdjuntosBlob (azure-storage-blob + identidad administrada)
│   ├── dataverse/          ClienteDataverse (OData v4 con java.net.http), RepositorioSolicitudesDataverse,
│   │                       AutorizadorSolicitudDataverse, AutorizadorPermisivo, NombresDataverse
│   ├── cmis/               ArchivoDocumentalCmis, MapeoPropiedadesCmis (OpenCMIS Browser binding)
│   ├── as400/              ResolutorClienteJt400
│   ├── cola/               ColaIngestaSalida (acumula mensajes para @QueueOutput)
│   └── memoria/            RepositorioSinDataverse (solo spike S1)
└── config/                 Configuracion (app settings), Fabrica (raíz de composición), Json
```

## Endpoints

| Función | Ruta | Respuesta |
| --- | --- | --- |
| `SasSubida` | `POST /api/sas/subida` `{solicitudId, nombreArchivo, tamanoBytes, contentType}` | 200 `{uploadUrl, rutaBlob, expiraUtc, bloqueBytes, versionServicio}`; 400 validación; 403 sin acceso; 404 sin fila; 409 estado |
| `SasLectura` | `GET /api/sas/lectura/{solicitudId}` | 200 `{downloadUrl, expiraUtc}`; 409 si ya está archivado (lectura desde CM pendiente) |
| `ResolverCliente` | `POST /api/clientes/resolver` `{id}` | 200 `Cliente`; 404 |
| `Salud` | `GET /api/salud` | `{estado:"ok", version}` |
| `OnScanResult` | Event Grid `Microsoft.Security.MalwareScanningResult` | encola o rechaza |
| `OnBlobCreated` | Event Grid `Microsoft.Storage.BlobCreated` | encola |
| `ProcesarIngesta` | cola `%COLA_INGESTA%` | worker |
| `Watchdog` | timer `0 */15 * * * *` | reencola |

Los nombres `OnScanResult` y `OnBlobCreated` coinciden con las suscripciones de `infra/main.bicep`.

### Autenticación de los endpoints HTTP

Los HTTP triggers son `AuthorizationLevel.ANONYMOUS`: la autenticación Entra la impone la autenticación
integrada de App Service (Easy Auth) en S7. La Function toma la identidad así:

1. `X-MS-CLIENT-PRINCIPAL-ID` / `X-MS-CLIENT-PRINCIPAL` (los inyecta App Service después de validar el token).
2. Si no están, los claims `oid` y `tid` del bearer **sin validar la firma**, solo para reenviarlos.

Hasta que S7 active Easy Auth, el `oid` es declarativo: cualquiera puede fabricar un token con otro `oid`.
No exponer la Function con datos reales antes de S7.

## App settings

Las que ya define `infra/main.bicep`:

| Setting | Uso |
| --- | --- |
| `AzureWebJobsStorage__accountName` / `__credential` / `__clientId` | Storage del host, colas y timers (identidad) |
| `AZURE_CLIENT_ID` | Identidad administrada asignada por el usuario para `DefaultAzureCredential` |
| `ADJUNTOS_STORAGE_ACCOUNT` | Nombre de la cuenta de adjuntos (informativo) |
| `ADJUNTOS_BLOB_ENDPOINT` | `https://<cuenta>.blob.core.windows.net/` |
| `ADJUNTOS_CONTAINER` | `cmv8-ingesta` |
| `DATAVERSE_URL` | `https://<org>.crm.dynamics.com` |
| `SAS_UPLOAD_MINUTOS` | Vigencia de la SAS de subida (15). Tope: 60, igual a `sasPolicy` de la cuenta |
| `SAS_READ_MINUTOS` | Vigencia de la SAS de lectura (5) |
| `SAS_BLOQUE_MB` | Tamaño de bloque en MiB que el broker recomienda al PCF (4). Rango 1 a 100; sirve para experimentar throughput sin republicar el PCF |

Nuevas (agregar a Bicep o a la configuración de la Function App):

| Setting | Defecto | Uso |
| --- | --- | --- |
| `COLA_INGESTA` | `ingesta-pendiente` | Nombre de la cola. **Obligatoria como app setting**: las anotaciones usan `%COLA_INGESTA%` y el host no arranca esas funciones si no existe |
| `AUTORIZACION_MODO` | `dataverse` | `dataverse` = suplantación con `CallerObjectId` + `RetrievePrincipalAccess`. `ninguna` = permite todo (solo spike S1; si además falta `DATAVERSE_URL`, usa un repositorio sustituto) |
| `SAS_LIGADA_USUARIO` | `false` | `true` = SAS ligada al usuario (`sduoid`, S1b). Exige `oid` + bearer del llamador (si faltan, 401) y devuelve `tokenStorage` / `tokenStorageExpiraUtc` obtenidos por OBO |
| `BROKER_CLIENT_ID` | — | Client id de la app registration del broker (audiencia de Easy Auth). Obligatoria con `SAS_LIGADA_USUARIO=true` |
| `BROKER_TENANT_ID` | — | Tenant Entra del broker (autoridad del OBO). Obligatoria con `SAS_LIGADA_USUARIO=true` |
| `BROKER_CLIENT_SECRET` | — | Secreto de la app del broker para el OBO hacia `https://storage.azure.com/user_impersonation`. **Nunca en archivos ni en Bicep**: en el spike se fija fuera de banda con `az functionapp config appsettings set` (y `infra/desplegar.sh` lo preserva); en el Banco, referencia `@Microsoft.KeyVault(...)` o, mejor, certificado |
| `TAMANO_MAXIMO_BYTES` | `1073741824` | Límite de tamaño declarado (1 GB) |
| `ESTANCADA_MINUTOS` | `15` | Antigüedad para el watchdog |
| `ESCANEO_REQUERIDO` | `false` | `true` = el worker exige la etiqueta de Defender `Malware Scanning scan result = No threats found` antes de archivar. Activar cuando Defender esté habilitado |
| `CMIS_URL` | — | URL del Browser binding, por ejemplo `https://cm/cmis/browser` |
| `CMIS_USER` / `CMIS_PASSWORD` | — | Usuario de servicio de Content Manager (en Azure: referencia a Key Vault) |
| `CMIS_REPOSITORY_ID` | vacío | Vacío = primer repositorio |
| `CMIS_FOLDER_PATH` | — | Carpeta destino, por ejemplo `/Ingesta` |
| `CMIS_TYPE_ID` | `cmis:document` | Tipo de documento de CM |
| `CMIS_PROPIEDADES_JSON` | `{}` | Mapa `propiedadCM → "${campo}"` o literal. Campos: `idCliente`, `idCliente2`, `nombreCliente`, `afiliadoPadre`, `idDueno`, `solicitudId`, `nombreArchivo`, `contentType` |
| `AS400_HOST` / `AS400_USER` / `AS400_PASSWORD` | — | Perfil de solo lectura (contraseña en Key Vault) |
| `AS400_SECURE` | `true` | TLS (puertos 9471/9476) |
| `AS400_SQL_CLIENTE` | — | SQL con un solo `?` que devuelve `IDCLIENTE, IDCLIENTE2, NOMBRE, AFILIADOPADRE, IDDUENO` |

Nombre del documento en CM: si `CMIS_PROPIEDADES_JSON` mapea alguna propiedad a `${solicitudId}`, se usa el
nombre de archivo y la idempotencia consulta esa propiedad. Si no, el nombre lleva el `solicitudId` como
prefijo (`<id>_<archivo>`) para que dos archivos homónimos de solicitudes distintas no se confundan.

## Qué ejercita cada spike

| Spike | Qué parte del código |
| --- | --- |
| S1 | `SasSubida` con `AUTORIZACION_MODO=ninguna` (sin Dataverse) + PCF |
| S1b | `SAS_LIGADA_USUARIO=true`: `EmisorSasBlob` agrega `sduoid` y pide la clave de delegación con el tenant del usuario; `IntercambiadorTokenObo` (MSAL4J, OBO) devuelve el token de Storage del usuario que el PCF envía como `Authorization: Bearer`. Ver `spikes/resultados/S1b-sas-ligada-usuario.md` |
| S2 | `SasSubida`/`SasLectura` con `AUTORIZACION_MODO=dataverse`: identidad administrada, Shared Key apagado, 403 a quien no tiene acceso (`AutorizadorSolicitudDataverse`) |
| S3 | `OnScanResult` (EICAR → Rechazado y blob borrado) y `ESCANEO_REQUERIDO=true` |
| S4b | `ProcesarIngesta` contra un servidor CMIS de prueba: `AlmacenAdjuntosBlob.abrir` lee por rangos de 4 MiB y OpenCMIS envía con chunked streaming de 64 KiB; medir heap con 100 MB |
| S5 | Las llamadas con `CallerObjectId` (suplantación) del autorizador |
| S6 | `ResolverCliente` con la consulta real en `AS400_SQL_CLIENTE` (CCSID, TLS) |
| S7 | Activar Easy Auth en la Function App; la Function pasa a leer `X-MS-CLIENT-PRINCIPAL-*` |

## Ejecutar en local

Requisitos: Java 21, Maven, Azure Functions Core Tools v4, `az login` con un usuario que tenga
`Storage Blob Data Contributor` + `Storage Blob Delegator` en la cuenta de adjuntos.

```bash
cp local.settings.json.example local.settings.json   # completar valores (no se versiona)
mvn clean package
func start --script-root target/azure-functions/cmv8-func-CAMBIAR
# o: mvn azure-functions:run
```

- Azurite sirve para `AzureWebJobsStorage` (colas y timers), pero **no** para los adjuntos: Azurite no emite
  SAS de delegación de usuario. Los adjuntos tienen que estar en una cuenta real.
- En local `DefaultAzureCredential` usa la sesión de `az login`; deje `AZURE_CLIENT_ID` vacío.

## Desplegar

La Function App Flex Consumption la crea `infra/main.bicep`; acá solo se publica el código.

```bash
mvn clean package
# Opción 1: plugin de Maven
mvn azure-functions:deploy -DfunctionAppName=<nombre> -DresourceGroup=<rg>
# Opción 2: Core Tools, desde function/
func azure functionapp publish <nombre> --java
```

## host.json

- `functionTimeout` en 1 hora: subir 100 MB a Content Manager por un túnel (VPN/ExpressRoute) puede tardar
  varios minutos. En Flex Consumption el valor por defecto es 30 min.
- Colas: `maxDequeueCount` 5, `visibilityTimeout` 2 min, `batchSize` 4 (pocos archivos grandes en paralelo
  por instancia de 2 GB).

## Pendientes conocidos

- Lectura de documentos ya archivados (traer de CM a staging y emitir SAS): `EmitirSasLectura` responde 409.
- Leer etiquetas de índice (`ESCANEO_REQUERIDO=true`) requiere `blobs/tags/read`; verificar en S3 si el rol
  del contenedor lo incluye, si no, asignar uno que lo tenga.
- Propiedades CM no texto (enteros, fechas): `MapeoPropiedadesCmis` envía texto; ajustar tras S4a.
- Pool de conexiones jt400 si la latencia lo pide (S6).
