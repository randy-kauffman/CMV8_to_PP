# 18. Dataverse: solución base v2, catálogo, roles, app de administración y tabla de adjuntos del maker

> Rol que lo ejecuta: Power Platform (administrador del entorno) para la solución base; el **maker** de cada solución del Banco para su tabla de adjuntos y sus roles; el administrador del entorno de nuevo para asignar el rol Worker al usuario de aplicación. Depende de: la identidad administrada creada por el Bicep (guías 03 a 13: `identidadClientId`), entorno administrado con Dataverse; para `ValidarTabla`, la Function publicada (16) y el conector (20). Lo necesitan: 16 (la v2 exige este esquema antes del primer arranque), 19 (la característica de componentes para canvas), 20 (el conector se crea dentro de la solución `cmv8ingesta`), 21 (la app usa la tabla de adjuntos), 22 (humo), 23 (la app de administración). Costo: sin costo propio adicional (consume capacidad de base de datos de Dataverse del tenant; las tablas guardan solo metadatos, no binarios).

## Qué es y qué hace en esta solución

Dataverse es **quien decide** quién puede subir qué (ADR-002) y la **verdad de negocio** de cada adjunto (ADR-003). En v2 participa en dos momentos por archivo: al autorizar (el broker hace un PATCH de `rutablob` suplantando al usuario; 403 = no puede escribir esa fila) y al recibir el resultado (un PATCH del worker). El estado operativo del proceso vive en la Azure Table `ingesta` (guía 07), no acá.

Hay dos capas:

| Capa | Quién la monta | Qué tiene |
| --- | --- | --- |
| **Solución base** `cmv8ingesta` (publisher `banco_cmv8`, prefijo `cmv8`) | Administrador del entorno, con los scripts de `dataverse/` | Choice global de estados, catálogo de tipos de documento, propiedades de CM y conflictos, roles base, app model-driven de administración, usuario de aplicación de la Function. Para los spikes, además la primera tabla de adjuntos `cmv8_solicitudingesta` |
| **Solución del maker** (una por solución del Banco) | El maker, con su propio publisher | SU tabla de adjuntos (nombre libre, lookup a su registro padre, columnas reservadas, columnas de metadatos), su rol de usuarios y el rol «CMV8 Worker - <solución>» |

Broker y worker reciben tabla lógica + id: no hay tabla registro ni configuración por solución en Azure (ADR-003 §1).

### Solución base: componentes

| Componente | Qué es |
| --- | --- |
| Choice global `cmv8_estadoingesta` | Los diez estados: 1 Creada, 2 Subido, 3 Procesando, 4 Archivado, 5 Rechazado, 6 Error, 7 Almacenado, 8 Observada, 9 Detenida, 10 Depurado. Cada tabla de adjuntos liga su columna `estadoingesta` a este choice (qué significa cada uno y quién actúa: guía 23) |
| `cmv8_tipodocumento` (OrganizationOwned) | Catálogo de tipos. **Descubierto** (lo escribe la Function desde Content Manager por CMIS): id del tipo CMIS, nombre local, versionable, ausente en el servidor, revisado, cambios, descubierto en. **Humano**: código, en uso, carpeta y su origen (derivada / manual), destino (Content Manager / Solo Blob), momento (al subir / diferido), días de retención (vacío = permanente), inmutable. Un tipo **local** lo crea el administrador a mano, sin ItemType, con destino Solo Blob |
| `cmv8_propiedadcm` (hija de tipo, Cascade) | Propiedades escribibles descubiertas (id de wire, tipo de dato, obligatoria, default, largo, lista, heredada). **Humano**: decisión usar/omitir, origen del valor (columna / consulta AS400 / sistema / literal), nombre lógico, consulta, literal, patrón, momento de validación |
| `cmv8_conflicto` (lookups a tipo y propiedad) | Clase (catálogo desactualizado, obligatoria sin origen, columna inexistente, carpeta inexistente, tipo ausente en el servidor, rechazo de CM no clasificado), severidad (bloquea / aviso), detalle, detectado en, estado abierto/resuelto, resuelto en, adjuntos detenidos, clave de deduplicación |
| `cmv8_solicitudingesta` | Primera tabla de adjuntos, solo para spikes (ADR-003 §12): columnas reservadas con prefijo `cmv8_`, suma `cmv8_estadoingesta` (choice global) y la lookup `cmv8_tipodocumento`; sin lookup al padre. `cmv8_estado` de v1 queda hasta que la Function pase a v2 |
| App model-driven **CMV8 Administración documental** | Mapa del sitio con **Catálogo** (tipos, propiedades) y **Operación** (conflictos, adjuntos); formularios y vistas por defecto; rol de administrador asociado. Los botones `DescubrirTipos`, `ActualizarTipos`, `ValidarTipo` y `ReanudarConflicto` llegan con el conector (`dataverse/README.md`, bloque 4) |

