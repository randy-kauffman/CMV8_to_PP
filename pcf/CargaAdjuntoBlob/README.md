# CargaAdjuntoBlob v2 (1.0.0)

Control PCF (namespace `Banco`) que arma una cola de archivos y los sube directo del navegador a Azure Blob Storage, por bloques y con las dos llaves de ADR-002: la SAS de un blob en la URL y el `tokenStorage` del usuario en `Authorization: Bearer` de cada PUT. Implementa el flujo de subida de [ADR-003 §4.1](../../docs/decisiones/ADR-003-v2-modelo-de-datos.md) en **modo canvas: "solo sube; el estado lo muestra la app"**. El control no llama al broker: la app llama a `'CMV8 Broker'.EmitirSasLote` por el conector y le pasa la respuesta.

Diseño: [Anatomia.md](../../docs/diseno/anatomia-y-tokens/Anatomia.md), [tokens.json](../../docs/diseno/anatomia-y-tokens/tokens.json), [textos.es.json](../../docs/diseno/anatomia-y-tokens/textos.es.json). Seguridad de la app: [checklist-construccion-app.md](../../docs/checklist-construccion-app.md).

## Protocolo con la app

```
Usuario            Control (PCF)                          App (Power Fx)                     Broker / Storage
  │ arrastra o elige  │                                      │                                    │
  ├──────────────────►│ valida (tipo, tamaño, vacío,          │                                    │
  │                   │ duplicado, lote lleno) → En cola      │                                    │
  │ "Subir todo"      │                                      │                                    │
  ├──────────────────►│ estado = listo-para-sas               │                                    │
  │                   │ pendientes = [{indice,id,...}]        │                                    │
  │                   │ numeroPedido = N ───── OnChange ─────►│ crea filas (id vacío), en orden     │
  │                   │                                      ├── EmitirSasLote(tabla, items) ────►│
  │                   │                                      │◄── {items, tokenStorage, ...} ─────┤
  │                   │◄──────────── lote = varLote ──────────┤ Set(varLote, JSON(respuesta))      │
  │                   │ empareja, descarta el lote, sube ──────────── Put Block / Put Block List ─►│
  │                   │ resultados = [{id, estado, ...}] ─────►│ Patch estadoingesta = Subido       │
  │                   │ estado = completado | con-errores ───►│ Set(varLote, Blank())               │
```

1. **Agregar.** El usuario arrastra o selecciona varios archivos. Cada uno se valida al instante, en este orden, con las claves `validation.*`: lote lleno (`loteMaximo` archivos vigentes), tipo (`tiposAceptados`, sintaxis de `accept`), tamaño (`tamanoMaximoMB`), vacío y duplicado (mismo nombre y tamaño que otro de la cola). Los rechazados quedan en la lista como **No se agregó** con el motivo. Estado del control: `encola`.
2. **Pedir permiso.** "Subir todo" (o "Reintentar" / "Reintentar fallidos") pasa los archivos a **Solicitando permiso** y publica un pedido: `pendientes` con `[{indice, id, nombreArchivo, tamanoBytes, contentType}]`, `numeroPedido` nuevo y `estado = "listo-para-sas"`. `id` viene vacío si el archivo todavía no tiene fila; trae el GUID cuando es un reintento (la fila ya existe y el broker acepta filas en Creada o Error: "un archivo fallido se reintenta con un lote de uno").
3. **La app atiende el pedido una vez** (guarda `numeroPedido` en `varPedidoAtendido`): crea las filas que faltan (`nombrearchivo`, `tamanobytes`, `contenttype`, `tipodocumento`), llama `EmitirSasLote` con los items **en el mismo orden de `pendientes`** y deja `JSON(respuesta)` en **una** variable, `varLote`, que entra por `lote`. Si la llamada falla, deja `{"error": "...", "codigo": 403}` en `varLote`: el control necesita una respuesta por pedido para no quedar esperando.
4. **Subir.** El control empareja cada item del lote con su archivo, descarta el texto del lote en el acto (no lo guarda en ningún campo; solo recuerda una huella no reversible para no consumirlo dos veces) y sube hasta `paralelismoArchivos` archivos a la vez, cada uno en bloques de `bloqueBytes` con `paralelismoBloques` bloques en vuelo y el mismo `tokenStorage` para todos.
5. **Resultados.** Cada archivo que termina (Subido, Error o Cancelado) aparece en `resultados` con su `id`. La app marca **Subido** con `Patch` (requests del usuario, ADR-003 §4.1). Un item de lote con error (403, 409, 400...) pasa a Error con el mensaje del broker; 403 no ofrece Reintentar.
6. **Limpiar.** Cuando `estado` deja de ser `listo-para-sas`, la app hace `Set(varLote, Blank())`. Lo mismo en `OnHidden` de la pantalla. `disparadorReinicio` vacía todo el control.

