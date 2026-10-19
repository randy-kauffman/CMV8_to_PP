# Control de carga de archivos — Anatomía

Versión 0.1 · Medidas en px. Formato `compacta / cómoda`. Si una medida cambia por variante: **F** Fluent nativo · **I** Institucional sobrio · **M** Moderno con tarjetas. Los nombres `--fu-*` son tokens de `tokens.json`. Textos: claves de `textos.es.json`.

## Reglas globales

- Tipografía: `--fu-font-family` (Segoe UI + fallback de sistema). Números con `font-variant-numeric: tabular-nums`.
- Íconos: Fluent UI System Icons, en línea, `fill: currentColor`. Tamaño 16 o 20 (regular); 12–14 dentro de chips (filled).
- Foco: `outline: var(--fu-focus-width) solid var(--fu-color-focus); outline-offset: var(--fu-focus-offset)`, solo con `:focus-visible`.
- Estados nunca solo por color: siempre ícono + texto.
- Rejilla de 4 px. Escala de espacio `--fu-space-1…8` = 2, 4, 8, 12, 16, 20, 24, 32.
- Puntos de quiebre del contenedor (no de la ventana; usar `ResizeObserver`): `< 480` estrecho, `480–899` medio, `≥ 900` ancho.

---

## 1. Zona de carga (`Dropzone`)

**Partes**
1. Contenedor — `role="region"` con `aria-label` (`dropzone.aria`); clic en cualquier punto abre el selector. El botón (4) es la única parada de tabulación (evita controles interactivos anidados). Borde punteado `--fu-border-width-dropzone` `--fu-color-border-strong`, radio `--fu-radius-lg`, fondo `--fu-color-surface-alt`.
2. Ícono — `ArrowUpload` 24 (M: 32, en círculo 48 `--fu-color-primary-subtle`).
3. Título — `dropzone.title`, `--fu-font-size-subtitle`, semibold.
4. Botón — `dropzone.browse`, secundario (F, I) / primario (M), alto `--fu-size-control`.
5. Pista — `dropzone.hint`, caption, `--fu-color-text-secondary`.
6. `<input type="file" multiple accept>` oculto.

**Medidas**
| | F | I | M |
|---|---|---|---|
| Alto mínimo | 96 / 136 | 88 / 120 | 120 / 168 |
| Relleno | 16 / 24 | 12 / 16 | 20 / 32 |
| Separación ícono–título–pista | 8 | 4 | 12 |
| Disposición | centrada vertical | horizontal: ícono · texto · botón a la derecha (≥ 480) | centrada vertical |

**Estados**: reposo · hover (fondo `surface-hover`) · foco · arrastrando encima (borde sólido 2 `--fu-color-primary`, fondo `primary-subtle`, título → `dropzone.dragActive`) · lote lleno (`dropzone.full`, botón deshabilitado) · deshabilitada (`dropzone.disabled`, `aria-disabled`).
**Compacta con archivos en cola**: la zona se reduce a una línea (alto `--fu-size-control` + 16) con ícono, `dropzone.compact` y `dropzone.compactCount`.
**< 480**: siempre vertical; botón ancho completo; pista en 2 líneas como máximo.

## 2. Barra de cola (`QueueToolbar`)

Partes: resumen (`queue.summary.*`, body semibold, `aria-live="polite"`) · espaciador · `action.clearDone` (sutil, solo si hay terminados) · `action.retryFailed` (sutil, solo si hay errores) · `action.uploadAll` (primario, deshabilitado si no hay en cola o falta tipo de documento).
Alto `--fu-size-control` + 8 · separación entre botones 8 · < 480: resumen arriba, botones en fila debajo, `Subir todo` ancho completo.

## 3. Fila de archivo (`FileRow`)

`role="listitem"` dentro de `role="list"` (`queue.aria`). Grid: `[ícono] [contenido 1fr] [acciones auto]`.

**Partes**
1. Ícono de tipo — `DocumentPdf`, `Image`, `Video`, `Document` (genérico). `--fu-size-icon-file`. Color por tipo: neutro (`text-secondary`) en F/I; en M ícono sobre cuadro 40 radio `md` `surface-alt`.
2. Nombre — body semibold, una línea, `text-overflow: ellipsis`, `title` = nombre completo. El sufijo de extensión no se corta (nombre base truncado + `.pdf`).
3. Tamaño — caption, `text-secondary`, tabular.
4. Tipo de documento (solo model-driven) — desplegable 200 (compacta 160), alto `--fu-size-control`, obligatorio en estado *En cola*. Después de subir, se muestra como texto.
5. Estado — ícono 16 + etiqueta caption con color de semántica.
6. Detalle de estado — caption: progreso, mensaje de error o motivo de rechazo.
7. Barra de progreso — ver §5. Ocupa el ancho de la columna de contenido.
8. Acciones — botones de ícono `--fu-size-control` cuadrados, glifo `--fu-size-icon-action`, con `aria-label` + tooltip.