### Roles

| Rol | Dónde vive | Para quién | Privilegios |
| --- | --- | --- | --- |
| **CMV8 Worker** | Solución base | Usuario de aplicación de la Function | `prvActOnBehalfOfAnotherUser` (suplantación); Create/Read/Write/Append/AppendTo Global sobre `cmv8_tipodocumento`, `cmv8_propiedadcm` y `cmv8_conflicto`; Read/Write/Append/AppendTo Global sobre `cmv8_solicitudingesta`; lectura de User, BusinessUnit, Organization, Role, Team; UserSettings propios |
| **CMV8 Solicitante** | Solución base | Usuarios de la app de spikes | Create/Read/Write/Append/AppendTo Basic (solo lo propio) sobre `cmv8_solicitudingesta`; Read y AppendTo Global sobre `cmv8_tipodocumento` (para elegir el tipo); lectura de User, BusinessUnit, Organization; UserSettings propios |
| **CMV8 Administrador documental** | Solución base | Quien administra el catálogo | Todo sobre el catálogo y los conflictos (incluido Delete); Read Global sobre `cmv8_solicitudingesta`; lectura de User, BusinessUnit, Organization; UserSettings propios |
| Rol de usuarios de la solución | Solución del maker | Usuarios de esa solución | Create/Read/Write/Append/AppendTo sobre SU tabla de adjuntos, con la profundidad que decida el maker. La regla de negocio (quién adjunta a qué padre) la ponen estos privilegios: el broker verifica solo la fila del adjunto |
| **CMV8 Worker - <solución>** | Solución del maker | Usuario de aplicación de la Function | Read/Write/Append/AppendTo Global sobre SU tabla de adjuntos. Sin Create ni Delete |

La lista exacta de privilegios de los roles base es la de `dataverse/crear-roles.sh`; el script la aplica con `ReplacePrivilegesRole`, que deja el conjunto exacto y evita privilegios de más. Ningún rol de sistema.

## ¿Imprescindible?

**Veredicto: Imprescindible**

- Qué aporta: la autorización por archivo (la fila y los roles dicen quién puede pedir una SAS sobre qué blob), el seguimiento visible para el usuario, y el catálogo que dirige al worker (qué tipo, qué carpeta, qué propiedades, de dónde sale cada valor, destino, momento, retención).
- Qué pasa si no está: la Function no tiene contra qué autorizar ni qué catálogo leer, y el worker no tiene dónde escribir el resultado.
- Piezas dentro de la guía:
  - **Choice global y catálogo**: imprescindibles en v2. Sin catálogo no hay tipo de documento que procesar.
  - **App de administración**: imprescindible operativamente. Es donde el administrador documental ve conflictos, detenidos y observados (guía 23).
  - **Rol de usuarios del maker**: imprescindible. Sin Write sobre la fila, el broker responde 403 a esa fila.
  - **Usuario de aplicación con CMV8 Worker y CMV8 Worker - <solución>**: imprescindibles. La suplantación (`CallerObjectId`) exige `prvActOnBehalfOfAnotherUser` (rol base) y el PATCH del resultado exige Write sobre la tabla del maker (rol de la solución).
  - **Firewall de IP de Dataverse**: no lo crea esta guía; si el entorno lo tiene, es imprescindible permitir la IP de salida de la Function.