### Emparejamiento del lote

El broker v2 **no devuelve** `nombreArchivo`, `tamanoBytes` ni ningún campo extra del pedido: cada item de la respuesta trae solo `{id, uploadUrl, rutaBlob, expiraUtc}` o `{id, error, codigo}` (`SasHttp.lote`, `EmitirSasLote.ItemResultado`). Por eso el control empareja así, de la regla más explícita a la más implícita (`lote.ts`, `emparejar`):

1. `indice` en el item, si la app lo agregó a la respuesta.
2. `nombreArchivo` + `tamanoBytes` en el item, si la app los agregó. Es una clave única porque la cola rechaza duplicados: **dos archivos con el mismo nombre y tamaño en el mismo lote se rechazan como duplicado** al agregarlos.
3. `id`, para archivos que ya tenían fila (reintentos).
4. **Posición** (el caso normal con la respuesta tal cual): `EmitirSasLote.ejecutar` responde un item por cada item del pedido y en el mismo orden, incluso los que fallan, y `ForAll` devuelve su tabla en el orden de la tabla de origen. Solo se usa si el lote trae exactamente tantos items como el pedido y el `id` no contradice uno ya conocido.

Lo que no se puede asignar con seguridad no se adivina: ese archivo pasa a Error y se reintenta. Una SAS en el archivo equivocado subiría bytes a la fila de otro documento.

Invariante que sostiene el emparejamiento por posición: **hay un solo pedido en vuelo y nunca se abandona** salvo con `disparadorReinicio`. Cancelar un archivo que está pidiendo permiso no abandona el pedido (su SAS se descarta cuando llega, pero se aprende el `id`). Los archivos que piden permiso mientras hay un pedido en vuelo salen en el siguiente. Como refuerzo opcional, la app puede agregar `numeroPedido` en la raíz del JSON y el control descarta un lote que no corresponda al pedido en curso.

## Power Fx de referencia

Nombres de ejemplo: tabla `cred_adjuntocredito` ('Adjuntos de crédito'), desplegable `ddTipoDocumento` con el tipo del lote, control `CargaAdjuntoBlob1`. Ajustar a los nombres reales de la solución.

Propiedades del control:

```powerfx
lote:               varLote
tabla:              "cred_adjuntocredito"
disparadorReinicio: varReinicio
```

`OnChange` del control:

