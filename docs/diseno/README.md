# Diseño visual de los controles de carga

Resultado de pasar `prompt-diseno-controles.md` a una herramienta de diseño asistido. El cliente es "el Banco"; no hay marca real.

| Entrega | Fecha | Contenido | Estado |
| --- | --- | --- | --- |
| Fase 1: anatomía y tokens | 2026-10-19 | `anatomia-y-tokens/`: `Anatomia.md` (componentes, partes, estados, medidas por variante y densidad, teclado, lectores de pantalla), `tokens.json` (85 tokens, prefijo `--fu-`, tres variantes × claro/oscuro, dos densidades, 18 expuestos al maker como propiedades del PCF, 6 derivados), `textos.es.json` (134 textos con clave y notas), y el visor `anatomia-y-tokens.html` + `support.js` (carga React y Babel desde unpkg: necesita internet). | Recibida y revisada |
| Fase 2: prototipo interactivo | — | Un HTML con conmutador de variante, panel de personalización, modo claro/oscuro, las dos vistas (canvas y model-driven) y datos de ejemplo con todos los estados. | Pendiente de pedir a la herramienta |

Cómo se porta al PCF `CargaAdjuntoBlob`: los tokens con `maker` son las propiedades de entrada del manifiesto; los `derived` se calculan en código cuando el maker cambia el token base; `textos.es.json` alimenta el `.resx`; las medidas de `Anatomia.md` van al CSS por variante y densidad.
