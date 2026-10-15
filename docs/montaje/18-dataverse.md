# 18. Dataverse: entorno, tabla, roles y usuario de aplicación

> Rol que lo ejecuta: Power Platform (administrador del entorno). Depende de: la identidad administrada creada por el Bicep (guías 03 a 13: `identidadClientId`), entorno administrado con Dataverse. Lo necesitan: 19 (la característica de componentes para canvas), 20 (el conector se crea dentro de la solución `cmv8ingesta`), 21 (la app usa la tabla), 22 (humo). Costo: sin costo propio adicional (consume capacidad de base de datos de Dataverse del tenant; la tabla guarda solo metadatos, no binarios).

## Qué es y qué hace en esta solución

Dataverse guarda el **registro** de cada carga: la fila `cmv8_solicitudingesta` (estado, nombre, tamaño, ruta del blob sin firmar, objectId de CMIS, datos del cliente, errores). Es la fuente de verdad del seguimiento y, sobre todo, es **quien decide** quién puede subir qué: principio "Dataverse decide, la Function emite, Storage solo verifica el token" (ADR-002). La Function, antes de emitir una SAS, suplanta al usuario contra Dataverse (`CallerObjectId`) y comprueba que puede escribir esa fila.

Esta guía deja:

- el entorno preparado (componentes de código para canvas y, si aplica, firewall de IP);
- publisher `cmv8`, solución `cmv8ingesta`, tabla `cmv8_solicitudingesta` con sus columnas (`dataverse/crear-tabla.sh`);
- roles **CMV8 Worker** y **CMV8 Solicitante**, y la identidad administrada de la Function como **usuario de aplicación** con CMV8 Worker (`dataverse/crear-roles.sh`);
- los usuarios piloto con CMV8 Solicitante.

## ¿Imprescindible?

**Veredicto: Imprescindible**

- Qué aporta: la autorización por archivo. La fila y los roles dicen quién puede pedir una SAS sobre qué blob; sin la fila no hay a qué ligar la SAS. Además es el seguimiento visible para el usuario y el estado que mueve el worker (Creada → Subido → Procesando → Archivado / Error / Rechazado).
- Qué pasa si no está: la Function no tiene contra qué autorizar (solo podría correr con `AUTORIZACION_MODO=ninguna`, que es exclusivo de un spike) y el worker no tiene dónde leer ni escribir el estado.
- Piezas dentro de la guía:
  - **CMV8 Solicitante**: imprescindible. Es el rol que permite a un usuario crear y escribir SOLO sus filas; sin él, el broker responde 403.
  - **Usuario de aplicación de la identidad administrada con CMV8 Worker**: imprescindible. La suplantación (`CallerObjectId`) exige que la identidad tenga `prvActOnBehalfOfAnotherUser`; sin el usuario de aplicación, el broker responde 502 al consultar Dataverse.
  - **Firewall de IP de Dataverse**: no lo crea esta guía; si el entorno lo tiene, es imprescindible permitir la IP de salida de la Function.
- Qué decisión lo haría prescindible (o distinto): llevar la autorización a otro lugar (por ejemplo, una lista de SharePoint o una base SQL con su propio modelo de permisos). Se perdería el modelo de seguridad de Dataverse (roles, unidades, equipos, compartir, seguridad de columna) y habría que reimplementarlo.
- Alternativa y su tradeoff: OBO contra Dataverse en vez de suplantación (la request cuenta contra el usuario, no contra el pool). Evaluado y descartado en S5: no hay reporte que muestre a qué pool se descuentan las requests de Dataverse, así que la complejidad extra no se justifica (ADR-002, «Dataverse: suplantación, no OBO»).

## Valores del cuaderno

| Variable | Produce / consume | Cómo se obtiene |
| --- | --- | --- |
| `TENANT_ID` | Consume | Cuaderno |
| `DATAVERSE_URL` | Consume | `https://<org>.crm.dynamics.com` del entorno del ambiente (admin center → entorno → **Environment URL**) |
| `MI_CLIENT_ID` | Consume | Salida `identidadClientId` del Bicep. Es el **client id** de la identidad administrada, no el principal id; el mismo valor que la Function usa en `AZURE_CLIENT_ID` |
| `ENV_ID` | Consume | `pac env list` (para `pac admin assign-user`) |

## Con comandos

### Requisitos del entorno

- Entorno administrado (Managed Environment) del ambiente, con Dataverse.
- Habilitado **Power Apps component framework for canvas apps** (pasos en "Con el portal"). Sin eso el componente de la guía 19 no se puede importar.
- Si el entorno tiene **firewall de IP de Dataverse**, la IP de salida de la Function tiene que estar permitida; esa IP depende de la red (S0 de la guía de spikes). Hasta resolverlo, el broker responde 502 al consultar Dataverse.

### Tabla, roles y usuario de aplicación

```bash
az login --tenant "$TENANT_ID"       # con un administrador del entorno
DATAVERSE_URL="$DATAVERSE_URL" bash dataverse/crear-tabla.sh
DATAVERSE_URL="$DATAVERSE_URL" APP_CLIENT_ID="$MI_CLIENT_ID" bash dataverse/crear-roles.sh
```

El primero crea publisher (prefijo `cmv8`), solución `cmv8ingesta`, la tabla `cmv8_solicitudingesta` y sus columnas (definidas en `dataverse/metadata/`), y publica. El segundo crea los roles **CMV8 Worker** (la Function: lectura y escritura global de la tabla, impersonación, lectura de usuarios y unidades) y **CMV8 Solicitante** (usuarios: crear, leer y escribir SOLO lo propio), da de alta la identidad administrada como usuario de aplicación y le asigna CMV8 Worker. Los dos scripts son idempotentes.

