# 21. App canvas de carga de adjuntos (v2)

> Rol que lo ejecuta: Power Platform (creador de la app, normalmente el maker de la solución). Depende de: 18 (tabla de adjuntos de la solución, sus roles y el catálogo `cmv8_tipodocumento`), 19 (componente `CargaAdjuntoBlob` 1.0.0), 20 (conector y DLP). Lo necesitan: 22 (humo). Costo: sin costo propio; cada usuario necesita Power Apps Premium (conector personalizado y componente con `external-service-usage`).

## Qué es y qué hace en esta solución

Es el producto: la pantalla donde el usuario, desde un registro padre de SU solución (un crédito, un caso), elige el tipo de documento, agrega uno o varios archivos y los sube; y donde después ve en qué estado está cada adjunto y actúa cuando le toca. La app se construye con `docs/checklist-construccion-app.md`: la respuesta de `EmitirSasLote` trae las SAS y el token de Storage del usuario, y la app la guarda en UNA variable (`varLote`), se la pasa al componente y la limpia al terminar; nunca la persiste ni la muestra.

En v2 no hay una tabla única de solicitudes: cada solución del Banco tiene **su propia tabla de adjuntos** (guía 18, «Guía del maker»), con las columnas reservadas `estadoingesta`, `tipodocumento`, `rutablob`, `cmisobjectid`, `mensajeerror`, `nombrearchivo`, `tamanobytes` y `contenttype`, más la lookup al padre y las columnas de metadatos que pidan sus tipos. En los ejemplos: tabla `cred_adjuntocredito` ('Adjuntos de crédito'), padre 'Crédito', componente `CargaAdjuntoBlob1`, combo `cmbTipoDocumento`. Ajustar a los nombres reales.

Flujo de subida (ADR-003 §4.1, protocolo del control en `pcf/CargaAdjuntoBlob/README.md`):

1. El usuario elige el tipo de documento y agrega archivos a la cola del componente; «Subir todo» publica un **pedido** (`pendientes`, `numeroPedido` nuevo, `estado = "listo-para-sas"`).
2. `OnChange` del componente, **una vez por `numeroPedido`**: la app crea **N filas** en la tabla de adjuntos en estado Creada (con tipo, nombre, tamaño, content type, padre y metadatos), llama `'CMV8 Broker'.EmitirSasLote` con los items en el mismo orden de `pendientes` y deja `JSON(respuesta)` en `varLote`, que entra al componente por `lote`.
3. El componente sube con las dos llaves y publica `resultados`. La app marca **Subido** cada archivo que terminó bien (solo si la fila sigue en Creada o Error).
4. Cuando el componente deja de esperar SAS, la app limpia `varLote`.
5. Desde ahí el worker mueve la fila por los estados; la app los muestra y ofrece `Reanudar` (Observada) y `Archivar` (Almacenado).

## ¿Imprescindible?

**Veredicto: Imprescindible** (por definición: es lo que usa el usuario), salvo que la solución use model-driven

- Qué aporta: la experiencia de usuario y el orden correcto de las llamadas (filas antes de SAS; limpieza de las llaves al terminar; Subido solo sobre filas que el worker todavía no tomó).
- Qué pasa si no está: el usuario no tiene desde dónde subir; el resto es infraestructura sin cliente (salvo la ingesta por flujo con `IngestarArchivo`).
- Qué decisión lo haría distinto: el **tipo** de app de cada solución del Banco.
- Alternativa y su tradeoff: **model-driven con `AdjuntosGrilla`** (guías 19 y 24): el control de dataset en la subcuadrícula de adjuntos del formulario del padre crea las filas y llama al broker solo, con su propio token por MSAL. Ventaja: formularios, vistas y seguridad de Dataverse sin construir pantallas, y no hay Power Fx que mantener. Costo: una app registration de cliente público más que gobernar y el riesgo de popups o iframes bloqueados en móvil y Teams (ADR-003 §13). **Página personalizada** dentro de una model-driven: acepta Power Fx y conectores, así que reutiliza esta guía tal cual, con las limitaciones de las páginas personalizadas.

## Valores del cuaderno

| Variable | Produce / consume | Cómo se obtiene |
| --- | --- | --- |
| Conector `CMV8 Broker` | Consume | Guía 20 |
| Componente `CargaAdjuntoBlob` 1.0.0 | Consume | Guía 19 |
| Tabla de adjuntos de la solución (nombre lógico, p. ej. `cred_adjuntocredito`) | Consume | Guía 18, «Guía del maker»; `ValidarTabla` con `ok: true` |
| Tabla `Tipos de documento` (`cmv8_tipodocumento`) | Consume | Guía 18 (solución base) |
| Consultas con nombre al AS400 (si la pantalla muestra datos del cliente) | Consume | `AS400_CONSULTAS_JSON` de la Function (guía 10) |

