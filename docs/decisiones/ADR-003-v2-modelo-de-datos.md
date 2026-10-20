# ADR-003: v2 del modelo de datos — tabla de adjuntos por solución, catálogo descubierto, estado operativo fuera de Dataverse

- Fecha: 2026-10-19
- Estado: Propuesta aceptada en discusión (2026-10-15 a 2026-10-19); pendiente de implementar. Los puntos marcados «a verificar» se prueban antes de cerrar el ADR.
- Modifica a ADR-002: cambia el modelo de datos (una tabla por solución en lugar de `cmv8_solicitudingesta`), el conteo de requests a Dataverse, los estados, y agrega destino, momento, retención y un catálogo de tipos. El modelo de seguridad de ADR-002 (dos llaves, SAS de delegación ligada al usuario, Dataverse decide) se mantiene, con dos desvíos declarados en la sección 9.
- Origen: la discusión punto por punto está en `docs/aprendizaje/consultas-y-mejoras.md` (M4, M6, M7, M8, M9, M11, M12). Este ADR consolida lo acordado; no repite los argumentos descartados.

## Contexto

La v1 (ADR-002) resolvió cómo sube un archivo con seguridad. Al explicarla de punta a punta aparecieron seis límites de diseño, no de implementación:

1. **Atada a una tabla.** Broker y worker conocen `cmv8_solicitudingesta`. Cada solución del Banco que quiera adjuntos tendría que escribir ahí o tocar código.
2. **Ocho requests a Dataverse por archivo** contra el usuario de aplicación, que consume un pool del tenant compartido por todas las integraciones: 25.000 por día si el tenant solo tiene licencias de Power Apps (Learn, «Requests limits and allocations»). Con 1.000 archivos por día, la v1 usa un tercio de ese pool.
3. **Todo blob va a Content Manager, y va ya.** No hay documentos que se queden en Blob ni archivado diferido por evento de negocio.
4. **Un solo tipo de documento**, un mapeo fijo de ocho campos, metadatos del cliente desde una consulta única al AS400. Content Manager tiene cientos de tipos con propiedades obligatorias distintas; cuando falta una, el archivo termina en Error tras cinco reintentos y nadie puede corregirlo.
5. **Sin retención.** El worker borra al archivar y el ciclo de vida borra todo a los 7 días.
6. **Un archivo por vez, solo canvas, nada desde Power Automate.**

## Decisión

1. **Cada solución del Banco tiene su propia tabla de adjuntos**, con nombre libre, lookup a su registro padre y columnas de nombre reservado. Broker y worker reciben tabla lógica + id. No hay tabla registro ni configuración por solución en Azure.
2. **Dataverse participa en dos momentos por archivo: autorizar y recibir el resultado.** El estado del proceso vive en una Azure Table de la cuenta de adjuntos. Dos requests del usuario de aplicación por archivo en el camino feliz.
3. **El catálogo de tipos de documento se descubre de Content Manager por CMIS, no se tipea.** Lo humano son decisiones: qué tipos están en uso, qué propiedad se envía y de dónde sale su valor, destino, momento, retención e inmutabilidad.
4. **Diez estados**, cada uno describe dónde está el archivo y quién tiene que actuar. Dos estados nuevos con dueño humano: Observada (el usuario) y Detenida (el administrador documental).
5. **Retención por fecha**, calculada por el worker desde el tipo, guardada en la Azure Table y ejecutada por un timer propio que respeta los estados. Inmutabilidad WORM por tipo.
6. **Lote de archivos, PCF con identidad propia en model-driven, e ingesta desde Power Automate por una operación del conector.**

## 1. Modelo de datos en Dataverse

### 1.1 Tabla de adjuntos (una por solución, en la solución del maker)

