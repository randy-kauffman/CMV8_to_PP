# Function CMV8 (Java 21, Flex Consumption)

Broker de SAS para la carga directa a Blob, worker dirigido por el catálogo de tipos de documento que lleva el
archivo a IBM Content Manager V8 por CMIS 1.1 Browser binding (OpenCMIS), resolución de metadatos en AS400 (jt400)
y operaciones de administración del catálogo. Es la v2 del modelo de datos: `docs/decisiones/ADR-003-v2-modelo-de-datos.md`.

## Flujo

1. La app crea N filas en SU tabla de adjuntos (lookup al registro padre, columnas reservadas) y pide `POST /api/sas/lote`.
2. Por cada adjunto el broker hace UNA llamada a Dataverse suplantando al usuario (PATCH de `rutablob` con
   representación: 403 = no puede escribir) y devuelve N SAS de delegación de usuario y UN token de Storage (OBO).
   Escribe el registro operativo en la Azure Table `ingesta`.
3. El PCF sube en bloques directo a Blob con las dos llaves y la app pone la fila en Subido.
4. `OnBlobCreated` (o `OnScanResult` con Defender) marca Subido en la Azure Table y encola. Dataverse no se toca.
5. `ProcesarIngesta` (cola): lee el registro operativo y el catálogo en caché; resuelve y valida las propiedades
   contra la definición VIVA del tipo; según el tipo archiva en CM (`<id>_<archivo>`), mueve a `cmv8-almacenados`
   (solo-blob, diferido, retenido) o detiene por conflicto de catálogo / observa por datos. UN PATCH a Dataverse con el resultado.
6. `Watchdog` (15 min) reencola lo estancado leyendo la Azure Table; `VigilarCatalogo` (hora) cruza CM con el
   catálogo y registra conflictos; `Depurar` (día) ejecuta la agenda de retención respetando los estados.

## Estructura (hexagonal)

```
src/main/java/com/banco/cmv8/
├── domain/                 Modelo y reglas puras (sin Azure)
│   ├── ClaveAdjunto        tabla + id; prefijo del publisher derivado; ruta <tabla>/<id>/<archivo>
│   ├── Adjunto, EstadoIngesta (10 estados), RegistroIngesta (fila de la Azure Table), MensajeIngesta,
│   │   DocumentoAArchivar, PoliticaSas, ContextoLlamador, SasEmitida, TokenUsuario, ResultadoEscaneo
│   ├── catalogo/           TipoDocumento, PropiedadCm, DefinicionTipoCmis, Conflicto, ConflictoRegistrado
│   └── port/               RepositorioAdjuntos, CatalogoTipos, RegistroIngestas, AgendaDepuracion, EmisorSas,
│                           AlmacenAdjuntos, ArchivoDocumental, ResolutorAs400, ColaIngesta, IntercambiadorTokenUsuario
├── application/            EmitirSasLote, EmitirSasLectura, ProcesarIngesta, RegistrarResultadoEscaneo,
│   │                       ReencolarEstancadas, ArchivarAlmacenado, Reanudar, IngestarArchivo, Depurar, ExtenderRetencion
│   └── catalogo/           CatalogoEnCache, ResolverPropiedades, ConversorCmis, AnalizadorCatalogo, Conflictos,
│                           ReglasDescubrimiento, DescubrirTipos, ValidarTipo, ValidarTabla, ReanudarConflicto, VigilarCatalogo
├── adapters/in/            SasHttp, AdjuntosHttp, CatalogoHttp, SaludHttp, EventosEventGrid, ColaIngestaFunction,
│                           Temporizadores (+ LectorLlamador, UbicacionBlob, RespuestasHttp)
├── adapters/out/
│   ├── blob/               EmisorSasBlob, AlmacenAdjuntosBlob (dos contenedores, mover, inmutabilidad)
│   ├── tabla/              RegistroIngestasTabla, AgendaDepuracionTabla (azure-data-tables)
│   ├── dataverse/          ClienteDataverse, RepositorioAdjuntosDataverse (cualquier tabla), CatalogoTiposDataverse
│   ├── cmis/               ArchivoDocumentalCmis (archivar, buscar, descubrir tipos, definición viva, borrar)
│   ├── as400/              ConsultasAs400 (consultas con nombre), ResolutorAs400Jt400, FuenteConexionesAs400
│   ├── entra/              IntercambiadorTokenObo
│   └── cola/               ColaIngestaSalida
└── config/                 Configuracion (app settings), Fabrica (raíz de composición), Json
```

## Endpoints