```powerfx
// 1) Pedido nuevo: crear las filas que falten y pedir el lote. Una sola vez por numeroPedido.
If(
    Self.estado = "listo-para-sas" && Self.numeroPedido <> varPedidoAtendido,
    Set(varPedidoAtendido, Self.numeroPedido);
    // colPedido no tiene secretos (ids, nombres y tamaños): puede ser una colección.
    ClearCollect(colPedido,
        ForAll(Table(ParseJSON(Self.pendientes)) As p,
            {
                id: If(IsBlank(Text(p.Value.id)),
                    Patch('Adjuntos de crédito', Defaults('Adjuntos de crédito'), {
                        'Nombre de archivo': Text(p.Value.nombreArchivo),
                        'Tamaño en bytes':   Value(p.Value.tamanoBytes),
                        'Content type':      Text(p.Value.contentType),
                        'Tipo de documento': ddTipoDocumento.Selected,
                        'Estado de ingesta': 'Estado de ingesta'.Creada
                    }).'Adjunto de crédito',
                    GUID(Text(p.Value.id))),
                nombreArchivo: Text(p.Value.nombreArchivo),
                tamanoBytes:   Value(p.Value.tamanoBytes),
                contentType:   Text(p.Value.contentType)
            }));
    // La respuesta trae la SAS y el token: va a UNA variable, nunca a una colección ni a Dataverse.
    Set(varLote,
        IfError(
            JSON('CMV8 Broker'.EmitirSasLote({
                tabla: "cred_adjuntocredito",
                items: ForAll(colPedido, { id: id, nombreArchivo: nombreArchivo,
                                           tamanoBytes: tamanoBytes, contentType: contentType })
            })),
            JSON({ error: FirstError.Message, codigo: 0 })))
);

// 2) Marcar Subido lo que terminó bien (una vez por id).
ForAll(
    Filter(Table(ParseJSON(Self.resultados)) As r,
        Text(r.Value.estado) = "subido" && !(Text(r.Value.id) in colAtendidos.id)) As r,
    Patch('Adjuntos de crédito', LookUp('Adjuntos de crédito', 'Adjunto de crédito' = GUID(Text(r.Value.id))),
        { 'Estado de ingesta': 'Estado de ingesta'.Subido });
    Collect(colAtendidos, { id: Text(r.Value.id) })
);

// 3) Nada esperando SAS: la variable con las llaves se vacía.
If(Self.estado <> "listo-para-sas" && !IsBlank(varLote), Set(varLote, Blank()))
```

`OnHidden` de la pantalla: `Set(varLote, Blank())`.

Reiniciar el control (por ejemplo, después de guardar el registro padre): `Set(varReinicio, varReinicio + 1); Clear(colAtendidos)`. No cambiar `disparadorReinicio` mientras la app espera `EmitirSasLote`.

Notas:

- Las filas de archivos cancelados o quitados después de crear su fila quedan en **Creada**; la app decide si las borra (`resultados` trae los cancelados con su `id`).
- `resultados.mensajeError` trae el texto del broker cuando el error vino del lote (por ejemplo "El adjunto no tiene tipo de documento") y, si no, el mensaje `error.*` que ve el usuario. Nunca trae URLs ni tokens.
- Ningún control de la app muestra `varLote` (checklist). Para diagnosticar: `resultados`, `estado`, `resumen`.

## Propiedades

### Entradas

| Propiedad | Tipo | Defecto | Uso |
|---|---|---|---|
| `lote` | Multiple.Lines | | `JSON()` de la respuesta de `EmitirSasLote` (`{items, bloqueBytes, versionServicio, tokenStorage, tokenStorageExpiraUtc}`). Secreto |
| `tabla` | SingleLine.Text | | Nombre lógico de la tabla de adjuntos. En canvas solo se valida; lo usará el modo model-driven |
| `tiposAceptados` | SingleLine.Text | `video/*,application/pdf` | Sintaxis de `accept`. Vacío = todo |
| `tamanoMaximoMB` | Whole.None | 250 | Por archivo |
| `loteMaximo` | Whole.None | 20 | Archivos vigentes en la cola y por pedido (el broker también limita a 20) |
| `paralelismoArchivos` | Whole.None | 2 | 1 a 4 |
| `paralelismoBloques` | Whole.None | 4 | 1 a 8 |
| `disparadorReinicio` | Whole.None | | Al cambiar, cancela y vacía |
| `densidad` | Enum | `comoda` | `compacta` / `comoda` |
| `variante` | Enum | `fluent` | `fluent` / `institucional` / `moderno` |
| `modo` | Enum | `claro` | `claro` / `oscuro` / `auto` (preferencia del sistema operativo) |
| Tokens del maker | | vacío | Una propiedad por token con `"maker"` en tokens.json. Vacío = valor de la variante |