- Qué decisión lo haría prescindible (o distinto): llevar la autorización a otro lugar (por ejemplo, una lista de SharePoint o una base SQL con su propio modelo de permisos). Se perdería el modelo de seguridad de Dataverse (roles, unidades, equipos, compartir, seguridad de columna) y habría que reimplementarlo.
- Alternativa y su tradeoff: OBO contra Dataverse en vez de suplantación (la request cuenta contra el usuario, no contra el pool). Evaluado y descartado en S5: no hay reporte que muestre a qué pool se descuentan las requests de Dataverse, así que la complejidad extra no se justifica (ADR-002, «Dataverse: suplantación, no OBO»). En v2 el camino feliz cuesta dos requests del usuario de aplicación por archivo (ADR-003 §8).

## Valores del cuaderno

| Variable | Produce / consume | Cómo se obtiene |
| --- | --- | --- |
| `TENANT_ID` | Consume | Cuaderno |
| `DATAVERSE_URL` | Consume | `https://<org>.crm.dynamics.com` del entorno del ambiente (admin center → entorno → **Environment URL**) |
| `MI_CLIENT_ID` | Consume | Salida `identidadClientId` del Bicep. Es el **client id** de la identidad administrada, no el principal id; el mismo valor que la Function usa en `AZURE_CLIENT_ID` |
| `ENV_ID` | Consume | `pac env list` (para `pac admin assign-user`) |
| `TABLA_ADJ` | Lo define el maker | Nombre lógico de su tabla de adjuntos (por ejemplo `cred_adjuntocredito`). Tiene que cumplir `^[a-z][a-z0-9_]{1,63}$` |

## Con comandos (solución base)

### Requisitos del entorno

- Entorno administrado (Managed Environment) del ambiente, con Dataverse.
- Habilitado **Power Apps component framework for canvas apps** (pasos en "Con el portal"). Sin eso el componente de la guía 19 no se puede importar.
- Si el entorno tiene **firewall de IP de Dataverse**, la IP de salida de la Function tiene que estar permitida; esa IP depende de la red (S0 de la guía de spikes). Hasta resolverlo, el broker responde 502 al consultar Dataverse.

### Solución base, esquema v2, roles, usuario de aplicación y app

En este orden (cada script depende del anterior; los cuatro son idempotentes):

```bash
az login --tenant "$TENANT_ID"       # con un administrador del entorno
DATAVERSE_URL="$DATAVERSE_URL" bash dataverse/crear-tabla.sh                                   # 1
DATAVERSE_URL="$DATAVERSE_URL" python3 dataverse/esquema-v2.py                                 # 2
DATAVERSE_URL="$DATAVERSE_URL" APP_CLIENT_ID="$MI_CLIENT_ID" bash dataverse/crear-roles.sh     # 3
DATAVERSE_URL="$DATAVERSE_URL" bash dataverse/crear-app-admin.sh                               # 4
```

| Paso | Script | Qué deja |
| --- | --- | --- |
| 1 | `crear-tabla.sh` | Publisher (prefijo `cmv8`), solución `cmv8ingesta` y la primera tabla de adjuntos `cmv8_solicitudingesta` con sus columnas de v1 (definidas en `dataverse/metadata/`) |
| 2 | `esquema-v2.py` | Choice global `cmv8_estadoingesta`, las tablas del catálogo con sus columnas y relaciones, y las columnas v2 de `cmv8_solicitudingesta`. Publica. `--dump dataverse/metadata/v2` vuelca el JSON generado sin tocar el entorno |
| 3 | `crear-roles.sh` | Roles CMV8 Worker, CMV8 Solicitante y CMV8 Administrador documental; con `APP_CLIENT_ID`, da de alta la identidad administrada como usuario de aplicación y le asigna CMV8 Worker |
| 4 | `crear-app-admin.sh` | App model-driven CMV8 Administración documental con su mapa del sitio; la valida, la publica y le asocia el rol de administrador |

`APP_CLIENT_ID` es el **client id** de la identidad administrada (salida `identidadClientId`), no el principal id.

### Personas

