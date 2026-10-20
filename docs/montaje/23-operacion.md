# 23. Operación: estados, timers, administración documental, rotaciones, diagnóstico y errores conocidos

> Rol que lo ejecuta: Identidad (secreto del conector), Cloud (Function, secretos, monitoreo), Power Platform (conector y componente), **administrador documental** (catálogo, conflictos, detenidos) y el **usuario** de cada solución (observados). Depende de: el montaje completo (01 a 22). Lo necesitan: el equipo de soporte. Costo: sin costo propio (el monitoreo se paga en Log Analytics y Application Insights, ver las guías de infraestructura).

## Qué es y qué hace en esta solución

Es el runbook de operación de la v2 (ADR-003): qué significa cada estado de un adjunto y quién tiene que actuar, qué hacen los timers, cómo se destraba un adjunto Detenido, Observado o en Error, cómo se extiende una retención, qué hay que hacer periódicamente, cómo se publica una versión nueva de cada pieza, dónde mirar cuando algo falla, y qué significa cada error que ya se vio.

## Los diez estados

Viven en el choice global `cmv8_estadoingesta` (guía 18) y en la columna `estadoingesta` de cada tabla de adjuntos. Un estado dice DÓNDE está el archivo, no qué le va a pasar (ADR-003 §2).

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

Reglas: Observada y Detenida no se reintentan solas. Archivado, Rechazado y Depurado son terminales. El worker descarta mensajes de filas en Archivado o Rechazado (idempotencia).

## Los tres timers

| Timer | Cuándo | Qué hace | Qué mirar si falla |
| --- | --- | --- | --- |
| `Watchdog` | Cada 15 min (`0 */15 * * * *`) | Lee la Azure Table `ingesta` (no Dataverse): reencola lo estancado más de `ESTANCADA_MINUTOS` (15) y detecta SAS emitidas y nunca subidas. Los Error se reintentan hasta `maxDequeueCount` (5, `host.json`) | Adjuntos que no avanzan de Subido; mensajes en `ingesta-pendiente-poison` |
| `VigilarCatalogo` | Cada hora (`0 5 * * * *`) | Compara la definición viva de cada tipo en uso en CM contra el catálogo; registra conflictos nuevos en `cmv8_conflicto` (una fila por clave tipo + propiedad + clase) y dispara una alerta (ADR-003 §4.4; la alerta de Application Insights todavía no está en el Bicep). Detecta, no decide: nunca corre `ActualizarTipos` | Conflictos abiertos en la app de administración |
| `Depurar` | Diario, 02:30 UTC (`0 30 2 * * *`) | Lee la agenda `depuracion` de hoy y de los días pendientes. Con humano pendiente (Observada vigente, Detenida, Procesando) no borra y corre la fecha siete días. Si no: borra el blob y actualiza Dataverse (Archivado → vacía `rutablob`; Almacenado → Depurado; Observada vencida → Rechazado «vencida sin corrección»). Idempotente. Un WORM no vencido lo rechaza Storage: se reprograma | Blobs vencidos que siguen; entradas viejas en `depuracion` |

## App de administración documental

App model-driven **CMV8 Administración documental** (guía 18), para el rol **CMV8 Administrador documental**:

| Área | Qué se ve | Para qué |
| --- | --- | --- |
| Catálogo → Tipos de documento | Tipos descubiertos y locales; columnas humanas (en uso, carpeta, destino, momento, días de retención, inmutable) y `revisado` / `cambios` | Decidir qué tipos se usan y cómo; revisar lo que cambió tras `ActualizarTipos` |
| Catálogo → Propiedades de Content Manager | Propiedades de cada tipo: decisión usar/omitir, origen del valor, nombre lógico, consulta, literal, patrón | Darle origen a cada propiedad que se envía |
| Operación → Conflictos | Conflictos con clase, severidad, detalle, adjuntos detenidos, estado abierto/resuelto | Resolver lo que detiene adjuntos |
| Operación → Adjuntos | La tabla de adjuntos con su estado | Ver detenidos (estado Detenida) y observados (estado Observada) |

