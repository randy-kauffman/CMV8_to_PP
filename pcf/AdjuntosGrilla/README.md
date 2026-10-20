# AdjuntosGrilla (1.0.0)

Control PCF de **dataset** (namespace `Banco`) para apps **model-driven**: se liga a la subcuadrícula de adjuntos del formulario del registro padre y **orquesta solo** ([ADR-003 §4.6](../../docs/decisiones/ADR-003-v2-modelo-de-datos.md)). Muestra los adjuntos con sus diez estados, deja subir archivos nuevos y ejecuta las acciones de cada estado. No tiene salidas: no necesita Power Fx ni la app.

A diferencia de [CargaAdjuntoBlob](../CargaAdjuntoBlob/README.md) (canvas, «solo sube; el estado lo muestra la app»), este control obtiene su propio token con MSAL Browser, llama al broker directo y crea y actualiza las filas con la Web API del componente, con la identidad del usuario. El broker no cambia.

Diseño: [Anatomia.md](../../docs/diseno/anatomia-y-tokens/Anatomia.md) §4 (chip), §6 (barra de la grilla), §7 (grilla) y §8 (detalle, acá como acordeón bajo la fila), [textos.es.json](../../docs/diseno/anatomia-y-tokens/textos.es.json) (claves `grid.*`, `lifecycle.*`, `panel.*`, `action.*`), [tokens.json](../../docs/diseno/anatomia-y-tokens/tokens.json). App registration: [guía 24](../../docs/montaje/24-app-registration-pcf.md).

## Flujo

```
Usuario          AdjuntosGrilla (PCF)                       Dataverse (Web API, usuario)   Broker / Storage
  │ elige tipo     │                                            │                              │
  │ y archivos     │ valida (tipo, tamaño, vacío, duplicado)    │                              │
  │ «Subir todo»   │ token MSAL (silencioso; popup si hace falta, dentro del clic)              │
  ├───────────────►│ crea N filas en Creada ───────────────────►│ createRecord                 │
  │                │ POST /api/sas/lote {tabla, items} ─────────────────────────────────────────►│
  │                │◄──────────── {items[uploadUrl], tokenStorage, bloqueBytes, ...} ───────────┤
  │                │ PUT Block / Block List con SAS + Bearer tokenStorage ─────────────────────►│ Storage
  │                │ cada archivo terminado: estadoingesta = 2 ►│ updateRecord                 │
  │                │ dataset.refresh() (y cada 15 s mientras haya Subido/Procesando)           │
```

- Cola, motor de subida, subida por bloques, validación, tokens y vista de carga (Dropzone, barra de cola, filas con progreso) son **los de CargaAdjuntoBlob**, importados por ruta relativa (`../CargaAdjuntoBlob/...`; pcf-scripts los empaqueta en el bundle de cada control). Lo único nuevo del lado de la subida es la fuente de SAS: `FuenteSasMsal` (broker directo) envuelta en `FuenteConFilas` (crea la fila antes de pedir la SAS). Para el motor, «Solicitando permiso» incluye crear la fila.
- Un archivo que falla se reintenta reutilizando su fila (lote de uno), igual que en canvas.

## Grilla y acciones

Columnas (≥ 900 px de contenedor): Nombre (con el mensaje de detalle debajo en Observada, Detenida, Error y Rechazado) · Tipo de documento · Estado (chip) · Tamaño · Fecha de subida · Subido por · acción principal · menú «…». Entre 600 y 899 px, Tamaño y Subido por pasan a la segunda línea del nombre; bajo 600 px, tarjetas. Filtros rápidos con contador (Todos · Requieren acción · En proceso · Guardados · Cerrados; desplegable bajo 600 px), orden por fecha (desc/asc) o por estado, densidad, actualizar, «Cargar más» pasados 100.

| Estado (código) | Acción | Qué hace |
| --- | --- | --- |
| Creada (1) | Subir | Abre el selector del sistema y sube un archivo **sobre esa misma fila** (completa nombre, tamaño y tipo MIME). |
| Archivado (4), Almacenado (7) | Ver | `GET /api/sas/lectura/{tabla}/{id}` → descarga con `Authorization: Bearer tokenStorage` → Blob local → pestaña nueva (`URL.createObjectURL`). La URL con SAS nunca se muestra. Archivado sin copia en Blob (`rutablob` vacía): sin Ver (el broker todavía no trae de Content Manager). |
| Almacenado (7) | Archivar ahora | `POST /api/adjuntos/{tabla}/{id}/archivar` (202) y refresca. |
| Observada (8) | Corregir y reanudar | Abre el formulario del adjunto (`context.navigation.openForm`). Al volver al padre, si la fila sigue Observada y `modifiedon` avanzó (el usuario guardó), llama `POST .../reanudar`. Si no guardó, no reanuda. |
| Error (6) | Reintentar | `POST .../reanudar` (202). Si el broker responde 404 (el archivo ya no está en ingesta), la fila pasa a ofrecer **Subir**. |
| Subido, Procesando, Detenida, Rechazado, Depurado | — | Detenida muestra «Espera a un administrador. No necesitas hacer nada.» |