| Columna reservada (nombre lógico) | Tipo | Quién la escribe |
| --- | --- | --- |
| `estadoingesta` | Choice ligada al choice global `cmv8_estadoingesta` | Usuario (Creada, Subido), worker y timers (el resto) |
| `tipodocumento` | Lookup a `cmv8_tipodocumento` | Usuario, obligatoria |
| `rutablob` | Texto | Broker al emitir la SAS; worker al mover; timer al depurar (vacía) |
| `cmisobjectid` | Texto | Worker al archivar |
| `mensajeerror` | Texto largo | Worker y timers; vacío al reanudar |
| `nombrearchivo`, `tamanobytes`, `contenttype` | Texto, entero, texto | Usuario al crear |

- El prefijo del publisher se deriva del nombre lógico de la tabla (`cred_adjuntocredito` → `cred_`). Regla de la guía: las columnas reservadas se crean en la misma solución que la tabla. Cero requests para resolverlo.
- Los **valores de metadatos** que exige un tipo de documento son columnas reales de esta tabla, con nombre lógico convenido por el catálogo (`<prefijo>_<nombrelogico>`). El maker arma un formulario normal; Dataverse valida obligatorio, tipo y largo; el broker recibe todos los valores en su única lectura.
- Lookup al registro padre: la define el maker. El broker verifica solo la fila del adjunto; la regla de negocio la ponen los roles del maker sobre esa tabla.
- Roles en la solución del maker: rol de usuarios (Create/Read/Write/Append/AppendTo con la profundidad que decida, más Read y AppendTo Global sobre `cmv8_tipodocumento` para elegir el tipo) y rol «CMV8 Worker - <solución>» (Read/Write/Append/AppendTo Global, sin Create ni Delete). La asignación del rol Worker al usuario de aplicación es un paso posterior a la importación, no un componente de solución.
- Validación del nombre de tabla que manda el cliente: `^[a-z][a-z0-9_]{1,63}$`. La lista de permitidas son los privilegios de Dataverse: otra tabla, 403.

### 1.2 Catálogo (solución base `cmv8ingesta`, publisher `banco_cmv8`, prefijo `cmv8`)

**`cmv8_tipodocumento`**

| Grupo | Columnas |
| --- | --- |
| Descubierto (escribe la Function) | id de wire del tipo CMIS, nombre visible, `localname`, versionable, `ausente_en_servidor`, `revisado`, `cambios`, `descubierto_en`, `origen_tipo` = descubierto |
| Humano | `codigo` (ID corto, derivado al descubrir, editable), `en_uso`, `carpeta` + `origen_carpeta` (derivada / manual), `destino` (Content Manager / Solo Blob), `momento` (al subir / diferido; solo con destino CM), `diasretencion` (entero; nulo = permanente), `inmutable` (bool; exige `diasretencion`), `origen_tipo` = local |

Un **tipo local** lo crea el administrador a mano, sin ItemType ni propiedades, con destino forzado a Solo Blob; `DescubrirTipos`, `ActualizarTipos`, `ValidarTipo` y `VigilarCatalogo` lo ignoran. Un tipo descubierto puede tener destino Solo Blob y aun así se valida contra CM: existe el ItemType y mantiene segura la promoción.

**`cmv8_propiedadcm`** (hija de tipo de documento, solo tipos descubiertos)

| Grupo | Columnas |
| --- | --- |
| Descubierto | id de wire, nombre visible, tipo de dato CMIS, obligatoria, valor por defecto, largo máximo, lista de valores, heredada |
| Humano | `decision` (usar / omitir; sugerida «usar» si es obligatoria sin default), `origen` (columna / consulta AS400 / sistema / literal), `nombrelogico` de la columna, `consulta` (nombre de una consulta declarada en configuración de Azure; el SQL nunca vive en Dataverse), `literal`, `patron`, `momento_validacion` (antes de subir / al archivar; por defecto al archivar) |

**`cmv8_conflicto`**: tipo, propiedad, clase (catálogo desactualizado / obligatoria sin origen / columna inexistente / carpeta inexistente / tipo ausente / rechazo de CM no clasificado), severidad (bloquea / aviso), detalle, detectado en, estado (abierto / resuelto), adjuntos detenidos. Una fila por conflicto; la deduplicación por clave tipo + propiedad + clase vive en la Azure Table.