Hoy la app usa formularios y vistas por defecto: los detenidos y observados se ven filtrando por estado. Los botones `DescubrirTipos`, `ActualizarTipos`, `ValidarTipo` y `ReanudarConflicto` llegan con el conector (`dataverse/README.md`); mientras tanto se llaman desde el conector (guía 20) o directo contra la Function.

## Qué hacer con un adjunto que no avanza

### Detenida (actúa el administrador documental)

Un conflicto de catálogo: el catálogo no coincide con lo que CM exige hoy (obligatoria de CM que no se va a enviar, tipo ausente, carpeta inexistente, largo mayor al máximo, propiedad desconocida, columna inexistente).

1. En la app → **Conflictos**: abrir el conflicto abierto; el detalle dice qué se esperaba, qué publica CM y qué hay que cambiar.
2. Si CM cambió el tipo: correr **`ActualizarTipos`** (`POST /api/catalogo/actualizar`). El tipo tocado vuelve a no revisado con su lista de cambios; un tipo ausente se marca, no se borra; una carpeta manual no se pisa.
3. **Revisar**: completar la decisión y el origen de las propiedades nuevas, marcar el tipo como revisado; si falta una columna en la tabla de adjuntos de una solución, pedírsela al maker (guía 18, `ValidarTabla`). `ValidarTipo` (`GET /api/catalogo/validar?tipo={id}`) confirma que no queden hallazgos críticos.
4. **`ReanudarConflicto`** (`POST /api/catalogo/conflictos/{id}/reanudar`): la Function invalida el caché del catálogo, busca en la Azure Table los adjuntos con esa clave y los reencola. Responde `{reencolados}`.
5. Si al reprocesar falta el valor en la fila, el adjunto pasa a Observada: catálogo → administrador → datos → usuario.

### Observada (actúa el usuario)

Falta o es inválido un valor de ESTE archivo (o el AS400 no lo reconoce). El detalle está en `mensajeerror` de la fila.

1. El usuario corrige la fila (completa o arregla la columna).
2. La app llama **`Reanudar(tabla, id)`** (`POST /api/adjuntos/{tabla}/{id}/reanudar`): el broker relee la fila suplantando al usuario, revalida, limpia `mensajeerror` y reencola. Es explícito porque el Watchdog ya no mira Dataverse.
3. Si nadie corrige en `OBSERVADA_DIAS` días (30 por defecto, decisión del Banco), `Depurar` borra el blob y pasa la fila a Rechazado «vencida sin corrección».

### Error (actúa operación)

Causa técnica: CM caído, AS400 caído, rechazo de CM no previsto.

1. El Watchdog reintenta solo hasta `maxDequeueCount` (5).
2. Agotados los reintentos, el mensaje queda en la cola poison `ingesta-pendiente-poison` del storage host. Revisar la causa en Application Insights (excepción del worker) y en `mensajeerror`.
3. Corregida la causa, reintentar a mano con **`Reanudar(tabla, id)`** (acepta Observada o Error; 409 en otro estado).
4. Para los rechazos de CM que no encajan en ninguna clase conocida existe la clase de conflicto «rechazo de CM no clasificado», que ve el administrador documental (ADR-003 §1.2).

### Almacenado diferido (actúa el negocio)

Cuando ocurre el evento de negocio, la app o un flujo llama **`Archivar(tabla, id)`** (`POST /api/adjuntos/{tabla}/{id}/archivar`): el broker verifica escritura suplantando al usuario y encola; el worker procesa desde `cmv8-almacenados`. 409 si el adjunto no está Almacenado o su tipo no va a CM. Un adjunto de tipo local se promueve cambiando primero su tipo a uno descubierto.

## Retención