Enter, Espacio o clic sobre la fila abre el detalle (estado, descripción, mensaje, datos y acciones con etiqueta completa). Teclado: la grilla es una sola parada de tabulación; flechas entre celdas y botones, Inicio/Fin, Ctrl+Inicio/Fin, Re Pág/Av Pág; Esc cierra el detalle o el menú. Estados con ícono + texto, nunca solo color; anuncios `aria-live` al agregar archivos, en cada cambio de estado (también los que trae un refresco), cada 25 % de progreso, al terminar y en los errores (región assertive).

**Ver y contenido activo.** El documento se sirve como `blob:` del origen del entorno. Solo se abren en pestaña PDF, imágenes raster, video, audio y texto plano; cualquier otro tipo (HTML, SVG, Office, etc.) se **descarga** con su nombre. Así un HTML o SVG subido por otro usuario no puede ejecutar script con la sesión de Dataverse de quien lo abre.

## Propiedades (subcuadrícula → Componentes → AdjuntosGrilla)

| Propiedad | Ejemplo | Notas |
| --- | --- | --- |
| `urlBroker` | `https://cmv8-func-supfcopn.azurewebsites.net` | Sin `/api` ni barra final; solo HTTPS. |
| `brokerClientId` | appId de «CMV8 Broker» | El control pide `api://<brokerClientId>/user_impersonation`. |
| `clientIdPcf` | appId de «CMV8 PCF» | Cliente público de la guía 24. |
| `tenantId` | GUID del tenant | Authority `https://login.microsoftonline.com/<tenantId>`. |
| `lookupPadre` | `cred_Credito` | **SchemaName** (nombre de navegación) de la lookup de la tabla de adjuntos al padre. Distingue mayúsculas: se usa como `cred_Credito@odata.bind`. |
| `lookupTipoDocumento` | vacío | SchemaName de la lookup `<prefijo>tipodocumento`. Vacío = `<prefijo>TipoDocumento` (por ejemplo `cred_TipoDocumento`). Si el maker la creó con otra capitalización, completarla. |
| `tiposAceptados`, `tamanoMaximoMB`, `loteMaximo`, `paralelismoArchivos`, `paralelismoBloques` | | Iguales que en CargaAdjuntoBlob. |
| `densidad`, `variante`, `modo` y las propiedades de tokens (`PrimaryColor`, …) | | Iguales que en CargaAdjuntoBlob (mismas variables `--fu-*`). La densidad la puede cambiar el usuario desde la grilla. |

Si falta alguna de las cinco primeras (o la tabla no cumple `^[a-z][a-z0-9_]{1,63}$`), la grilla se muestra igual pero sin carga ni acciones, con el aviso `config.missing`. En el formulario de creación (registro sin guardar) se muestra `config.noParent`.

**De dónde salen la tabla y el padre.** La tabla es `dataset.getTargetEntityType()`; el prefijo, lo que va hasta el primer `_` inclusive (`cred_adjuntocredito` → `cred_`). El padre (entidad e id) sale de `context.mode.contextInfo` (`entityTypeName`, `entityId`), que el host model-driven entrega aunque no figure en los tipos públicos de PCF. El entity set del padre y del catálogo se obtiene con `context.utils.getEntityMetadata(...).EntitySetName` (si no está disponible, se supone el plural por defecto `<nombre lógico>s`).

**Columnas.** El dataset declara property-sets opcionales (`estado`, `tipoDocumento`, `nombreArchivo`, `tamano`, `mensaje`, `rutaBlob`, `fechaSubida`, `subidoPor`). Si el maker no los liga, el control usa las columnas reservadas `<prefijo>estadoingesta`, `<prefijo>tipodocumento`, `<prefijo>nombrearchivo`, `<prefijo>tamanobytes`, `<prefijo>mensajeerror`, `<prefijo>rutablob`, `<prefijo>contenttype`, más `modifiedon` (fecha de subida por defecto y detección de «guardó la corrección») y `createdby`, y las agrega a la consulta con `dataset.addColumn` aunque la vista de la subcuadrícula no las tenga. Conviene ligar `fechaSubida` a `createdon` si el negocio quiere la fecha de alta y no la del último cambio de estado.

Al crear la fila se escriben `nombrearchivo`, `tamanobytes`, `contenttype`, `estadoingesta = 1`, `<lookupTipoDocumento>@odata.bind = /cmv8_tipodocumentos(<id>)`, `<lookupPadre>@odata.bind = /<entity set del padre>(<id>)` y la columna primaria de la tabla (si no es `nombrearchivo`) con el nombre del archivo recortado a 100 caracteres. El tipo se elige en un desplegable cargado con `cmv8_tipodocumento` filtrado por `cmv8_enuso eq true`; uno por lote, aplicado a los archivos de la cola que todavía no tienen fila.

## Requisitos fuera del control