```bash
# Administrador documental
pac admin assign-user --environment "$ENV_ID" --user "<UPN u object id>" --role "CMV8 Administrador documental"
# Usuarios de la app de spikes (cmv8_solicitudingesta)
pac admin assign-user --environment "$ENV_ID" --user "<UPN u object id del piloto>" --role "CMV8 Solicitante"
```

Ningún usuario de negocio recibe CMV8 Worker, y la identidad no recibe ningún rol de sistema.

## Guía del maker: cómo adjuntar documentos desde SU solución

Esto lo hace cada equipo que quiera adjuntos, en su propia solución y con su propio publisher, sin tocar código ni configuración de Azure (ADR-003 §1.1).

### 1. La tabla de adjuntos

En la solución del maker, una tabla nueva con nombre libre (por ejemplo `cred_adjuntocredito`). El prefijo del publisher se deriva del nombre lógico de la tabla (`cred_adjuntocredito` → `cred_`): por eso **las columnas reservadas se crean en la misma solución que la tabla, con el prefijo de ese publisher**.

Columnas reservadas (nombre lógico convenido = `<prefijo>_<nombre>`):

| Columna (nombre lógico) | Tipo | Quién la escribe |
| --- | --- | --- |
| `<prefijo>_estadoingesta` | Choice **ligada al choice global `cmv8_estadoingesta`** (no un choice local) | Usuario (Creada, Subido), worker y timers (el resto) |
| `<prefijo>_tipodocumento` | Lookup a `cmv8_tipodocumento` | Usuario, obligatoria |
| `<prefijo>_rutablob` | Texto | Broker al emitir la SAS; worker al mover; timer al depurar (vacía) |
| `<prefijo>_cmisobjectid` | Texto | Worker al archivar |
| `<prefijo>_mensajeerror` | Texto largo | Worker y timers; vacío al reanudar |
| `<prefijo>_nombrearchivo`, `<prefijo>_tamanobytes`, `<prefijo>_contenttype` | Texto, entero, texto | Usuario al crear |

Como referencia de largos y tipos, la primera tabla de adjuntos (`cmv8_solicitudingesta`): `rutablob` texto 400, `cmisobjectid` texto 200, `nombrearchivo` texto 260, `tamanobytes` entero grande, `contenttype` texto 100, `mensajeerror` multilínea 4000 (`dataverse/README.md`).

Además:

- **Lookup al registro padre**: la define el maker (por ejemplo, al crédito). El broker verifica solo la fila del adjunto; quién puede adjuntar a qué padre lo dicen los roles del maker.
- **Columnas de metadatos**: los valores que exige cada tipo de documento que la solución va a usar son columnas reales de esta tabla, con el nombre lógico convenido por el catálogo: `<prefijo>_<nombrelogico>`, donde `nombrelogico` es el que figura en `cmv8_propiedadcm` para las propiedades con origen **Columna**. El maker arma un formulario normal; Dataverse valida obligatorio, tipo y largo; el broker recibe todos los valores en su única lectura. Qué columnas hacen falta lo dice `ValidarTabla` (paso 4).

### 2. Los roles de la solución

En la solución del maker:

- **Rol de usuarios**: Create, Read, Write, Append y Append To sobre la tabla de adjuntos, con la profundidad que decida el maker (Basic para "solo lo mío", Local o Deep para la unidad, etc.). Los usuarios también necesitan poder elegir el tipo de documento (Read y Append To sobre `cmv8_tipodocumento`); en la base eso lo da CMV8 Solicitante.
- **CMV8 Worker - <solución>**: Read, Write, Append y Append To en nivel **Organization** (Global) sobre la tabla de adjuntos. **Sin Create ni Delete.**

### 3. Asignar el rol Worker al usuario de aplicación (después de importar)

La asignación de «CMV8 Worker - <solución>» al usuario de aplicación de la Function **no es un componente de solución**: un rol viaja en la solución, su asignación a un usuario no. Es un paso posterior a cada importación en cada entorno, lo hace un administrador del entorno.

Por portal: admin center → entorno → **Settings** → **Users + permissions** → **Application users** → `CMV8 Function` → **Edit security roles** (verificar en el portal el rótulo) → marcar `CMV8 Worker - <solución>` → **Save**. El usuario de aplicación conserva CMV8 Worker (rol base).