No hay identificadores nuevos: todo lo resuelve Studio por nombre.

## Con comandos

No aplica: una canvas app se construye en Power Apps Studio. Cuando esté terminada, se agrega a la solución del maker y viaja con ella (`pac solution export` cuando el Banco defina su ALM).

## Con el portal

En **make.powerapps.com** → entorno del ambiente → **Solutions** → la solución del maker → **New** → **App** → **Canvas app** (crearla dentro de la solución evita moverla después).

### 1. Datos

**Data** → **Add data**: la tabla de adjuntos de la solución, la tabla del padre, **Tipos de documento** (Dataverse) y el conector **CMV8 Broker** (crea la conexión del creador).

### 2. Tipo de documento: lookup obligatoria al catálogo

El tipo de documento es la columna reservada `tipodocumento`, una **lookup a `cmv8_tipodocumento`**, obligatoria (ADR-003 §1.1). No es un texto ni un choice local: es lo que le dice al worker qué tipo de Content Manager, qué carpeta, qué propiedades, qué destino, qué momento y qué retención aplican.

- En la tabla del maker, la columna se marca **Business required**. El rol de usuarios necesita Read y Append To sobre `cmv8_tipodocumento` para poder elegirlo (guía 18).
- Combo box `cmbTipoDocumento`, selección simple:

| Propiedad | Valor |
| --- | --- |
| `Items` | `Filter('Tipos de documento', 'En uso' = true)` |
| `DisplayFields` | `["Nombre"]` (o `Código` + `Nombre`, según lo que reconozca el usuario) |
| `SelectMultiple` | `false` |

  Solo los tipos **en uso** se ofrecen: son los que el administrador documental revisó y los que `VigilarCatalogo` vigila. El tipo vale para todo el lote; para subir archivos de otro tipo, se hace otro lote.
- Si el tipo exige metadatos (columnas `<prefijo>_<nombrelogico>` que lista `ValidarTabla`), la pantalla los pide antes de subir y van en la misma `Patch` que crea cada fila. Si la propiedad declara un `Patrón` en el catálogo, la app puede validarlo con `IsMatch` antes de habilitar la subida; el broker y el worker lo validan igual.

### 3. El componente

**Insert** → **Get more components** → **Code** → `CargaAdjuntoBlob` → **Import**. Insertarlo en la pantalla (queda como `CargaAdjuntoBlob1`).

| Propiedad | Valor |
| --- | --- |
| `lote` | `varLote` |
| `tabla` | `"cred_adjuntocredito"` (nombre lógico de la tabla de adjuntos) |
| `disparadorReinicio` | `varReinicio` |
| `tiposAceptados` | Sintaxis de `accept` (por defecto `video/*,application/pdf`; vacío = todo) |
| `tamanoMaximoMB` | Por archivo (por defecto 250) |
| `loteMaximo` | Archivos por lote (por defecto 20; el broker también limita a 20) |
| `variante`, `densidad`, `modo` | `fluent` / `institucional` / `moderno`; `compacta` / `comoda`; `claro` / `oscuro` / `auto` |
| Tokens del maker (`PrimaryColor`, `FontFamily`, `BorderRadius`…) | Vacío = valor de la variante. Para alinear con el tema de la app, por ejemplo `PrimaryColor: "#1F4E79"` |

`paralelismoArchivos` (1 a 4) y `paralelismoBloques` (1 a 8) se dejan en su defecto salvo que el humo diga otra cosa. Los textos del control salen del diseño (`textos.es.json`); no se sobreescriben desde la app.

### 4. `OnChange` del componente

