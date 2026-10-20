# dataverse

Artefactos de Dataverse de la solución `cmv8ingesta` (publisher `banco_cmv8`, prefijo `cmv8`).

## Tabla cmv8_SolicitudIngesta

Un registro por documento enviado a Content Manager. El binario NO vive acá: vive en Blob Storage y acá queda solo la ruta sin firmar (ADR-002).

| Columna | Tipo | Uso |
| --- | --- | --- |
| cmv8_nombre | texto (primaria) | nombre visible del registro |
| cmv8_nombrearchivo | texto 260 | nombre original del archivo |
| cmv8_tamanobytes | entero grande | tamaño |
| cmv8_contenttype | texto 100 | MIME |
| cmv8_estado | opción | 1 Creada, 2 Subido, 3 Procesando, 4 Archivado, 5 Rechazado, 6 Error |
| cmv8_rutablob | texto 400 | `{solicitudId}/{archivo}` dentro del contenedor, nunca una URL firmada |
| cmv8_cmisobjectid | texto 200 | id del documento en Content Manager |
| cmv8_idcliente, cmv8_idcliente2, cmv8_nombrecliente, cmv8_afiliadopadre, cmv8_iddueno | texto | metadatos obligatorios resueltos desde AS400 |
| cmv8_mensajeerror | memo | último error del worker |

## Crear en el entorno

```bash
az login
DATAVERSE_URL=https://org36e60d9d.crm.dynamics.com bash dataverse/crear-tabla.sh
```

El script usa el token de `az` contra Dataverse (el usuario debe ser administrador del entorno), crea publisher, solución, tabla y columnas si no existen, y publica.

## Roles y usuario de aplicación

```bash
DATAVERSE_URL=https://org36e60d9d.crm.dynamics.com APP_CLIENT_ID=<identidadClientId> bash dataverse/crear-roles.sh
```

| Rol | Para quién | Privilegios |
| --- | --- | --- |
| CMV8 Worker | identidad administrada de la Function | tabla: Read/Write/Append/AppendTo Global; `prvActOnBehalfOfAnotherUser` (impersonación); lectura de User, BusinessUnit, Organization, Role, Team; UserSettings propios |
| CMV8 Solicitante | usuarios de la app | tabla: Create/Read/Write/Append/AppendTo Basic (solo lo propio); lectura de User, BusinessUnit, Organization; UserSettings propios |

El script crea los roles con `ReplacePrivilegesRole` (idempotente, deja el set exacto), da de alta el usuario de aplicación con el client id y le asigna CMV8 Worker. Ningún rol de sistema.

## Esquema v2 (ADR-003)

Se aplica con `DATAVERSE_URL=https://org.crm.dynamics.com python3 dataverse/esquema-v2.py` (idempotente; `--dump dataverse/metadata/v2` vuelca el JSON generado sin tocar el entorno). Aplicado en el entorno de desarrollo el 2026-10-19.

| Componente | Qué es |
| --- | --- |
| Choice global `cmv8_estadoingesta` | Los diez estados: 1 Creada, 2 Subido, 3 Procesando, 4 Archivado, 5 Rechazado, 6 Error, 7 Almacenado, 8 Observada, 9 Detenida, 10 Depurado. Cada tabla de adjuntos liga su columna `estadoingesta` a este choice. |
| `cmv8_tipodocumento` (OrganizationOwned) | Catálogo. Descubierto por la Function desde Content Manager (id del tipo CMIS, nombre local, versionable, ausente, revisado, cambios, descubierto en) o local (sin ItemType). Humano: código, en uso, carpeta y su origen, destino, momento, días de retención, inmutable. |
| `cmv8_propiedadcm` (hija de tipo, Cascade) | Propiedades escribibles descubiertas (id de wire, tipo de dato, obligatoria, default, largo, lista, heredada). Humano: decisión usar/omitir, origen del valor (columna / consulta AS400 / sistema / literal), nombre lógico, consulta, literal, patrón, momento de validación. |
| `cmv8_conflicto` (lookups a tipo y propiedad) | Clase, severidad, detalle, detectado en, estado abierto/resuelto, resuelto en, adjuntos detenidos, clave de deduplicación. |
| `cmv8_solicitudingesta` | Primera tabla de adjuntos (ADR-003 §12): suma `cmv8_estadoingesta` (choice global) y la lookup `cmv8_tipodocumento`. `cmv8_estado` de v1 queda hasta que la Function pase a v2. |

Roles (`crear-roles.sh`, v2): **CMV8 Worker** suma Create/Read/Write/Append/AppendTo Global sobre las tres tablas del catálogo; **CMV8 Solicitante** suma Read y AppendTo sobre tipo de documento (para elegirlo); **CMV8 Administrador documental** (nuevo) tiene todo sobre el catálogo y los conflictos, y lectura Global de la tabla de adjuntos.

App model-driven **CMV8 Administración documental** (`crear-app-admin.sh`): mapa del sitio con Catálogo (tipos, propiedades) y Operación (conflictos, adjuntos); usa formularios y vistas por defecto; rol de administrador asociado. Los botones `DescubrirTipos`, `ActualizarTipos`, `ValidarTipo` y `ReanudarConflicto` llegan con el conector (bloque 4).

## Lo que falta en esta carpeta

- Exportación de la solución (`pac solution export`) cuando el PCF y el conector estén adentro. El PCF hoy se sube con `pac pcf push --publisher-prefix cmv8`, que usa una solución temporal.