Por Web API, igual que el final de `dataverse/crear-roles.sh`:

```bash
API="$DATAVERSE_URL/api/data/v9.2"
TOKEN="$(az account get-access-token --resource "$DATAVERSE_URL" --query accessToken -o tsv)"
H=(-H "Authorization: Bearer $TOKEN" -H "Accept: application/json" -H "Content-Type: application/json")
USR=$(curl -sS -G "${H[@]}" "$API/systemusers" --data-urlencode '$select=systemuserid' \
  --data-urlencode "\$filter=applicationid eq $MI_CLIENT_ID" | python3 -I -c 'import sys,json; print(json.load(sys.stdin)["value"][0]["systemuserid"])')
ROL=$(curl -sS -G "${H[@]}" "$API/roles" --data-urlencode '$select=roleid' \
  --data-urlencode "\$filter=name eq 'CMV8 Worker - <solución>'" | python3 -I -c 'import sys,json; print(json.load(sys.stdin)["value"][0]["roleid"])')
curl -sS -o /dev/null -w "%{http_code}\n" -X POST "${H[@]}" \
  "$API/systemusers($USR)/systemuserroles_association/\$ref" -d "{\"@odata.id\":\"$API/roles($ROL)\"}"
```

Si hay más de una unidad de negocio, el filtro del rol tiene que acotar también `_businessunitid_value` a la raíz, como en el script.

### 4. `ValidarTabla` antes de publicar

Antes de publicar la app, el maker valida su tabla contra el catálogo con la operación **ValidarTabla** del conector (guía 20) o directo contra la Function:

```
GET /api/catalogo/validar-tabla/{tabla}?tipo={id del tipo de documento}
→ 200 {tabla, prefijo, ok, faltantes, avisos}
```

Revisa las columnas reservadas y las columnas que piden los tipos en uso (o el tipo indicado). Con `ok: false`, `faltantes` dice qué columna crear. Es metadata, fuera del flujo de cada archivo (ADR-003 §5). Si un tipo cambia en Content Manager después, `VigilarCatalogo` lo detecta y la clase de conflicto «columna inexistente» le avisa al administrador documental.

## Con el portal

### Componentes de código para canvas