Tokens del maker: `PrimaryColor`, `PrimaryHoverColor`, `OnPrimaryColor`, `BackgroundColor`, `SurfaceColor`, `BorderColor`, `TextColor`, `TextSecondaryColor`, `SuccessColor`, `WarningColor`, `InfoColor`, `DangerColor`, `NeutralColor`, `FontFamily`, `FontScale` (texto, por ejemplo `"#1F4E79"`, `"Arial, sans-serif"`, `"1.2"`) y `FontSize`, `BorderRadius`, `BorderWidth` (enteros en px). Un color que el navegador no entiende se ignora. Derivados (`tokens.ts`): con `PrimaryColor` se recalculan hover, pressed, texto sobre primario (blanco o negro según contraste 4.5:1), texto primario y primario sutil; con un color de semántica se derivan su fondo (8 %) y borde (35 %) sobre la superficie; con `FontSize`/`FontScale`, caption = base/escala, subtítulo = base×escala, título = base×escala²; con `BorderRadius`, sm = r/2 y lg = 2r (institucional: r).

### Salidas

| Propiedad | Tipo | Contenido |
|---|---|---|
| `pendientes` | Multiple.Lines | `[{indice, id, nombreArchivo, tamanoBytes, contentType}]` del pedido en vuelo, `[]` si no hay |
| `numeroPedido` | Whole.None | Número del último pedido publicado; crece siempre (no vuelve a 0 al reiniciar) |
| `resultados` | Multiple.Lines | `[{id, estado, rutaBlob, nombreArchivo, tamanoBytes, contentType, mensajeError, duracionMs, velocidadMbps}]` de los archivos con fila que terminaron; `estado` es `subido`, `error` o `cancelado`; `rutaBlob` (sin firmar) solo en `subido` |
| `resumen` | SingleLine.Text | El mismo texto de la barra de cola (`queue.summary.*`) |
| `progreso` | Whole.None | 0 a 100, por bytes confirmados de lo que entró al circuito |
| `estado` | SingleLine.Text | `vacio`, `encola`, `listo-para-sas`, `subiendo`, `completado`, `con-errores`. Prioridad: `listo-para-sas` > `subiendo` > `encola` > `con-errores` > `completado` > `vacio` (un pedido en vuelo manda sobre una subida en curso, para que la app vea los reintentos) |
| `cantidadEnCola` | Whole.None | En cola + Solicitando permiso + esperando turno |
| `cantidadSubidos` | Whole.None | Subidos |
| `cantidadConError` | Whole.None | Con error |

El progreso se publica como mucho dos veces por segundo (cada publicación dispara `OnChange`); los cambios de estado, al instante.

## Estados por archivo

| Estado (interno) | Se muestra | Acciones |
|---|---|---|
| `enCola` | En cola, pista vacía | Quitar |
| `solicitando` | Solicitando permiso, barra indeterminada | Cancelar |
| `esperando` | En cola (ya tiene SAS, espera turno por `paralelismoArchivos`) | Cancelar |
| `subiendo` | Subiendo, % · MB · velocidad · tiempo restante (< 480 px: solo % y MB) | Cancelar |
| `verificando` | Verificando (Put Block List en curso) | — |
| `subido` | Subido; la barra queda verde 300 ms y se oculta | Quitar |
| `error` | Error con mensaje `error.*` | Reintentar · Quitar (403: solo Quitar) |
| `cancelado` | Cancelado | Reintentar · Quitar |
| `rechazado` | No se agregó, con motivo `validation.*` | Quitar |

Errores de subida: Storage rechaza las llaves (401/403) con la SAS o el token vencidos → `error.passExpired`; sin vencer → `error.generic` (reintentar pide SAS nueva). Error de red con el archivo ilegible → `error.fileChanged`; si se puede leer → `error.network`. Una SAS que llega ya vencida no se usa.