- La fecha de depuración la calcula el worker desde `diasretencion` del tipo al dejar el archivo en `cmv8-almacenados` (diferido en espera: sin fecha hasta archivarse) y la guarda en la Azure Table; la agenda vive en `depuracion`. Tipo sin `diasretencion` = permanente.
- **`ExtenderRetencion(tabla, id, fecha)`** (`POST /api/adjuntos/{tabla}/{id}/retencion` con `{fecha}` en `yyyy-MM-dd`): mueve la fecha. Sin fecha = retención permanente (legal); si el tipo es inmutable, legal hold por versión. **Un tipo inmutable solo admite extender**: una política bloqueada nunca se acorta.
- El ciclo de vida de `cmv8-almacenados` solo cambia de nivel (Cool a 30 días, Cold a 180); no borra (guía 07).

## ¿Imprescindible?

**Veredicto: No aplica: es operación, no un componente**

- Rotación **obligatoria**: el secreto del conector, antes de los 6 meses (si vence, nadie puede crear ni renovar conexiones del conector). Las contraseñas de IBM i y de Content Manager, según la política del Banco.
- Rotación que **no existe**: el broker no tiene secreto (credencial federada, guía 14) y Easy Auth no usa credencial. La identidad administrada no tiene nada que rotar.
- Lo que podría eliminar la única rotación de Entra: autenticar el conector con una identidad administrada del conector en vez de secreto (documentado por Microsoft, no probado en este proyecto; ver guía 02).

## Valores del cuaderno

Todos los del cuaderno (ver las guías de origen). Para rotar el secreto del conector hace falta además el **id del conector** (`pac connector list --environment "$ENV_ID"`).

## Con comandos

| Qué | Cuándo | Cómo |
| --- | --- | --- |
| Rotar el secreto del conector | Antes de los 6 meses | `az ad app credential reset … --append` al vault (guía 02, "Secreto del conector"), `pac connector update --connector-id <id>` con la copia temporal (guía 20), borrar la credencial vieja con `az ad app credential delete`. Las conexiones existentes siguen hasta que el usuario vuelva a iniciar sesión |
| Rotar contraseñas de IBM i o CM | Según la política del Banco | Guía 17 de nuevo y `az functionapp restart`; la Function toma el valor nuevo al reiniciar (o sola en 24 h) |
| Publicar una versión nueva de la Function | Cada cambio | Guía 16; `/api/salud` devuelve la versión |
| Publicar una versión nueva del componente | Cada cambio | Subir `version` en el manifiesto y repetir la guía 19; la app toma la versión nueva al abrirla en Studio |
| Cambiar la Function de hostname o de cuenta de Storage | Nunca sin plan | Hay que repetir la guía 19 (manifiesto) y la guía 20 (swagger) |
| Recrear el conector | Solo si es inevitable | Guía 20 y, después, registrar la URL de redirección nueva (guía 02): cambia el hash |
| Vigilar | Siempre | App Insights: trazas del broker (`SasLote`, OBO), del worker (`ProcesarIngesta`) y de los tres timers, excepciones; conflictos abiertos en la app de administración; mensajes en `ingesta-pendiente-poison`; `StorageBlobLogs` para 403; presupuesto con alertas |
| Revisar el catálogo | Cuando `VigilarCatalogo` registra un conflicto o CM publica tipos nuevos | Administrador documental: `ActualizarTipos` (tipos en uso) o `DescubrirTipos` (todo el árbol), revisar, marcar revisado (sección "Detenida") |

### Rotación del secreto del conector, paso a paso

```bash
# 1. secreto nuevo, directo al vault (la credencial vieja sigue viva gracias a --append)
FIN=$(date -u -d '+6 months' +%Y-%m-%dT%H:%M:%SZ)
az keyvault secret set --vault-name "<vault de secretos de identidad del Banco>" --name cmv8-conector-secreto \
  --expires "$FIN" \
  --value "$(az ad app credential reset --id "$CONECTOR_APP_ID" --append --display-name power-platform-conector --end-date "$FIN" --query password -o tsv)" \
  --query attributes.expires -o tsv

# 2. actualizar el conector con la copia temporal fuera del repo (guía 20, "Actualizar un conector existente")

# 3. identificar y borrar la credencial vieja (la de vencimiento más cercano)
az ad app credential list --id "$CONECTOR_APP_ID" --query '[].{id:keyId, nombre:displayName, fin:endDateTime}' -o table
az ad app credential delete --id "$CONECTOR_APP_ID" --key-id "<keyId de la credencial vieja>"
```