**Medidas**
| | F | I | M |
|---|---|---|---|
| Alto mínimo | 44 / 56 | 40 / 48 | 56 / 72 |
| Relleno x / y | 8·6 / 12·8 | 8·4 / 12·6 | 12·10 / 16·14 |
| Separación de columnas | 12 | 8 | 12 |
| Separación entre filas | divisor 1 `border` | divisor 1 `border` | 8 / 12, tarjeta con borde + `shadow.card`, radio `lg` |
| Línea 1 | nombre · tamaño | nombre · tamaño | nombre |
| Línea 2 | estado · detalle | estado · detalle | tamaño · estado · detalle |

**Estados de subida** (misma familia: ícono de estado + etiqueta + detalle + barra):
| # | Estado | Semántica | Ícono | Barra | Detalle | Acciones |
|---|---|---|---|---|---|---|
| 1 | En cola | neutro | `Clock` | pista vacía | tamaño | Quitar |
| 2 | Solicitando permiso | info | `ArrowSync` girando | indeterminada | — | Cancelar |
| 3 | Subiendo | primario | `ArrowUpload` | determinada + % | `upload.uploading.detail` | Cancelar |
| 4 | Verificando | info | `ShieldCheckmark` | indeterminada al 100 % | — | — |
| 5 | Subido | éxito | `CheckmarkCircle` (filled) | oculta | — | Quitar |
| 6 | Error | error | `ErrorCircle` (filled) | oculta | mensaje `error.*` | Reintentar · Quitar |
| 7 | Cancelado | neutro | `DismissCircle` | oculta | — | Reintentar · Quitar |
| — | No se agregó (validación) | error, fila con fondo `status-danger-bg` | `Warning` | oculta | `validation.*` | Quitar |

Porcentaje: alineado a la derecha de la línea 2, tabular, ancho fijo 40. Velocidad y ETA ocultas < 480 (se muestran solo % y MB).
**< 480**: línea 1 = ícono + nombre + acción; línea 2 = estado + detalle; barra debajo; tipo de documento en línea 3 a ancho completo.
**Paralelismo**: máx. 2 en *Subiendo*; el resto queda *En cola*.

## 4. Chip de estado (`StatusChip`)

`<span>` con ícono `aria-hidden` + texto; `title` = `lifecycle.*.desc`. No es interactivo.
Alto `--fu-size-chip` (20 / 20 / 24) · relleno x 6 (M: 8) · separación ícono–texto 4 · caption semibold · radio `--fu-radius-chip`.
Tres estilos: **tintado** (fondo `bg`, texto `fg`, borde `border`), **contorno** (fondo `surface`, borde `border`), **punteado** (contorno con `border-style: dashed`).

| Estado | Semántica | Estilo | Ícono | Acción |
|---|---|---|---|---|
| Creada | neutro | punteado | `DocumentAdd` | Subir |
| Subido | info | contorno | `CloudArrowUp` | — |
| Procesando | info | tintado | `ArrowSync` | — |
| Archivado | éxito | tintado | `ArchiveCheckmark`* | Ver |
| Almacenado | éxito | contorno | `CloudCheckmark` | Ver · Archivar ahora |
| **Observada** | advertencia | tintado + borde 1 `fg` | `Edit` + punto | Corregir y reanudar |
| **Detenida** | info | tintado | `PersonClock` | — (explicación) |
| Error | error | tintado | `ErrorCircle` | Reintentar |
| Rechazado | neutro | tintado | `Prohibited` | — |
| Depurado | neutro | contorno | `Delete` | — |

\* Si no existe en la versión del set, usar `Archive`.
**Observada** es el único chip con borde en `fg` y la fila lleva una franja de 2 px a la izquierda del contenido en `status-warning-fg` (no una tarjeta con borde lateral; es un indicador dentro de la celda de nombre). **Detenida** muestra el mensaje con ícono `Info` y el texto "No necesitas hacer nada".

## 5. Barra de progreso (`ProgressBar`)

`role="progressbar"`, `aria-valuemin=0`, `aria-valuemax=100`, `aria-valuenow` (omitido si indeterminada), `aria-valuetext="{sent} de {total}"`, `aria-labelledby` → nombre del archivo.
Grosor `--fu-size-progress` (4 / 3 / 6) · radio `--fu-radius-sm` (I: 0) · pista `--fu-color-border` · relleno `--fu-color-primary` · éxito momentáneo `status-success-fg` (300 ms antes de ocultarse).
- **Determinada**: ancho = bytes confirmados / total. Avanza por bloque (8 MB); transición `width` `--fu-motion-normal` lineal.
- **Indeterminada**: segmento 30 % que recorre la pista en 1.5 s; con `prefers-reduced-motion` → pulso de opacidad.
- **M**: además muestra marcas de bloque (separadores de 1 px en `surface` cada bloque) cuando el archivo tiene ≤ 32 bloques.