**Choice global `cmv8_estadoingesta`** con los diez estados de la sección 2.

**App model-driven «CMV8 Administración documental»** en la solución base: catálogo con los botones `DescubrirTipos`, `ActualizarTipos`, `ValidarTipo`; conflictos abiertos; vistas de adjuntos detenidos y observados; cola de rechazos de CM no clasificados con reintento. Rol «CMV8 Administrador documental».

## 2. Los diez estados

| Estado | Dónde está el archivo | Quién lo pone | Quién tiene que actuar | Transiciones de salida |
| --- | --- | --- | --- | --- |
| **Creada** | En ningún lado | Usuario al crear la fila | Usuario: subir | Subido; Error |
| **Subido** | En `cmv8-ingesta` | Usuario (app) al terminar la subida | Nadie | Procesando |
| **Procesando** | En `cmv8-ingesta`, en manos del worker | Worker al tomar el mensaje | Nadie | Archivado, Almacenado, Observada, Detenida, Rechazado, Error |
| **Archivado** | En Content Manager (y en `cmv8-almacenados` si el tipo retiene) | Worker tras `createDocument` | Nadie | — (al vencer la retención se vacía `rutablob`, el estado no cambia) |
| **Almacenado** | En `cmv8-almacenados`, no en CM | Worker (tipo Solo Blob o diferido) | Nadie, o el negocio cuando ocurre el evento | Procesando (vía `Archivar`); Depurado |
| **Observada** | En `cmv8-ingesta`, esperando corrección | Worker: falta o es inválido un valor de este archivo | Usuario: corregir y reanudar | Subido (vía `Reanudar`); Rechazado (vencida sin corrección) |
| **Detenida** | En `cmv8-ingesta`, esperando al administrador | Worker o `VigilarCatalogo`: conflicto de catálogo | Administrador documental | Procesando (vía `ReanudarConflicto`) |
| **Error** | En `cmv8-ingesta` | Worker: causa técnica (CM caído, AS400 caído, rechazo no previsto) | Operación: reintentar | Procesando (reintento del Watchdog o manual); Rechazado |
| **Rechazado** | Borrado | Worker (malware) o timer (Observada vencida) | Nadie | — |
| **Depurado** | Borrado por retención | Timer `Depurar` | Nadie | — |

Reglas: un estado dice DÓNDE está el archivo, no qué le va a pasar (por eso Almacenado sirve para solo-blob y para diferido; la intención está en el tipo). Observada y Detenida no se reintentan solos. Archivado, Rechazado y Depurado son terminales. El worker descarta mensajes de filas en Archivado o Rechazado (idempotencia de v1, sin cambios).

## 3. Estado operativo en Azure Table (cuenta de adjuntos)

**Tabla `ingesta`**: PartitionKey = tabla lógica, RowKey = id del adjunto. Columnas (como las escribe `RegistroIngestasTabla`): `estado` (código del choice), `tipoDocumentoId`, `contenedor`, `ruta`, `solicitanteOid`, `nombreArchivo`, `tamanoBytes`, `contentType`, `valoresJson` (columnas de la fila de Dataverse al emitir la SAS: el origen "columna" de las propiedades), `cmisObjectId`, `intentos`, `mensaje`, `conflictoClave`, `fechaDepuracion` (nula = permanente), `sasEmitidaEn`, `subidoEn`, `procesadoEn`. La deduplicación de conflictos vive en la misma tabla (PartitionKey `conflicto`, RowKey = clave codificada, columna `conflictoId`). La escribe el broker al emitir la SAS y el worker en cada paso. Nadie salvo la Function la lee.

**Tabla `depuracion`** (agenda): PartitionKey = día de vencimiento (`yyyy-MM-dd`), RowKey = tabla + id. Una consulta puntual por día; cambiar la fecha mueve la entrada.

