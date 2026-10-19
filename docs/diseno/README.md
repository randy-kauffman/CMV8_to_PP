# Diseño visual de los controles de carga

Resultado de pasar `prompt-diseno-controles.md` a una herramienta de diseño asistido. El cliente es "el Banco"; no hay marca real.

| Entrega | Fecha | Contenido | Estado |
| --- | --- | --- | --- |
| Fase 1: anatomía y tokens | 2026-10-19 | `anatomia-y-tokens/`: `Anatomia.md` (componentes, partes, estados, medidas por variante y densidad, teclado, lectores de pantalla), `tokens.json` (85 tokens, prefijo `--fu-`, tres variantes × claro/oscuro, dos densidades, 18 expuestos al maker como propiedades del PCF, 6 derivados), `textos.es.json` (134 textos con clave y notas), y el visor `anatomia-y-tokens.html` + `support.js` (carga React y Babel desde unpkg: necesita internet). | Recibida y revisada |
| Fase 2: prototipo interactivo | 2026-10-19 | `prototipo/prototipo-autocontenido.html`: un solo archivo, sin red (React embebido), con vistas Canvas (360 y 800 px), Model-driven (1200 y 760 px, grilla con 0, 3 y 25 adjuntos, panel de detalle), 320 px (ambos anfitriones) y Comparar variantes; conmutador de las tres variantes, claro/oscuro, panel de personalización en vivo (color primario, tipografía, radio, densidad) y simulación de subidas. `prototipo.html` + `proto-data.js` + `proto-lib.js` + `support.js` es la versión editable del visor (carga React desde unpkg). Actualizó `Anatomia.md` (zona de carga como `role="region"` con el botón como única parada de tabulación) y sumó dos textos (`dropzone.compact`, `dropzone.compactCount`). | Recibida; verificada sin red con Chromium headless: capturas en `capturas/` |

Las capturas de `capturas/` salen del prototipo autocontenido renderizado sin red a 1.400 px (y 320 px en la vista de responsive), en las combinaciones Fluent claro, Institucional oscuro compacta y Moderno claro.

Cómo se porta al PCF `CargaAdjuntoBlob`: los tokens con `maker` son las propiedades de entrada del manifiesto; los `derived` se calculan en código cuando el maker cambia el token base; `textos.es.json` alimenta el `.resx`; las medidas de `Anatomia.md` van al CSS por variante y densidad.
