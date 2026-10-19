# Prompt para diseñar los controles de carga de adjuntos

Prompt para una herramienta de diseño asistido (por ejemplo Claude Design). Se pega tal cual. El resultado (prototipo HTML, tokens y notas) vuelve a este repo y se porta al PCF `CargaAdjuntoBlob`. Contexto de fondo en `docs/arquitectura.md` y en la sección M7 de `docs/aprendizaje/consultas-y-mejoras.md`.

---

## Contexto del producto

Estoy construyendo un componente de carga de archivos para Microsoft Power Apps que un banco va a usar en sus aplicaciones internas. Los usuarios adjuntan documentos (PDF, imágenes, video, hasta 250 MB cada uno) a un registro de negocio, por ejemplo una solicitud de crédito o un expediente de cliente. El archivo se sube directo a Azure Blob Storage en bloques, con progreso real, y después un proceso de fondo lo archiva en el gestor documental del banco y lo valida. El componente es un control de código (Power Apps Component Framework, TypeScript, con React y Fluent UI disponibles) y tiene que vivir en DOS anfitriones distintos con el mismo lenguaje visual:

1. **App canvas**: el control ocupa un área que el maker decide (típicamente entre 320 y 900 px de ancho, 240 a 600 px de alto). Convive con controles modernos de Power Apps. Solo sube archivos; el estado de cada adjunto lo muestra la app alrededor.
2. **App model-driven (Dynamics 365)**: el control se liga a una subcuadrícula de "Adjuntos" dentro del formulario del registro padre. Reemplaza la grilla estándar: muestra la lista de adjuntos existentes con su estado, y permite subir nuevos. Tiene que verse nativo al lado de los demás controles de Dataverse (Fluent UI 2 / Fluent 9, Segoe UI). Ancho de 600 a 1.400 px; alto típico 400 a 700 px dentro de un formulario con scroll.

Nombre neutro del cliente: "el Banco". No uses nombres, logos ni colores de ningún banco real. Idioma de la interfaz: español neutro, voseo no.

## Lo que necesito que diseñes

### A. Zona de carga (ambos anfitriones)

- Arrastrar y soltar más botón "Seleccionar archivos". Multi-archivo (hasta 20 por lote). Indicar tipos aceptados y tamaño máximo (vienen configurados por el maker, por ejemplo "PDF, JPG, MP4 · hasta 250 MB").
- Validación inmediata antes de subir: tipo no permitido, tamaño excedido, archivo duplicado en el lote, lote lleno. Cada rechazo se muestra en línea, por archivo, con motivo claro y sin bloquear al resto.
- Estado vacío (sin archivos), estado con archivos en cola, estado subiendo, estado terminado, estado con errores mixtos (algunos bien, algunos mal).
- Acciones: quitar un archivo de la cola, cancelar una subida en curso, reintentar un archivo fallido, "Subir todo", "Limpiar terminados".

### B. Fila de archivo (ambos anfitriones)

Cada archivo tiene estos estados de subida, en este orden, y cada uno necesita una representación visual distinta pero de la misma familia:

1. En cola (esperando turno).
2. Pidiendo permiso (obteniendo el pase de subida; dura 1 a 3 segundos; sin porcentaje).
3. Subiendo: barra de progreso real por bloques, porcentaje, MB subidos de MB totales, velocidad actual en Mbps y tiempo restante estimado. Puede haber hasta 2 archivos subiendo en paralelo.
4. Verificando (confirmando el cierre del archivo; sin porcentaje; breve).
5. Subido (terminal, éxito).
6. Error (terminal, con mensaje corto legible por el usuario y acción "Reintentar"). Casos: permiso denegado (el usuario no puede adjuntar a este registro), pase vencido (tardó más de 15 minutos), red cortada, archivo cambió en disco.
7. Cancelado.

Mostrar: ícono por tipo de archivo, nombre (truncado con elipsis y tooltip), tamaño legible, y el estado. Nunca mostrar URLs, tokens ni rutas internas.

### C. Lista de adjuntos existentes (solo model-driven)

Además de la zona de carga, la grilla de adjuntos ya guardados. Cada adjunto tiene un **tipo de documento** (texto, por ejemplo "Cédula de identidad", "Contrato firmado") y uno de estos **diez estados del ciclo de vida**, que el usuario tiene que distinguir de un vistazo con un chip o badge:

| Estado | Significado para el usuario | Acción disponible |
| --- | --- | --- |
| Creada | Registro creado, archivo todavía no subido | Subir |
| Subido | Archivo en el depósito, esperando proceso | Ninguna (informativo) |
| Procesando | El proceso de fondo lo está archivando | Ninguna |
| Archivado | Guardado en el gestor documental | Ver |
| Almacenado | Guardado en el depósito, no va (o no va todavía) al gestor documental | Ver · Archivar ahora |
| Observada | Falta un dato o hay un dato inválido; el usuario tiene que corregir | Corregir y reanudar |
| Detenida | Esperando a un administrador (problema de configuración, no del usuario) | Ninguna (informativo, con explicación) |
| Error | Falló por una causa técnica | Reintentar |
| Rechazado | Rechazado de forma definitiva (por ejemplo, malware o vencido sin corregir) | Ninguna (informativo) |
| Depurado | El archivo ya fue eliminado del depósito por retención | Ninguna (informativo) |

Observada y Detenida son los dos que más importan: Observada le pide algo al usuario y tiene que llamar la atención sin ser agresivo; Detenida le explica que no depende de él. Cada fila muestra además: nombre, tipo de documento, tamaño, fecha de subida, quién lo subió, y un mensaje de detalle cuando el estado lo tiene (Observada, Detenida, Error, Rechazado). Diseñar: la grilla con 0, 3 y 25 adjuntos; ordenamiento por fecha y por estado; filtro rápido por estado; densidad compacta y cómoda.

### D. Tres variantes de diseño conmutables

Quiero comparar tres direcciones visuales en el mismo prototipo, con un conmutador visible arriba para alternar entre ellas sin recargar:

1. **Fluent nativo**: lo más parecido posible a los controles estándar de Power Apps y Dynamics 365 (Fluent 2). Para el maker que quiere que no se note que es un control a medida.
2. **Institucional sobrio**: estética de banca: contraste alto, tipografía serena, bordes finos, poco color salvo para estado. Para formularios densos.
3. **Moderno con tarjetas**: cada archivo como tarjeta, más aire, progreso más visual. Para apps canvas orientadas a usuarios ocasionales.

Las tres comparten la misma anatomía, los mismos estados y los mismos textos. Solo cambian forma, espaciado, tipografía y color.

### E. Personalización por tokens

Todo color, tipografía, radio, espaciado y sombra tiene que salir de **tokens de diseño** expuestos como variables CSS, porque el maker los va a configurar como propiedades del control sin tocar código. Mínimo:

- Colores: primario, primario hover, texto sobre primario, fondo, superficie, borde, texto principal, texto secundario, y un color por semántica de estado: éxito, advertencia (Observada), información (Detenida, Procesando), error, neutro (Depurado, Rechazado).
- Tipografía: familia (por defecto Segoe UI, con fallback de sistema), tamaño base, escala.
- Forma: radio de bordes, grosor de borde, densidad (compacta / cómoda).
- Modo claro y modo oscuro, con contraste AA en ambos.

Incluir en el prototipo un panel de personalización en vivo: cambiar color primario, familia tipográfica, radio, densidad y modo claro/oscuro, y ver el efecto en las tres variantes.

## Restricciones

- Sin imágenes externas ni fuentes web obligatorias: íconos de Fluent UI System Icons (SVG en línea) y tipografía del sistema.
- Accesibilidad: navegación por teclado completa, foco visible, roles ARIA en la zona de arrastre, la lista y las barras de progreso, anuncios de cambio de estado para lectores de pantalla, contraste AA, sin depender solo del color para distinguir estados (ícono o texto además del color).
- Responsive: en 320 px la fila de archivo pasa a dos líneas y la grilla a tarjetas; nada se corta.
- Textos cortos y concretos; los mensajes de error dicen qué pasó y qué hacer.
- Nada de datos sensibles en pantalla: ni URLs firmadas, ni tokens, ni rutas internas.

## Entregables que necesito

1. Un prototipo HTML autocontenido (un solo archivo, CSS y JS en línea) con: conmutador de variante, panel de personalización, modo claro/oscuro, y datos de ejemplo que muestren TODOS los estados de subida y los diez estados de ciclo de vida, en los dos anfitriones (canvas y model-driven) como dos vistas.
2. Un archivo `tokens.json` con todos los tokens de diseño, sus valores por variante y por modo, y una descripción de una línea por token.
3. Una nota de anatomía: lista de componentes (zona de carga, fila de archivo, chip de estado, barra de progreso, grilla, toolbar, panel de detalle), sus partes y sus estados, con medidas (alturas de fila, espaciados, tamaños de ícono y de tipografía) para que un desarrollador las porte a TypeScript sin adivinar.
4. Una tabla de textos de interfaz en español (etiquetas, mensajes de estado, errores, tooltips) con su clave, para pasarla a un archivo de recursos.

Antes de diseñar, hacé las preguntas que necesites sobre lo que no esté claro. Después, mostrame primero la anatomía y los tokens, y recién después el prototipo.