**Dedupe de conflictos**: fila por clave tipo + propiedad + clase, con el id de la fila de `cmv8_conflicto`.

Reglas: Dataverse es la verdad de negocio; la Azure Table es la verdad operativa. Si la tabla se pierde, se reconstruye desde los blobs y Dataverse. Permiso: Storage Table Data Contributor acotado a las dos tablas, para la identidad administrada.

## 4. Flujos

### 4.1 Subida (canvas)

1. La app crea N filas en la tabla de adjuntos (requests del usuario).
2. Llama `EmitirSasLote(tabla, [ids])`. Por cada id el broker hace **una** llamada suplantada: PATCH de `rutablob` con `Prefer: return=representation`. 403 = no puede escribir; estado distinto de Creada o Error = no corresponde SAS. Devuelve **un** `tokenStorage` (OBO del usuario) y N `uploadUrl` (SAS de delegación con `sduoid`, 15 minutos, un blob). Escribe la fila en la Azure Table.
3. El PCF sube con las dos llaves, por bloques, con paralelismo acotado por archivo. La app marca Subido (requests del usuario). Un archivo fallido se reintenta con un lote de uno.
4. Event Grid `BlobCreated` → cola → worker.

### 4.2 Worker en `BlobCreated`

Lee la fila de la Azure Table (tipo, solicitante) y el catálogo del caché. Cero requests a Dataverse para decidir.

- Escaneo (si hay Defender): malicioso → Rechazado, borra.
- Si el tipo tiene ItemType: resuelve propiedades `usar` según origen (columna de la fila ya leída por el broker y guardada en la tabla; consulta AS400 con nombre; sistema; literal), convierte al tipo de dato que CM declara, valida patrón y largo, y cruza con la **definición viva del tipo** (`getTypeDefinition`, cacheada por OpenCMIS). Obligatoria de CM que no se va a enviar, tipo ausente, carpeta inexistente, largo mayor al máximo, propiedad desconocida → **conflicto de catálogo** → Detenida + fila de conflicto (una vez) + alerta. Valor faltante o inválido de este archivo, o AS400 que no reconoce → **conflicto de datos** → Observada con detalle. Esto ocurre aunque el archivado sea diferido: el usuario se entera hoy.
- Si el tipo es local: solo escaneo.
- Destino CM y momento al subir: `createDocument`, un PATCH final (Archivado + cliente + `cmisobjectid` + `rutablob` nueva o vacía). Si el tipo retiene, mueve a `cmv8-almacenados`; si no, borra.
- Diferido o Solo Blob: mueve a `cmv8-almacenados` (misma ruta `<tabla>/<id>/<archivo>`), un PATCH final (Almacenado + `rutablob`).
- Falla técnica: Error con mensaje; el Watchdog reintenta hasta `maxDequeueCount`. Si el PATCH final falla porque la fila ya no existe o cambió, se borra el documento recién creado en CM y se marca error en la tabla.

### 4.3 Archivado diferido y promoción

`Archivar(tabla, id)` desde la app (o un flujo) cuando ocurre el evento de negocio: el broker verifica escritura por suplantación (una request), encola; el worker procesa desde `cmv8-almacenados`. Un adjunto de tipo local se promueve cambiando primero su tipo a uno descubierto. Hacia arriba sí, hacia abajo no.

### 4.4 Observada y Detenida

- `Reanudar(tabla, id)`: el usuario corrigió; el broker relee la fila suplantando (una request), revalida, limpia `mensajeerror` y reencola. Explícito, porque el Watchdog ya no mira Dataverse.
- `ReanudarConflicto(conflictoId)`: el administrador resolvió (corrió `ActualizarTipos`, revisó, agregó la columna); la Function invalida el caché del catálogo, busca en la Azure Table los adjuntos con esa clave y los reencola. Si al reprocesar falta el valor en la fila, pasa a Observada: catálogo → administrador → datos → usuario.
- `VigilarCatalogo` (timer, cada hora): compara la definición viva de cada tipo en uso contra el catálogo; registra conflictos nuevos y dispara alerta. Detecta, no decide: nunca corre `ActualizarTipos`.

