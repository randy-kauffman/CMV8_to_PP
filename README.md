# CMV8_to_PP

Ingesta de documentos (incluidos videos de hasta 100 MB) desde Power Apps / Power Automate hacia IBM Content Manager V8 a través de su API CMIS 1.1, con metadatos obligatorios resueltos desde AS400.

## Estructura

| Carpeta | Contenido |
| --- | --- |
| `docs/` | Arquitectura con diagrama (`arquitectura.md`, `diagramas/`), ADRs, plan de solución, costos, índice de montaje en el tenant del Banco (`guia-montaje-banco.md`) con una guía por componente en `montaje/` (Bicep, CLI y portal), y guía de spikes adentro (`guia-spikes-banco.md`) |
| `infra/` | Bicep: Storage endurecido, Function Flex Consumption, Event Grid, Key Vault |
| `function/` | Azure Function en Java: broker de SAS, resolución de cliente (jt400), worker CMIS (OpenCMIS) |
| `pcf/` | Componente PCF de carga directa a Blob con SAS |
| `dataverse/` | Solución exportada: tabla SolicitudIngesta, roles, conector personalizado |
| `spikes/` | Guiones y resultados de cada spike (`spikes/resultados/`); fixtures pesados en `spikes/fixtures/` (ignorados por git) |

## Spikes

El orden y el alcance de cada spike están en `docs/plan-solucion.md`, sección 4 y 4b. Cada spike deja un archivo `spikes/resultados/S<n>-<nombre>.md` con hipótesis, pasos, evidencia y veredicto.

## Herramientas

az CLI + bicep, Azure Functions Core Tools v4, Java 21 (Temurin) + Maven vía SDKMAN, node 22 + pac CLI.

## Fixtures

```bash
bash spikes/generar-fixtures.sh
```

Genera `spikes/fixtures/video-100mb.bin` y `spikes/fixtures/video-250mb.bin` con contenido aleatorio.