```powerfx
// 1) Pedido nuevo: una sola vez por numeroPedido. Crear las filas que falten y pedir el lote.
If(
    Self.estado = "listo-para-sas" && Self.numeroPedido <> varPedidoAtendido,
    Set(varPedidoAtendido, Self.numeroPedido);
    If(
        IsBlank(cmbTipoDocumento.Selected),
        // El control necesita UNA respuesta por pedido, aun en error, para no quedar esperando.
        Set(varLote, JSON({ error: "Elegí el tipo de documento antes de subir.", codigo: 400 })),

        // colPedido no tiene secretos (ids, nombres y tamaños): puede ser una colección.
        ClearCollect(colPedido,
            ForAll(Table(ParseJSON(Self.pendientes)) As p,
                {
                    id: If(IsBlank(Text(p.Value.id)),
                        Patch('Adjuntos de crédito', Defaults('Adjuntos de crédito'), {
                            'Nombre de archivo': Text(p.Value.nombreArchivo),
                            'Tamaño en bytes':   Value(p.Value.tamanoBytes),
                            'Content type':      Text(p.Value.contentType),
                            'Tipo de documento': cmbTipoDocumento.Selected,
                            'Estado de ingesta': 'Estado de ingesta CMV8'.Creada,
                            'Crédito':           varCredito
                            // + las columnas de metadatos que pida el tipo
                        }).'Adjunto de crédito',
                        GUID(Text(p.Value.id))),          // reintento: la fila ya existe
                    nombreArchivo: Text(p.Value.nombreArchivo),
                    tamanoBytes:   Value(p.Value.tamanoBytes),
                    contentType:   Text(p.Value.contentType)
                }));
        // La respuesta trae las SAS y el token: va a UNA variable, nunca a una colección ni a Dataverse.
        Set(varLote,
            IfError(
                JSON('CMV8 Broker'.EmitirSasLote({
                    tabla: "cred_adjuntocredito",
                    items: ForAll(colPedido, { id: id, nombreArchivo: nombreArchivo,
                                               tamanoBytes: tamanoBytes, contentType: contentType })
                })),
                JSON({ error: FirstError.Message, codigo: 0 })))
    )
);

// 2) Marcar Subido lo que terminó bien: una vez por id, y solo si la fila sigue en Creada o Error.
ForAll(
    Filter(Table(ParseJSON(Self.resultados)) As r,
        Text(r.Value.estado) = "subido" && !(Text(r.Value.id) in colAtendidos.id)) As r,
    With({ fila: LookUp('Adjuntos de crédito', 'Adjunto de crédito' = GUID(Text(r.Value.id))) },
        If(fila.'Estado de ingesta' = 'Estado de ingesta CMV8'.Creada ||
           fila.'Estado de ingesta' = 'Estado de ingesta CMV8'.Error,
            Patch('Adjuntos de crédito', fila, { 'Estado de ingesta': 'Estado de ingesta CMV8'.Subido })));
    Collect(colAtendidos, { id: Text(r.Value.id) })
);

// 3) Nada esperando SAS: la variable con las llaves se vacía.
If(Self.estado <> "listo-para-sas" && !IsBlank(varLote), Set(varLote, Blank()))
```

Por qué cada parte:

- **Una vez por `numeroPedido`** (`varPedidoAtendido`): el progreso dispara `OnChange` hasta dos veces por segundo. Sin la guarda, cada disparo crearía filas y pediría otro lote.
- **Mismo orden que `pendientes`**: el broker v2 devuelve solo `{id, uploadUrl, rutaBlob, expiraUtc}` o `{id, error, codigo}` por item, en el orden del pedido, y el control empareja por posición. Reordenar los items (`SortByColumns`, `Distinct`) puede poner la SAS de un archivo en otro.
- **Subido solo desde Creada o Error**: Event Grid avisa al worker apenas el blob existe; si el worker ya pasó la fila a Procesando (o más allá) cuando llega este `Patch`, marcar Subido la haría retroceder.
- **`JSON(respuesta)` en `varLote`**: el componente recibe texto; la respuesta completa (con `tokenStorage`) nunca pasa por una colección.

Los nombres de enumeración del estado dependen de cómo Studio exponga el choice global (`'Estado de ingesta CMV8'` es su nombre visible en la solución base): verificar en Studio con IntelliSense.

### 5. Limpieza y reinicio

- Pantalla, `OnHidden`: `Set(varLote, Blank())`.
- Reiniciar el control (por ejemplo, al cambiar de registro padre): `Set(varReinicio, varReinicio + 1); Clear(colAtendidos)`. No cambiar `disparadorReinicio` mientras la app espera `EmitirSasLote`.
- Archivos cancelados o quitados después de crear su fila quedan en **Creada**; `resultados` los trae como `cancelado` con su `id`. La app decide si los borra (`Remove`).
- Para el usuario: `CargaAdjuntoBlob1.resumen` y `resultados.mensajeError` (nunca traen URLs ni tokens). Nada muestra `varLote`.

### 6. Lista de adjuntos y los diez estados

Galería `galAdjuntos` sobre la tabla de adjuntos filtrada por el padre (`Filter('Adjuntos de crédito', 'Crédito'.'Crédito' = varCredito.'Crédito')`), con un botón o un temporizador que haga `Refresh('Adjuntos de crédito')`: el estado lo cambian el worker y los timers, no la app.