### 4.5 Retención y depuración

- Al quedar en `cmv8-almacenados`: `fechaDepuracion` = hoy + `diasretencion` del tipo (diferido en espera: nula hasta archivarse). Al pasar a Observada: hoy + N días (N del Banco).
- Tipo `inmutable`: el worker pone al blob una política de inmutabilidad a nivel de versión con vencimiento = `fechaDepuracion`, bloqueada si `wormBloquear` (verdadero en el Banco; Learn: bloqueada solo se extiende, nunca se acorta ni se borra, y solo bloqueada cumple regulación).
- `ExtenderRetencion(tabla, id, fecha)`: extiende; nula = retención legal (legal hold por versión si es inmutable).
- Timer `Depurar` (diario): lee la agenda de hoy y de los días pendientes. Si hay humano pendiente (Observada vigente, Detenida, Procesando) no borra y corre la fecha siete días. Si no: borra el blob, actualiza Dataverse (Archivado → vacía `rutablob`; Almacenado → Depurado; Observada vencida → Rechazado «vencida sin corrección»), actualiza la tabla y registra. Idempotente. Un WORM no vencido lo rechaza Storage: se reprograma.

### 4.6 Model-driven

El mismo PCF, ligado como **control de dataset a la subcuadrícula de adjuntos** del formulario del padre. Adquiere su propio token con MSAL Browser contra la app registration «CMV8 PCF» (cliente público, single tenant, redirect SPA con la URL del entorno, permiso `api://<broker>/user_impersonation`, consentimiento de administrador); silencioso con la sesión de Entra, popup si el navegador bloquea el iframe. Llama al broker directo, recibe las dos llaves, sube; crea y actualiza filas con la Web API del componente con la identidad del usuario. Broker sin cambios. Patrón documentado por Microsoft (arquitectura de referencia Dynamics 365, PCF + MSAL). Alta en la guía 24; creada en desarrollo el 2026-10-20. La URL del broker sale de una variable de entorno de Dataverse. Respaldo si el entorno bloquea popups e iframes: Custom API + plugin con identidad administrada (GA agosto 2025) como segundo llamador del broker; pierde `sduoid`.

### 4.7 Power Automate

`IngestarArchivo(tabla, id, contenido)` en el conector: el flujo crea la fila (conector de Dataverse), obtiene el contenido de la fuente (SharePoint, OneDrive, correo, carpeta) y llama la operación; el broker suplanta al usuario de la conexión, verifica escritura y estado, escribe el blob con su identidad; de ahí, el pipeline normal. Límite: 100 MB por acción de flujo contando el payload entero (Learn), menos por base64. `EmitirSasSubida` y `EmitirSasLote` siguen prohibidas desde flujos. Alternativa documentada para archivos grandes con URL (no se construye ahora): `IngestarDesdeUrl` con copia del lado de Storage.

## 5. Operaciones del conector

| Operación | Quién la llama | Dataverse (usuario de aplicación) |
| --- | --- | --- |
| `EmitirSasLote(tabla, ids[])` | App canvas, PCF model-driven | 1 por archivo (PATCH suplantado con representación) |
| `EmitirSasLectura(tabla, id)` | App, PCF | 1 (lectura suplantada) |
| `Archivar(tabla, id)` | App, PCF, flujo | 1 |
| `Reanudar(tabla, id)` | App, PCF | 1 |
| `IngestarArchivo(tabla, id, contenido)` | Flujo | 1 |
| `ValidarTabla(tabla)` | Maker, antes de publicar | Metadata, fuera del flujo |
| `DescubrirTipos`, `ActualizarTipos`, `ValidarTipo`, `ReanudarConflicto`, `ExtenderRetencion` | Administrador documental | Centenas una vez; después a demanda |
| `ConsultarAs400(nombre, parametros)` | App (mostrar datos antes de subir) | Ninguna (AS400); reemplaza a `ResolverCliente` de v1 |
| `GET /api/salud` | Sin cambios | — |

