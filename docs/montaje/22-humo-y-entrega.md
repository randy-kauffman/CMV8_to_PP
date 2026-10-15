# 22. Prueba de humo y entrega

> Rol que lo ejecuta: Power Platform y Cloud juntos, con dos usuarios piloto. Depende de: todas las guías anteriores (01 a 21). Lo necesitan: las fases siguientes de `docs/guia-spikes-banco.md`. Costo: el de una subida de 100 MB y unos minutos de ejecución (centavos); el informe incluye el costo de Azure de todo el montaje.

## Qué es y qué hace en esta solución

Es la prueba corta de punta a punta que demuestra que el montaje funciona en el tenant del Banco con usuarios reales, y deja evidencia escrita. Corresponde a la fase 0.6 de `docs/guia-spikes-banco.md` (S2 y S7 en corto).

## ¿Imprescindible?

**Veredicto: Prescindible por decisión** (técnicamente), **imprescindible como criterio de "listo"**

- Qué aporta: la evidencia de que las dos llaves, la autorización por fila y la auditoría funcionan con usuarios del Banco, no solo en el tenant de desarrollo. Es lo que permite decir "montado" con pruebas.
- Qué pasa si no está: nada se rompe, pero no hay forma de distinguir "montado" de "creemos que está montado"; el primer error aparece con un usuario de negocio y lejos de su causa.
- Qué decisión lo haría prescindible: ninguna razonable; a lo sumo se puede acortar.
- Alternativa y su tradeoff: confiar en las verificaciones de cada guía. Son necesarias pero no prueban la combinación (conector + Easy Auth + OBO + Dataverse + componente + Storage) ni el rechazo a un usuario sin rol.

## Valores del cuaderno

| Variable | Produce / consume | Cómo se obtiene |
| --- | --- | --- |
| `RG`, `STORAGE_ADJ` | Consume | Cuaderno (para consultar `StorageBlobLogs` en Log Analytics) |
| Dos usuarios piloto | Consume | Uno con CMV8 Solicitante, otro sin el rol |

## Con comandos

Correr la fase 0.6 de `docs/guia-spikes-banco.md`:

1. Un piloto sube 100 MB desde Studio.
2. Otro usuario, sin el rol, pide SAS sobre esa fila y recibe 403.
3. `StorageBlobLogs` muestra `PutBlock` y `PutBlockList` 201 con `AuthenticationType = DelegationSas` y el UPN del piloto.
4. La fila pasa a Subido.

La evidencia queda en `spikes/resultados/BANCO-0-cimientos.md` con el costo de Azure del montaje.

Nota de auditoría (ADR-002): los 403 `AuthenticationFailed` no quedan en `StorageBlobLogs` (fallan antes de autenticar); si se quiere evidencia de un intento rechazado por Storage, usar métricas de transacciones (dimensión `ResponseType`).

## Verificación

El archivo `spikes/resultados/BANCO-0-cimientos.md` existe y registra los cuatro puntos con su evidencia (capturas o consultas) y el costo.

A partir de acá siguen las fases de la guía de spikes: S0 red, hallazgo 10, S4a, S6 real, CORS, Defender, S5.

## Errores que ya vimos

Los que pueden aparecer en el humo están en la tabla de `23-operacion.md`, cada uno con la guía donde se arregla.