Cada estado se muestra con ícono y texto, y dice **quién tiene que actuar** (ADR-003 §2; detalle operativo en la guía 23):

| Estado | Qué ve el usuario | Acción en la app |
| --- | --- | --- |
| Creada | «Pendiente de subir» | Ninguna en la lista: se sube desde el componente (la fila se reintenta con un lote de uno) |
| Subido | «Recibido, en cola» | Ninguna |
| Procesando | «Procesando» | Ninguna |
| Archivado | «Archivado en Content Manager» | Ninguna |
| Almacenado | «Guardado» | **Archivar**, si el negocio decide el momento (tipo diferido) |
| Observada | «Hay que corregir un dato» + `mensajeerror` | **Corregir** (editar la fila) y **Reanudar**. Si nadie corrige en los días que fije el Banco (`OBSERVADA_DIAS`), pasa a Rechazado |
| Detenida | «Detenido: lo resuelve el administrador documental» | **Ninguna.** Solo informa: el problema está en el catálogo, no en el dato del usuario. Lo destraba `ReanudarConflicto` desde la app de administración |
| Error | «Error técnico» + `mensajeerror` | Ninguna por defecto: operación reintenta (Watchdog o manual, guía 23) |
| Rechazado | «Rechazado» + `mensajeerror` (malware o vencida sin corrección) | Ninguna: el archivo ya no existe |
| Depurado | «Eliminado por retención» | Ninguna |

Texto del estado:

```powerfx
Switch(ThisItem.'Estado de ingesta',
    'Estado de ingesta CMV8'.Creada,     "Pendiente de subir",
    'Estado de ingesta CMV8'.Subido,     "Recibido, en cola",
    'Estado de ingesta CMV8'.Procesando, "Procesando",
    'Estado de ingesta CMV8'.Archivado,  "Archivado en Content Manager",
    'Estado de ingesta CMV8'.Almacenado, "Guardado",
    'Estado de ingesta CMV8'.Observada,  "Hay que corregir un dato",
    'Estado de ingesta CMV8'.Detenida,   "Detenido: lo resuelve el administrador documental",
    'Estado de ingesta CMV8'.Error,      "Error técnico",
    'Estado de ingesta CMV8'.Rechazado,  "Rechazado",
    'Estado de ingesta CMV8'.Depurado,   "Eliminado por retención")
```

`mensajeerror` se muestra en Observada, Error y Rechazado. **Observada pide corregir y reanudar**; **Detenida solo informa**: no ofrecer ningún botón al usuario en Detenida, porque ninguna acción suya lo destraba.

### 7. Operaciones del conector desde la app

Todas reciben tabla lógica + id; el broker suplanta al usuario y verifica en Dataverse que pueda escribir esa fila (403 si no).

**Reanudar** (Observada), `OnSelect` del botón, visible solo en Observada y después de que el usuario corrigió la fila:

```powerfx
IfError(
    'CMV8 Broker'.Reanudar("cred_adjuntocredito", Text(ThisItem.'Adjunto de crédito'));
    Notify("Se reanudó el procesamiento.", NotificationType.Success),
    Notify(FirstError.Message, NotificationType.Error));
Refresh('Adjuntos de crédito')
```

El broker relee la fila, revalida, limpia `mensajeerror` y reencola (ADR-003 §4.4). Es explícito: el Watchdog ya no mira Dataverse, así que una corrección sin `Reanudar` no hace nada.

**Archivar** (Almacenado, cuando ocurre el evento de negocio de un tipo diferido): igual, con `'CMV8 Broker'.Archivar("cred_adjuntocredito", Text(ThisItem.'Adjunto de crédito'))`. El broker verifica escritura y encola; el worker archiva desde `cmv8-almacenados` (ADR-003 §4.3). Un adjunto de tipo local se promueve cambiando primero su tipo a uno descubierto.

**ConsultarAs400** (mostrar datos del cliente antes de subir): `'CMV8 Broker'.ConsultarAs400("<consulta>", { idcliente: txtCliente.Text })`. Solo existen las consultas con nombre declaradas en `AS400_CONSULTAS_JSON`; el SQL nunca vive en la app. Devuelve la primera fila o 404. La respuesta no tiene esquema en el swagger (objeto libre): verificar en Studio cómo la tipa y leer los campos con `Text()`/`Value()`. Sirve para mostrar y precargar; el valor que llega a Content Manager lo resuelve el worker según el catálogo.