En el **Power Platform admin center** (https://admin.powerplatform.microsoft.com), con System Administrator en el entorno:

1. **Manage** → **Environments** → elegir el entorno → **Settings**.
2. **Product** → **Features**.
3. En la sección **Power Apps component framework for canvas apps**, activar **Allow publishing of canvas apps with code components** → **Save**.

### Solución base

El camino recomendado son los scripts: crean el choice global, las tres tablas del catálogo con sus columnas, opciones y relaciones (con el comportamiento de borrado de cada una) exactamente como los espera la Function. Por portal es posible pero largo; cada nombre lógico tiene que coincidir con `dataverse/esquema-v2.py` (o con el JSON de `--dump dataverse/metadata/v2`):

1. **make.powerapps.com** → elegir el entorno → **Solutions** → **New solution**: **Display name** `cmv8ingesta`, **Publisher** nuevo con **Prefix** `cmv8` → **Create**.
2. Dentro de la solución → **New** → **More** → **Choice**: **Display name** `Estado de ingesta CMV8`, nombre `cmv8_estadoingesta`, con los diez valores y sus números (1 Creada a 10 Depurado, tabla de arriba) (verificar en el portal la ruta del menú).
3. **New** → **Table** por cada tabla del catálogo (`cmv8_TipoDocumento`, `cmv8_PropiedadCm`, `cmv8_Conflicto`), propiedad de **Organization**, con las columnas de `COLUMNAS` en `esquema-v2.py`.
4. Relaciones: `cmv8_propiedadcm` → tipo (obligatoria, borrado en cascada); `cmv8_conflicto` → tipo (obligatoria, cascada) y → propiedad (opcional, quitar vínculo).
5. **Publish all customizations**.

### Roles base

1. Admin center → entorno → **Settings** → **Users + permissions** → **Security roles** → **+ New role**, unidad de negocio raíz, uno por cada rol base de la tabla "Roles".
2. En el editor de cada rol, marcar los privilegios de la tabla con la profundidad indicada (Organization = Global, User = Basic). **Act on Behalf of Another User** está en la pestaña de privilegios misceláneos (verificar en el portal su ubicación) → **Save**.

### Usuario de aplicación

1. Admin center → entorno → **Settings** → **Users + permissions** → **Application users** → **+ New app user**.
2. **+ Add an app** → pegar el **client id** de la identidad administrada (`MI_CLIENT_ID`). Learn: para una identidad administrada se ingresa su Application ID, no su nombre. → **Add**.
3. **Business unit**: la raíz. **Security roles**: lápiz → `CMV8 Worker` → **Save** → confirmar.
4. **Create**.

### App de administración

Sin script: **make.powerapps.com** → solución `cmv8ingesta` → **New** → **App** → **Model-driven app** → nombre `CMV8 Administración documental`; en el diseñador, un área con dos grupos: **Catálogo** (Tipos de documento, Propiedades de Content Manager) y **Operación** (Conflictos de catálogo, la tabla de adjuntos) → **Publish**. Después, en **Share**, asociar el rol `CMV8 Administrador documental` (verificar en el portal los rótulos del diseñador).

### Personas

Admin center → entorno → **Settings** → **Users + permissions** → **Users** → elegir el usuario → **Manage security roles** → marcar el rol → **Save** (verificar en el portal el nombre exacto del comando; Learn lo nombra **Manage Roles** en algunas páginas).

## Verificación

```bash
# esquema: idempotente, informa "existe" para todo lo creado
DATAVERSE_URL="$DATAVERSE_URL" python3 dataverse/esquema-v2.py
# roles y usuario de aplicación: idempotente
DATAVERSE_URL="$DATAVERSE_URL" APP_CLIENT_ID="$MI_CLIENT_ID" bash dataverse/crear-roles.sh
# app: revalida y publica; "válida: True"
DATAVERSE_URL="$DATAVERSE_URL" bash dataverse/crear-app-admin.sh
```

En el centro de administración, **Application users** muestra `CMV8 Function` con CMV8 Worker y, por cada solución del Banco importada, su «CMV8 Worker - <solución>». El explorador de soluciones muestra el choice global y las tres tablas del catálogo dentro de `cmv8ingesta`. Por cada tabla de adjuntos, `ValidarTabla` devuelve `ok: true`.

## Errores que ya vimos

| Síntoma | Causa | Arreglo |
| --- | --- | --- |
| `Guid should contain 32 digits` al ligar una columna al choice global | La clave alterna por `Name` no sirve para el enlace; hace falta el `MetadataId` del choice (probado 2026-10-19) | `esquema-v2.py` ya lo resuelve; a mano, usar `/GlobalOptionSetDefinitions(<MetadataId>)` |
| 403 del broker para una fila | El usuario no tiene el rol de usuarios de esa solución o la fila no le corresponde según la profundidad del rol | Asignar el rol (guía del maker, paso 2) |
| (Anticipado) El worker no puede escribir el resultado en una tabla de adjuntos nueva; los adjuntos quedan en Error | Falta asignar «CMV8 Worker - <solución>» al usuario de aplicación después de importar | Guía del maker, paso 3 |
| (Anticipado) Adjuntos en Observada o Detenida por «columna inexistente» | La tabla del maker no tiene la columna `<prefijo>_<nombrelogico>` que pide el tipo | `ValidarTabla` y crear la columna; después, `Reanudar` o `ReanudarConflicto` (guía 23) |
| 502 del broker al autorizar | Dataverse inaccesible: firewall de IP o usuario de aplicación sin rol | Permitir la IP de salida de la Function (S0) o repetir `crear-roles.sh` con `APP_CLIENT_ID` |
| (Documentado por Microsoft) El componente no se puede importar (guía 19) | Falta **Allow publishing of canvas apps with code components** | Habilitarlo en Features |
| (No observado, esperable) El usuario de aplicación no aparece o no se puede crear | Se pasó el principal id en vez del client id de la identidad | Usar `identidadClientId` |