## 6. Barra de herramientas de la grilla (`GridToolbar`, model-driven)

Alto `--fu-size-toolbar` (F 40/48 · I 36/44 · M 44/52). Partes: título `grid.title` (`--fu-font-family-heading`, subtitle semibold) + contador · filtro rápido (grupo de botones conmutables `role="radiogroup"`, con número por grupo) · espaciador · `Ordenar` (menú) · `Densidad` (menú, ícono `TextDensity`) .
Filtros: Todos · Requieren acción · En proceso · Guardados · Cerrados. **< 600**: filtros en un desplegable.

## 7. Grilla de adjuntos (`AttachmentGrid`, model-driven)

`role="grid"` con `aria-rowcount`; filas `role="row"`; navegación por flechas entre celdas (roving tabindex); Enter abre el panel.
**Columnas** (≥ 900): Nombre (ícono 20 + nombre, `minmax(200px, 2fr)`) · Tipo de documento (`minmax(140px, 1fr)`) · Estado (148) · Tamaño (88, derecha) · Fecha de subida (112) · Subido por (`minmax(120px, 1fr)`) · Acciones (168: acción principal como botón sutil + menú `…` 32).
**600–899**: se ocultan "Subido por" y "Tamaño" (pasan a la segunda línea del nombre).
**< 600**: tarjetas: línea 1 ícono + nombre + menú; línea 2 chip + tipo de documento; línea 3 tamaño · fecha · usuario; mensaje de detalle; acción principal a ancho completo.
**Medidas**: fila `--fu-size-grid-row` (F 36/44 · I 32/40 · M 44/56); con mensaje de detalle la fila crece y el mensaje (caption, color `fg` de la semántica, ícono 12) va bajo el nombre ocupando columnas Nombre→Subido por. Encabezado `--fu-size-grid-header`, fondo `surface-alt`, caption semibold (I: mayúsculas, tracking 0.04em). Celda relleno x `--fu-space-row-x`. Divisor 1 `border`. M: filas como tarjetas separadas por 4.
**Orden**: fecha desc (defecto), fecha asc, estado (Observada, Error, Creada, Detenida, Procesando, Subido, Almacenado, Archivado, Rechazado, Depurado). Indicador en encabezado `ArrowSortDown/Up` 12, `aria-sort`.
**Vacíos**: 0 adjuntos → `grid.empty.*` centrado, alto 160; filtro sin resultados → `grid.filterEmpty` + `grid.clearFilter`.
**Muchos**: alto máximo lo da el formulario; encabezado `position: sticky`. Sin paginación hasta 100; luego "Cargar más".

## 8. Panel de detalle (`DetailPanel`, model-driven)

A la derecha de la grilla, ancho `--fu-size-panel` (360 / 360 / 400, min 320, max 480). `role="complementary"` + `aria-labelledby`. Con < 900 de contenedor se superpone con `color.scrim` y `shadow.panel`, foco atrapado, Esc cierra.
**Partes**: encabezado (ícono 24 + nombre subtitle semibold 2 líneas máx + botón cerrar 32) · chip + `lifecycle.*.desc` · mensaje de detalle (caja con fondo `status-*-bg`, borde 1 `status-*-border`, radio `md`, relleno 12) · Datos (lista de pares etiqueta caption / valor body, separación 12) · Corregir datos (solo Observada: campos con el inválido marcado, `aria-invalid`, mensaje bajo el campo) · Historial (lista vertical de estados con fecha, punto 8 + línea 1) · pie fijo con acciones (alto 56, relleno 16, primario a la derecha).
Relleno 16 (M: 20). Secciones separadas por 20 con título caption semibold `text-secondary`.

## 9. Anuncios para lectores de pantalla

Una región `aria-live="polite"` por control (visualmente oculta) y otra `assertive` solo para errores. Se anuncia: archivos agregados/rechazados, cada cambio de estado, progreso cada 25 %, fin de lote. Nunca más de un anuncio por segundo por archivo.

## 10. Teclado

Tab: zona de carga → barra de cola → filas (cada acción tabulable) → toolbar de grilla → grilla (una parada; flechas dentro) → panel. Enter/Espacio en zona abre el selector. Supr en una fila de cola = Quitar. Esc cancela arrastre o cierra el panel.