**EmitirSasLectura** (ver un adjunto que sigue en Blob): devuelve `downloadUrl` (SAS de lectura de un blob, 5 minutos) y `tokenStorage`; 409 si el documento ya está solo en Content Manager. Va a una variable propia, con las mismas reglas que `varLote`. **Limitación:** la SAS está ligada al usuario (`sduoid`) y Storage exige el bearer junto con ella; `Launch(downloadUrl)` o un control de imagen no mandan `Authorization`, y `CargaAdjuntoBlob` solo sube. Hoy la app canvas no tiene cómo descargar con las dos llaves: no ofrecer «Ver» hasta que exista ese componente.

### 8. Publicar y compartir

1. **Save** → **Publish**.
2. **Share**: compartir la app con los usuarios piloto. Compartir también el conector (guía 20). La conexión del conector es por usuario; nunca compartida ni embebida con una cuenta de servicio.
3. Los usuarios tienen el rol de usuarios de la solución (guía 18) y, para elegir el tipo, Read y Append To sobre `cmv8_tipodocumento`.

Reglas del checklist que se revisan antes de publicar (resumen; la lista completa está en `docs/checklist-construccion-app.md`):

- La respuesta de `EmitirSasLote` va en UNA variable (`varLote`), con las SAS y el token juntos; nunca en una colección.
- `varLote` se limpia cuando el componente deja de estar en `listo-para-sas` y en `OnHidden`.
- Ningún `Patch`, `Collect` ni `SaveData` escribe `uploadUrl` ni `tokenStorage`; ningún control los muestra.
- Cada `numeroPedido` se atiende una sola vez, y siempre con una respuesta (aun de error).
- No se hace `Patch` a Subido si la fila no está en Creada o Error.
- `EmitirSasLote` y `EmitirSasLectura` no se llaman desde Power Automate; un flujo usa `IngestarArchivo`.

## Verificación

- Un piloto abre la app desde un registro padre, elige un tipo en uso, agrega tres archivos chicos y sube: se crean tres filas en Creada, pasan a Subido y, según el tipo, a Archivado o Almacenado.
- «Subir todo» sin tipo elegido: los archivos pasan a Error con «Elegí el tipo de documento antes de subir.» y no se crea ninguna fila.
- Un adjunto forzado a Observada (por ejemplo, con un metadato vacío) muestra el mensaje, se corrige y con **Reanudar** vuelve a Subido y avanza. Uno en Detenida no ofrece acciones.
- Revisión del checklist marcada completa antes de publicar.
- La prueba de 100 MB, el usuario sin rol y los logs de Storage son el humo (guía 22).

## Errores que ya vimos

| Síntoma | Causa | Arreglo |
| --- | --- | --- |
| 409 del broker en un item del lote | La fila no está en Creada o Error (ya se subió, o el worker la tomó) | No reutilizar ids de filas que avanzaron; el reintento es solo para Creada o Error |
| 403 `AccesoDenegado` del broker | El usuario no tiene el rol de usuarios de la solución, la fila no le corresponde según la profundidad del rol, o la tabla no es una tabla de adjuntos que pueda escribir | Guía 18 (roles del maker) |
| (Anticipado) El item vuelve con «El adjunto no tiene tipo de documento» | La fila se creó sin `tipodocumento` | Lookup obligatoria (paso 2) y guarda del `OnChange` |
| (Anticipado) Los archivos quedan en «Solicitando permiso» | El `OnChange` no dejó respuesta en `varLote` para ese `numeroPedido` (falló antes de `Set`, o la guarda lo saltó) | Envolver la llamada en `IfError` y responder siempre |
| (Anticipado) Una fila vuelve de Procesando o Archivado a Subido | El `Patch` a Subido no verificaba el estado | Condición Creada/Error del paso 4 |
| (Anticipado) Muchas filas en Creada sin archivo | Archivos cancelados o quitados después de crear su fila | Borrarlas con los `cancelado` de `resultados`; el Watchdog detecta SAS emitidas y nunca subidas |
| 403 `AuthorizationPermissionMismatch` al subir | Faltó el bearer de Storage junto con la SAS ligada (`lote` no cableado), o el usuario tiene un rol de datos que no debería | Cableado de esta guía; roles del contenedor (guías de infraestructura) |
| 401 con `WWW-Authenticate: Bearer error="interaction_required"` | Acceso condicional sobre Azure Storage que el token del conector no satisface | Hallazgo 10 de la guía de spikes; `docs/backlog-tecnico.md` §10 |