`APP_CLIENT_ID` es el **client id** de la identidad administrada (salida `identidadClientId`), no el principal id. Es el mismo valor que la Function usa en `AZURE_CLIENT_ID`.

### Usuarios piloto

Asignar el rol **CMV8 Solicitante** a cada usuario piloto:

```bash
pac admin assign-user --environment "$ENV_ID" --user "<UPN u object id del piloto>" --role "CMV8 Solicitante"
```

Ningún usuario de negocio recibe CMV8 Worker, y la identidad no recibe ningún rol de sistema.

## Con el portal

### Componentes de código para canvas

En el **Power Platform admin center** (https://admin.powerplatform.microsoft.com), con System Administrator en el entorno:

1. **Manage** → **Environments** → elegir el entorno → **Settings**.
2. **Product** → **Features**.
3. En la sección **Power Apps component framework for canvas apps**, activar **Allow publishing of canvas apps with code components** → **Save**.

### Tabla (si no se puede correr el script)

El script es el camino recomendado: crea las 12 columnas con sus tipos, largos y la lista de opciones de `cmv8_Estado` exactamente como los espera la Function. Por portal es posible, pero cada nombre lógico tiene que coincidir con `dataverse/metadata/columnas-solicitudingesta.json`:

1. **make.powerapps.com** → elegir el entorno → **Solutions** → **New solution**: **Display name** `cmv8ingesta`, **Publisher** nuevo con **Prefix** `cmv8` → **Create**.
2. Dentro de la solución → **New** → **Table** → **Table**: **Display name** `Solicitud de ingesta`, **Schema name** `cmv8_SolicitudIngesta`, propiedad de **User or team** (en **Advanced options**, verificar en el portal la ubicación) → **Save**.
3. **New** → **Column** por cada entrada del JSON: `cmv8_NombreArchivo` (texto 260), `cmv8_TamanoBytes` (número entero grande), `cmv8_ContentType` (texto 100), `cmv8_Estado` (opción, con los valores del JSON), `cmv8_RutaBlob` (texto 400), `cmv8_CmisObjectId` (texto 200), `cmv8_IdCliente`, `cmv8_IdCliente2`, `cmv8_AfiliadoPadre`, `cmv8_IdDueno` (texto 50), `cmv8_NombreCliente` (texto 200), `cmv8_MensajeError` (texto multilínea 4000).
4. **Publish all customizations**.

### Roles

1. Admin center → entorno → **Settings** → **Users + permissions** → **Security roles** → **+ New role**. Nombre `CMV8 Solicitante`, unidad de negocio raíz → **Save**.
2. En el editor del rol, tabla **Solicitud de ingesta**: Create, Read, Write, Append, Append To en nivel **User** (Basic). Lectura de **User**, **Business Unit** y **Organization** en nivel **Organization** (Global); **User Settings** lectura y escritura en nivel User. → **Save**.
3. Repetir con `CMV8 Worker`: la tabla con Read, Write, Append, Append To en nivel **Organization**; **Act on Behalf of Another User** (pestaña de privilegios misceláneos, verificar en el portal su ubicación) en Organization; lectura de User, Business Unit, Organization, Security Role y Team en Organization; User Settings en nivel User.

La lista exacta de privilegios es la de `dataverse/crear-roles.sh`; el script la aplica con `ReplacePrivilegesRole`, que deja el conjunto exacto y evita privilegios de más.

### Usuario de aplicación

1. Admin center → entorno → **Settings** → **Users + permissions** → **Application users** → **+ New app user**.
2. **+ Add an app** → pegar el **client id** de la identidad administrada (`MI_CLIENT_ID`). Learn: para una identidad administrada se ingresa su Application ID, no su nombre. → **Add**.
3. **Business unit**: la raíz. **Security roles**: lápiz → `CMV8 Worker` → **Save** → confirmar.
4. **Create**.

### Usuarios piloto

Admin center → entorno → **Settings** → **Users + permissions** → **Users** → elegir el usuario → **Manage security roles** → marcar `CMV8 Solicitante` → **Save** (verificar en el portal el nombre exacto del comando; Learn lo nombra **Manage Roles** en algunas páginas).

## Verificación

En el centro de administración, **Application users** muestra `CMV8 Function` con el rol CMV8 Worker; el explorador de soluciones muestra la tabla con sus columnas dentro de `cmv8ingesta`.

Por línea de comandos, volver a correr los dos scripts: siendo idempotentes, informan "existe" para todo lo creado.

## Errores que ya vimos

| Síntoma | Causa | Arreglo |
| --- | --- | --- |
| 403 `AccesoDenegado` del broker | El usuario no tiene CMV8 Solicitante o la fila no es suya | Asignar el rol (usuarios piloto) |
| 502 del broker al autorizar | Dataverse inaccesible: firewall de IP o usuario de aplicación sin rol | Permitir la IP de salida de la Function (S0) o repetir `crear-roles.sh` con `APP_CLIENT_ID` |
| (Documentado por Microsoft) El componente no se puede importar (guía 19) | Falta **Allow publishing of canvas apps with code components** | Habilitarlo en Features |
| (No observado, esperable) El usuario de aplicación no aparece o no se puede crear | Se pasó el principal id en vez del client id de la identidad | Usar `identidadClientId` |