## Estructura

```
CargaAdjuntoBlob/
  ControlManifest.Input.xml   generado
  index.ts                    ciclo de vida PCF: entradas, salidas, notificaciones
  cola.ts                     modelo, máquina de estados, validación, conteos (puro)
  lote.ts                     parseo y emparejamiento del lote (puro)
  fuenteSas.ts                puerto FuenteSas + FuenteSasApp (canvas)
  motor.ts                    cola de subida, paralelismo, permisos en memoria, clasificación de errores
  subidaBloques.ts            Put Block / Put Block List con las dos llaves (sin cambios de fondo)
  formato.ts                  tamaños, Mbps, %, ETA, huella (puro)
  textos.ts                   context.resources.getString + {placeholders}
  textos.generado.ts          tipo ClaveTexto (generado)
  tokens.ts                   tokens → variables --fu-*, derivados del maker (puro)
  tokens.generado.ts          los 85 tokens de tokens.json (generado)
  vista/                      dom, íconos (generado), Dropzone, QueueToolbar, FileRow, ProgressBar, anuncios
  css/CargaAdjuntoBlob.css    estructura por variante, densidad y ancho
  strings/*.resx              3082 español; 1033 con los mismos textos marcados TRADUCIR (generados)
  herramientas/generar_desde_diseno.py
```

Para cambiar propiedades, textos, tokens o íconos se edita la fuente (`docs/diseno/...` o la tabla `PROPIEDADES` del generador) y se corre `python3 pcf/CargaAdjuntoBlob/herramientas/generar_desde_diseno.py` desde la raíz del repo. Regenera el manifiesto, `generated/ManifestTypes.d.ts`, los `.resx`, `tokens.generado.ts`, `textos.generado.ts` y `vista/iconos.generado.ts`.

## Modo model-driven (próxima tarea)

ADR-003 §4.6: el mismo control ligado a la subcuadrícula de adjuntos obtiene su token con MSAL y llama al broker directo. Queda preparado así: el motor solo conoce el puerto `FuenteSas` (`solicitar(pedido, entregar)`, `abandonar()`). Una `FuenteSasBroker` que haga `POST {broker}/api/sas/lote` con el pedido y entregue `respuestaDesdeLote(pedido, parsearLote(cuerpo))` se enchufa sin tocar cola, motor ni vista. Falta en esa tarea: crear y actualizar filas con la Web API, el desplegable de tipo de documento por fila (`queue.summary.needsDocType`, `file.docType.*`), la grilla, el panel y el dominio del broker en `external-service-usage`.

## Accesibilidad y teclado

- Zona de carga: `role="region"` con `aria-label`; la única parada de tabulación es el botón (o el enlace en la versión compacta). Esc cancela el estado visual de arrastre.
- Lista `role="list"`, filas `role="listitem"`; cada acción es un botón con `aria-label` "Acción: nombre" y `title`. Supr en una fila = Quitar. Si la fila enfocada desaparece, el foco pasa a la siguiente, a la anterior o a la zona.
- Barra de progreso `role="progressbar"` con `aria-valuetext` "X de Y" y `aria-labelledby` al nombre; sin `aria-valuenow` si es indeterminada.
- Una región `aria-live="polite"` y otra `assertive` (solo errores): archivos agregados o rechazados, cada cambio de estado, progreso cada 25 %, fin de lote; como mucho un anuncio por segundo por archivo.
- Estados siempre con ícono y texto; `prefers-reduced-motion` quita el giro y cambia la barra indeterminada por un pulso.

## Probar con el test harness

```bash
cd pcf
npm start watch
```

En el harness, `lote` se pega a mano (una respuesta real de `EmitirSasLote`, que vence en 15 minutos). El origen `http://localhost:8181` debe estar en el CORS de la cuenta de Storage con `PUT`, `OPTIONS` y los headers `x-ms-version`, `x-ms-blob-content-type`, `Content-Type` y `Authorization`.