| Función | Ruta | Respuesta |
| --- | --- | --- |
| `SasLote` | `POST /api/sas/lote` `{tabla, items:[{id, nombreArchivo, tamanoBytes, contentType}]}` | 200 `{items:[{id, uploadUrl, rutaBlob, expiraUtc} o {id, error, codigo}], bloqueBytes, versionServicio, tokenStorage, tokenStorageExpiraUtc}`; 400 lote inválido; 401 sin identidad |
| `SasLectura` | `GET /api/sas/lectura/{tabla}/{id}` | 200 `{downloadUrl, expiraUtc, tokenStorage…}`; 403; 404; 409 si ya está solo en CM |
| `Archivar` | `POST /api/adjuntos/{tabla}/{id}/archivar` | 202; 409 si no está Almacenado o el tipo no va a CM |
| `Reanudar` | `POST /api/adjuntos/{tabla}/{id}/reanudar` | 202; 409 si no está Observada ni en Error |
| `Ingestar` | `POST /api/adjuntos/{tabla}/{id}/ingestar` (cuerpo = archivo; `Content-Type`; `X-Nombre-Archivo`) | 202 `{rutaBlob}`; para Power Automate, tope 100 MB |
| `ExtenderRetencion` | `POST /api/adjuntos/{tabla}/{id}/retencion` `{fecha}` (nula = congelar) | 200 |
| `DescubrirTipos` / `ActualizarTipos` | `POST /api/catalogo/descubrir` / `POST /api/catalogo/actualizar` | 200 `{nuevos, actualizados, sinCambios, ausentes, avisos}`; 403 sin rol de administrador documental |
| `ValidarTipo` | `GET /api/catalogo/validar?tipo={id}` | 200 `{criticos, avisos, hallazgos:[{nivel, tipo, propiedad, mensaje}]}` |
| `ValidarTabla` | `GET /api/catalogo/validar-tabla/{tabla}?tipo={id}` | 200 `{tabla, prefijo, ok, faltantes, avisos}` |
| `ReanudarConflicto` | `POST /api/catalogo/conflictos/{id}/reanudar` | 200 `{reencolados}` |
| `Salud` | `GET /api/salud` | `{estado:"ok", version}` |
| `OnScanResult` / `OnBlobCreated` | Event Grid | encola o rechaza |
| `ProcesarIngesta` | cola `%COLA_INGESTA%` | worker |
| `Watchdog` / `VigilarCatalogo` / `Depurar` | timers `0 */15 * * * *` / `0 5 * * * *` / `0 30 2 * * *` | ver Flujo |

Los nombres `OnScanResult` y `OnBlobCreated` coinciden con las suscripciones de `infra/main.bicep`.

### Autenticación de los endpoints HTTP

Los HTTP triggers son `AuthorizationLevel.ANONYMOUS`: la autenticación Entra la impone Easy Auth. La Function toma
la identidad de `X-MS-CLIENT-PRINCIPAL-ID` / `X-MS-CLIENT-PRINCIPAL` (los inyecta App Service después de validar el
token) o, si no están, de los claims `oid`/`tid` del bearer sin validar la firma. Toda operación autoriza en
Dataverse suplantando ese `oid`; las de administración exigen además el rol de administrador documental (lectura
suplantada de `cmv8_conflicto`).

## App settings

Las define `infra/main.bicep`:

| Setting | Defecto | Uso |
| --- | --- | --- |
| `AzureWebJobsStorage__accountName` / `__credential` / `__clientId` | — | Storage del host, colas y timers (identidad) |
| `AZURE_CLIENT_ID` | — | Identidad administrada asignada por el usuario para `DefaultAzureCredential` |
| `ADJUNTOS_BLOB_ENDPOINT` / `ADJUNTOS_TABLE_ENDPOINT` | — | `https://<cuenta>.blob.core.windows.net/` y `.table.core.windows.net/` |
| `ADJUNTOS_CONTAINER` / `ADJUNTOS_CONTAINER_ALMACENADOS` | `cmv8-ingesta` / `cmv8-almacenados` | En vuelo / lo que se queda |
| `TABLA_INGESTA` / `TABLA_DEPURACION` | `ingesta` / `depuracion` | Azure Tables del estado operativo y la agenda |
| `DATAVERSE_URL` | — | `https://<org>.crm.dynamics.com` |
| `SAS_UPLOAD_MINUTOS` / `SAS_READ_MINUTOS` / `SAS_BLOQUE_MB` / `SAS_LOTE_MAXIMO` | 15 / 5 / 4 / 20 | SAS y lote |
| `SAS_LIGADA_USUARIO` | `false` | `true` = SAS con `sduoid` + token de Storage por OBO (`BROKER_CLIENT_ID`, `BROKER_TENANT_ID`; `BROKER_CLIENT_SECRET` solo en local) |
| `TAMANO_MAXIMO_BYTES` | 1 GB | Límite de tamaño declarado |
| `COLA_INGESTA` / `ESTANCADA_MINUTOS` | `ingesta-pendiente` / 15 | Cola y Watchdog |
| `ESCANEO_REQUERIDO` | `false` | `true` = exigir la etiqueta de Defender antes de procesar |
| `OBSERVADA_DIAS` | 30 | Días para corregir una Observada antes de pasar a Rechazado |
| `WORM_HABILITADO` / `WORM_BLOQUEAR` | `false` / `true` | Inmutabilidad por tipo (exige versionado e inmutabilidad en el contenedor, Bicep) |
| `CATALOGO_CACHE_MINUTOS` | 10 | Caché del catálogo en memoria (cero requests por archivo) |
| `CMIS_URL` / `CMIS_USER` / `CMIS_PASSWORD` / `CMIS_REPOSITORY_ID` | — | Browser binding y usuario de servicio (contraseña por referencia a Key Vault) |
| `CMIS_PREFIJO_PROPIEDADES` / `CMIS_PROPIEDAD_ID_CORTO` / `CMIS_CARPETA_PLANTILLA` | — / — / `/{localName}` | Reglas de descubrimiento (nombre canónico, ID corto, carpeta derivada) |
| `AS400_HOST` / `AS400_USER` / `AS400_PASSWORD` / `AS400_SECURE` / `AS400_POOL_MAX` / `AS400_POOL_VIDA_MAX_MINUTOS` | — | Como en v1 (contraseña por referencia a Key Vault) |
| `AS400_CONSULTAS_JSON` | vacío | `{"nombre": "SELECT ... WHERE A = :parametro"}`; los parámetros se llaman como las columnas lógicas de la tabla de adjuntos. El SQL vive solo acá. Vacío = `AS400_SQL_CLIENTE` (v1, un `?`) como consulta `cliente` con parámetro `idcliente` |

La tabla `cmv8_solicitudingesta` de los spikes es la primera tabla de adjuntos (ADR-003 §12): sus columnas
reservadas llevan el prefijo `cmv8_`.

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
# (func azure functionapp publish no es el camino soportado en Flex Consumption)
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