Borrar la vieja recién después de probar una conexión nueva con el conector actualizado (operación `Salud` desde la pestaña **Test**: 200).

## Con el portal

- Secreto del conector: Entra admin center → **App registrations** → `CMV8 Conector (Power Platform)` → **Certificates & secrets** → **Client secrets**: ver vencimientos, crear el nuevo (**New client secret**) y borrar el viejo (ícono de papelera). El valor nuevo se pega en el conector (make.powerapps.com → **Custom connectors** → CMV8 Broker → **Edit** → **Security** → **Client secret** → **Update connector**) y en el vault.
- Contraseñas de IBM i y CM: guía 17, "Con el portal" (**New Version** del secreto y **Restart** de la Function).
- Monitoreo: Azure portal → Application Insights del ambiente → **Transaction search** / **Logs**; Log Analytics → tabla `StorageBlobLogs`; cuenta de adjuntos → **Storage browser** → **Tables** → `ingesta` / `depuracion` (estado operativo); cuenta host → **Queues** → `ingesta-pendiente-poison`; **Cost Management** → presupuesto del grupo de recursos.
- Administración documental: app **CMV8 Administración documental** (sección de arriba).

## Verificación

- Después de rotar el secreto del conector: `az ad app credential list` muestra una sola credencial `power-platform-conector` con vencimiento a 6 meses; `Salud` desde **Test** devuelve 200; un piloto sube un archivo.
- Después de rotar contraseñas: referencias de Key Vault en `Resolved` (guía 17) y un adjunto llega a Archivado.
- Después de destrabar un conflicto: el conflicto queda Resuelto, `ReanudarConflicto` informa `reencolados` > 0 y esos adjuntos salen de Detenida.

## Mapa de diagnóstico por tramo (M10)

La llave que une las fuentes es el adjunto: **tabla lógica + id**. Es la fila en Dataverse, la PartitionKey + RowKey en la Azure Table `ingesta` y el prefijo `<tabla>/<id>/` del blob.

| Tramo del flujo | Dónde se ve | Qué buscar |
| --- | --- | --- |
| App y conector (pasos 1, 2, 7) | Monitor de Power Apps | Status y cuerpo de la respuesta de `EmitirSasLote`; en un lote, el error de cada adjunto viaja en `items[]` |
| Easy Auth (paso 3) | Solo el 401 que ve el conector | `AppServiceAuthenticationLogs` no se exporta a Log Analytics: pendiente en el Bicep (M10) |
| Broker → Dataverse (paso 4) | Application Insights: dependencias hacia Dataverse | 403 = el usuario no puede escribir esa fila; 502 = Dataverse inaccesible |
| OBO (paso 5) | Application Insights + registros de inicio de sesión de Entra | Código AADSTS en la traza |
| Broker → Azure Table | Application Insights: dependencias hacia `*.table.core.windows.net`; la tabla `ingesta` | 403 = falta Table Data Contributor (guía 07); la fila del adjunto con `estado`, `sasEmitidaEn` |
| PCF → Storage (paso 6) | `StorageBlobLogs` | Status, `AuthenticationType`, `RequesterUpn`, `scid`. Los 403 `AuthenticationFailed` no aparecen: métrica Transactions por `ResponseType` |
| Event Grid (paso 8) | Métricas del system topic | Delivery Failed, Dropped Events |
| Worker (pasos 9 a 11) | Application Insights: trazas de `ProcesarIngesta`, dependencias hacia AS400, CM y Dataverse; la tabla `ingesta` | Columnas `estado`, `intentos`, `mensaje`, `conflictoClave`, `subidoEn`, `procesadoEn`; en Dataverse, `estadoingesta` y `mensajeerror` |
| Catálogo | App de administración → Conflictos; tabla `ingesta` con PartitionKey `conflicto` | Clase, severidad, detalle; `conflictoId` de la deduplicación |
| Retención | Tabla `depuracion` (PartitionKey = día) y `fechaDepuracion` en `ingesta`; trazas de `Depurar` | Entradas de días pasados que siguen (humano pendiente o WORM no vencido: reprogramadas) |
| Reintentos agotados | Cola `ingesta-pendiente-poison` del storage host | El mensaje con tabla + id |