Todas con Easy Auth y rol verificado por suplantación. `EmitirSasSubida(solicitudId)` de v1 desaparece (es `EmitirSasLote` de un elemento).

## 6. Function

- Puertos nuevos: `EstadoIngesta` (Azure Table), `CatalogoTipos` (Dataverse, con caché en memoria de minutos), `AgendaDepuracion`. `RepositorioSolicitudes` pasa a `RepositorioAdjuntos` parametrizado por tabla y prefijo. `ArchivoDocumental` crece con `descubrirTipos()` y `definicionTipo(id)`. `ResolutorCliente` se generaliza a `ResolutorAs400` con consultas con nombre (`AS400_CONSULTAS_JSON`); la consulta de v1 es la primera.
- Servicios de aplicación: `EmitirSasLote`, `ProcesarIngesta` (dirigido por datos; reemplaza a `MapeoPropiedadesCmis`), `ResolverPropiedades`, `ArchivarAlmacenado`, `Reanudar`, `IngestarArchivo`, `DescubrirTipos` (discover + merge conservando decisiones; tipo tocado vuelve a no revisado con lista de cambios; ausente se marca, no se borra; carpeta manual no se pisa), `ValidarTipo` (check con severidades), `ReanudarConflicto`, `ExtenderRetencion`.
- Timers: `ReencolarEstancadas` (Watchdog, lee la Azure Table, no Dataverse; también detecta SAS emitida y nunca subida), `VigilarCatalogo` (hora), `Depurar` (día).
- Se porta de CMCourier la regla, no el código: sugerencia usar/omitir, derivación de ID corto y carpeta, política de merge, severidades, alcance del preflight (solo tipos en uso).

## 7. Storage

| Contenedor | Contenido | Ciclo de vida |
| --- | --- | --- |
| `cmv8-ingesta` | Solo lo que está en vuelo | Borrado a 60 días como última red (la fecha de depuración manda) |
| `cmv8-almacenados` | Lo que se queda: retenidos tras archivar, solo-blob, diferidos en espera | Sin borrado por ciclo de vida; Cool a 30 días, Cold a 180, nunca Archive (rehidratación de horas; la SAS de lectura no serviría). Inmutabilidad a nivel de versión habilitada |

Versionado en la cuenta e inmutabilidad en el contenedor solo con `wormHabilitado` (las versiones cuestan; sin WORM no hacen falta). Soft delete 7 días se queda. Event Grid `BlobCreated` sigue filtrado a `cmv8-ingesta`; `BlobDeleted` no hace falta.

## 8. Presupuesto de requests (usuario de aplicación)

| Camino | Requests |
| --- | --- |
| CM al subir, camino feliz | 2 |
| Solo Blob | 2 |
| Diferido | 2 al subir + 2 al archivar |
| Con conflicto (Observada o Detenida) | 3 + 1 por reanudar |
| Vencimiento de retención | 1 |
| Watchdog, VigilarCatalogo, Depurar | 0 salvo escrituras de resultado |

1.000 archivos por día ≈ 2.000 requests (8 % de un pool de 25.000), contra ~8.100 en v1. Verificado en Learn que ni `$batch` ni una Custom API reducen el conteo: el entitlement cuenta operaciones CRUD, dentro o fuera de un batch, incluidas las de plug-ins.

## 9. Seguridad: qué se mantiene y qué se desvía

Se mantiene íntegro de ADR-002: Shared Key apagado, SAS de delegación de usuario ligada con `sduoid`, dos llaves en cada PUT, Dataverse decide por suplantación, identidad administrada sin secretos, Easy Auth, auditoría por `scid`.

Desvíos declarados:

1. **`IngestarArchivo`**: los bytes pasan por la Function y el blob lo escribe la identidad administrada, no el usuario. Aceptado solo para ingesta desde flujos, con el límite de 100 MB y la cuenta técnica de la conexión como actor auditado.
2. **App registration «CMV8 PCF» de cliente público** para model-driven. Misma audiencia y mismas políticas de acceso condicional que el conector; una app más que gobernar.
3. **El worker no relee Dataverse antes de archivar**: una cancelación en la ventana de minutos entre la SAS y el proceso se compensa borrando en CM si el PATCH final falla.
4. **El SQL del AS400 vive solo en Azure.** El catálogo referencia consultas por nombre. Un maker nunca escribe SQL que se ejecute contra el AS400.

## 10. Cambios de infraestructura (Bicep)

Contenedor `cmv8-almacenados`; Blob Data Contributor de la identidad sobre los dos contenedores; versionado e inmutabilidad a nivel de versión; reglas de ciclo de vida (ingesta 60 días, niveles en almacenados, versiones viejas); tablas `ingesta` y `depuracion` con Table Data Contributor acotado; permiso para políticas de inmutabilidad (a verificar: rol personalizado o Blob Data Owner acotado al contenedor); app settings `AS400_CONSULTAS_JSON`, `OBSERVADA_DIAS`, `WORM_BLOQUEAR`, `CATALOGO_CACHE_MINUTOS`; timers nuevos; alerta de Application Insights para conflictos; app registration «CMV8 PCF» (fuera del Bicep; guía 24).

## 11. Decisiones que quedan en el Banco

- Pool de requests del tenant (solo Power Apps o Dynamics 365) y consumo actual de otras integraciones.
- `diasretencion` por tipo, N días para corregir una Observada, `wormBloquear`, qué tipos son inmutables.
- Aceptar la cuenta técnica de la conexión como actor auditado en la ingesta por flujo.
- Aceptar una app registration de cliente público para el PCF en model-driven.
- Qué consultas con nombre al AS400 existen y cuáles son rápidas (validación antes de subir).
- Correo del administrador documental para la alerta de conflictos (`correoAlertas`).

## 12. Consecuencias y migración desde v1

- `cmv8_solicitudingesta` deja de ser la tabla de negocio. Para los spikes se convierte en la primera tabla de adjuntos (columnas reservadas con prefijo `cmv8_`), sin lookup al padre. Nada de v1 se migra en producción: la v1 no salió del entorno de spikes.
- `docs/arquitectura.md`, las guías de montaje 07, 10, 11, 16, 18, 19, 20 y 21 y `docs/checklist-construccion-app.md` describen la v1 y se actualizan con el código.
- El PCF toma los 18 tokens `maker` de `docs/diseno/anatomia-y-tokens/tokens.json` como propiedades del manifiesto, `textos.es.json` como recursos y las medidas de `Anatomia.md` como CSS por variante y densidad.
- Más componentes que operar: dos timers nuevos, dos tablas, una app de administración, un catálogo. A cambio: cualquier solución del Banco adjunta documentos sin tocar código, el consumo de requests baja cuatro veces, y los conflictos tienen dueño y pantalla.

## 13. A verificar antes de cerrar el ADR

- OpenCMIS `getTypeDescendants` contra CM V8 real: prefijo de wire, propiedad de ID corto, duplicados (fixture local tomado de CMCourier; no entra al repo).
- Permiso de la identidad para `Set Blob Immutability Policy` y que el cambio de nivel Cool/Cold esté permitido sobre blobs inmutables.
- PCF con MSAL en la app móvil de Dynamics y en Teams.
- Que un PATCH suplantado con `Prefer: return=representation` cuente como una sola request.
- Tamaño real que soporta `IngestarArchivo` de punta a punta (acción de flujo, conector, límite HTTP de la Function).
- Retención legal (legal hold por versión) para tipos inmutables: hoy congelar solo saca el adjunto de la agenda.
- Vistas de detenidos y observados para el administrador: la app de administración solo ve la tabla de la solución base; cada solución del maker agrega sus propias vistas sobre su tabla de adjuntos.