1. **App registration «CMV8 PCF»** ([guía 24](../../docs/montaje/24-app-registration-pcf.md)): cliente público single tenant; plataforma **SPA** con redirect `https://<org>.crm.dynamics.com/` (el control usa `window.location.origin + "/"`; registrar también `/main.aspx`); permiso delegado `CMV8 Broker / user_impersonation` con **consentimiento de administrador**; sin secreto. En la app del broker, el appId del PCF en `knownClientApplications` (consentimiento combinado con el del conector).
2. **CORS de la Function** (App Service → CORS): el origen del entorno (`https://<org>.crm.dynamics.com`). El control manda `Authorization` y `Content-Type: application/json` y no manda cookies. Para leer el desafío de claims del encabezado hace falta exponer `WWW-Authenticate`; sin eso el control lo toma del cuerpo JSON del 401 (el broker lo incluye).
3. **CORS de la cuenta de adjuntos** (Blob): el origen del entorno con `PUT` (subida) y `GET` (Ver), encabezados `authorization`, `x-ms-version`, `x-ms-blob-content-type`, `content-type`.
4. **external-service-usage** del manifiesto: el dominio real del Storage y del broker (los del manifiesto son los de desarrollo). Cambiarlos en `PROPIEDADES_GRILLA`/el bloque del manifiesto de `CargaAdjuntoBlob/herramientas/generar_desde_diseno.py`, no a mano.
5. **Roles** (solución del maker): sobre la tabla de adjuntos Create, Read, Write, Append y AppendTo; AppendTo sobre la tabla padre; Read y AppendTo sobre `cmv8_tipodocumento`. Licencia premium (el componente declara servicios externos).

## Compilar y generar

```bash
cd pcf
npm install                      # trae @azure/msal-browser 4.30.0 (fijada; v4 LTS, la que documenta Microsoft para PCF)
python3 CargaAdjuntoBlob/herramientas/generar_desde_diseno.py   # manifiestos, resx, íconos y base.generado.css de los dos controles
npm run build                    # compila los dos controles (cada carpeta con ControlManifest.Input.xml)
```

Generados (no editar a mano): `ControlManifest.Input.xml`, `strings/*.resx`, `textosGrilla.generado.ts`, `vista/iconosGrilla.generado.ts`, `css/base.generado.css` (copia de la hoja de CargaAdjuntoBlob: un manifiesto no puede referenciar CSS fuera de su carpeta). Los textos propios de la grilla que `textos.es.json` no tiene (sesión, configuración, errores de acciones) están en `TEXTOS_GRILLA` del generador; si el diseño los adopta, pasan a `textos.es.json`.

## Qué NO hace

- **No lee Dataverse para decidir.** El estado del proceso lo deciden el broker y el worker; el control solo crea la fila en Creada, la marca Subido al terminar la subida (ADR-003 §4.1, paso 3) y muestra lo que el dataset trae. Autorizar, emitir SAS, archivar y reanudar pasan **todos** por el broker, que suplanta al usuario y verifica la fila.
- No guarda SAS ni `tokenStorage`: viven en memoria hasta que el archivo termina. El token del broker lo cachea MSAL en `sessionStorage` (por pestaña, se borra al cerrarla). No registra nada en consola (el logger de MSAL queda en Error, sin PII y sin salida).
- No tiene salidas ni eventos para la app; no reemplaza a CargaAdjuntoBlob en canvas.
- No implementa el panel lateral completo de §8 (Corregir datos con campos, Historial): el detalle es un acordeón y la corrección se hace en el formulario del adjunto.
- No lee la URL del broker de una variable de entorno (ADR-003 §4.6 lo prevé): las propiedades de un control de subcuadrícula son estáticas. Se configura por formulario.

## Limitaciones conocidas y verificación pendiente

- **Popup en la app móvil de Dynamics 365 y en Teams.** Si el SSO silencioso falla (cookies de terceros bloqueadas, sesión vencida, acceso condicional), hace falta el popup de MSAL; en móvil y Teams puede no abrir. El control muestra «Vuelve a iniciar sesión» con un botón (el popup se abre dentro de ese clic); si el navegador lo bloquea, `auth.popupBlocked`. Respaldo de ADR-003 §4.6 (Custom API + plugin) no construido.
- El popup necesita que la página del entorno no corte la relación con su opener (`Cross-Origin-Opener-Policy`); con MSAL 4 la respuesta se lee del popup en el mismo origen. **A verificar en un entorno real.**
- «Ver» descarga el archivo completo a memoria antes de abrirlo: con videos de cientos de MB tarda y consume memoria. El navegador puede bloquear la pestaña si la descarga demora mucho; entonces el archivo se descarga.
- Filtro y orden se aplican sobre lo cargado (100 por página + «Cargar más»), no en el servidor.
- «Corregir y reanudar» depende de `sessionStorage` y de volver al formulario del padre en la misma pestaña dentro de dos horas; si no, el usuario reanuda desde la misma acción.
- Un único tipo de documento por lote (el prototipo lo pide por archivo; la vista de carga compartida no tiene esa celda).
- No hay harness model-driven: compilado y con lint limpio, pero `contextInfo`, `addColumn`, los alias de property-set, `openForm` y el flujo MSAL **no se probaron contra un entorno real**.