Leer las Azure Tables con Entra (Shared Key está apagado) exige un rol de datos de tablas para quien consulta, por ejemplo Storage Table Data Reader acotado a la tabla; el Bicep no se lo da a ninguna persona. Con ese rol:

```bash
az storage entity show --auth-mode login --account-name "$STORAGE_ADJ" --table-name ingesta \
  --partition-key "<tabla lógica>" --row-key "<id del adjunto>"
```

Si la tabla se pierde, se reconstruye desde los blobs y Dataverse (ADR-003 §3).

## Errores que ya vimos

| Síntoma | Causa | Dónde se arregla |
| --- | --- | --- |
| `AADSTS50011` al crear la conexión | Falta la URL de redirección específica del conector | `02-entra-conector.md` (y `20-conector-personalizado.md` para copiarla) |
| 401 de la Function con token válido | Audiencia o issuer de Easy Auth distintos de los del token (v1 contra v2, GUID contra `api://`) | `15-easy-auth.md` (y `01-entra-broker.md` para la versión del token) |
| 403 `AccesoDenegado` del broker para un adjunto | El usuario no tiene el rol de usuarios de esa solución o la fila no le corresponde | `18-dataverse.md` |
| Error en un item del lote: no corresponde SAS | La fila no está en Creada ni en Error | `21-app-canvas.md`: la app crea las filas antes de pedir el lote |
| 409 de `Reanudar` / `Archivar` | El adjunto no está en Observada o Error / no está Almacenado o su tipo no va a CM | Sección "Qué hacer con un adjunto que no avanza" |
| Adjuntos en Detenida | Conflicto de catálogo | Sección "Detenida" |
| Adjuntos en Observada | Falta o es inválido un valor de la fila | Sección "Observada" |
| 401 con `WWW-Authenticate: Bearer error="interaction_required"` | Acceso condicional sobre Azure Storage que el token del conector no satisface | Hallazgo 10 de la guía de spikes; `docs/backlog-tecnico.md` §10 |
| 403 `AuthorizationPermissionMismatch` al subir | Faltó el bearer de Storage junto con la SAS ligada, o el usuario tiene un rol de datos que no debería | `21-app-canvas.md` (cableado); roles del contenedor en las guías de infraestructura |
| 502 del broker al autorizar | Dataverse inaccesible: firewall de IP o usuario de aplicación sin rol | `18-dataverse.md` |
| `AADSTS700211` / `AADSTS70021` en el OBO | Credencial federada con subject equivocado | `14-credencial-federada.md` |
| `PessimisticConcurrencyConflict` en `pac connector create` | Operación previa en curso en el APIM interno | `20-conector-personalizado.md`: reintentar a los 5 minutos |
| Referencias de Key Vault en `UnauthorizedClient` / `AccessToKeyVaultDenied` | Rol de la identidad sin propagar, o vault sin acceso público ni private endpoint | `17-secretos-key-vault.md` y `09-key-vault.md` |
| `POST /api/sas/lote` sin token devuelve 200 | Easy Auth no aplicado | `15-easy-auth.md` |
| (Anticipado) 403 de la Function contra `*.table.core.windows.net` | Falta Table Data Contributor de la identidad sobre `ingesta` o `depuracion` | `07-storage-adjuntos.md`, "Roles v2" |
| (Anticipado) El worker no puede escribir el resultado en la tabla de una solución nueva | Falta asignar «CMV8 Worker - <solución>» al usuario de aplicación | `18-dataverse.md`, guía del maker paso 3 |
