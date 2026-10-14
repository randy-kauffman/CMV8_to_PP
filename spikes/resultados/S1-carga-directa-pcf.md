# S1. Carga directa desde el PCF a Blob Storage

- Fecha: 2026-10-14
- Entorno: canvas app en Studio (entorno Dev `org36e60d9d`), PCF `Banco.CargaAdjuntoBlob` 0.0.2, broker `cmv8-func-supfcopn`, Storage `cmv8adjsupfcopn`.
- Estado: **confirmado**. 100 MB en 15 s. Experimento de paralelismo y bloque: el usuario confirmó que el techo es el uplink de su enlace; ni 8 carriles ni bloques de 8 MB lo movieron.

## Hipótesis

Un control PCF en una canvas app sube 100 MB a Blob Storage por bloques de 4 MB con una SAS de delegación de usuario de un solo blob, sin pasar por Dataverse ni Power Automate, en menos de 2 minutos en una conexión normal.

## Evidencia (StorageBlobLogs en Log Analytics)

| Métrica | Valor |
| --- | --- |
| Bloques `PutBlock` | 25 de 4 MB, 100 MB en total |
| Primer bloque → último bloque | 06:07:13.95 → 06:07:29.07, **15,1 s** |
| `PutBlockList` | 1, 6 ms |
| Latencia del servidor por bloque | 243 ms promedio |
| Tiempo extremo a extremo por bloque | 2.609 ms promedio |
| Errores | 0 |
| Throughput efectivo | 6,6 MB/s, unos **53 Mbps** con 4 bloques en paralelo |

Verificación del blob desde Azure: `22222222-…/video-100mb.bin`, 104.857.600 bytes, descargado completo. El archivo de prueba se generó con `fsutil` (todo ceros), por eso no se compara hash contra la fixture del VPS.

## Veredicto

- Confirmado: el PCF sube 100 MB directo a Blob con SAS de un solo blob. Sin Dataverse, sin Power Automate, sin conector.
- Confirmado: el límite no es Azure. Storage procesa cada bloque en 0,24 s; el resto del tiempo por bloque es transferencia por el enlace del usuario. Más paralelismo solo ayuda si el enlace no está saturado.
- El origen real de la app en Studio es `https://authoring.us-il301.gateway.prod.island.powerapps.com`, variable por región. CORS en Storage y en la Function se configuró en `*`. CORS no es control de seguridad: la SAS lo es.

## Problemas encontrados y resueltos

1. "Couldn't import components" en Studio: dominios con comodín en `external-service-usage` y resx solo en español. Se pusieron hosts concretos y resx 1033.
2. El broker no tenía CORS en la Function App: agregado en `siteConfig.cors`.
3. Storage con CORS solo para `apps.powerapps.com`: Studio corre desde hosts regionales; abierto a `*`.

## Mejora agregada (0.0.3)

Salidas `velocidadMbps` (ventana de 3 s) y `velocidadPromedioMbps`, y la línea de estado muestra `subiendo 45% · 52,3 Mbps (promedio 50,1)` y al terminar `completado en 15,1 s · promedio 53 Mbps`.

## Experimento de throughput (2026-10-14)

Se hizo configurable el tamaño de bloque desde el broker (`SAS_BLOQUE_MB`) y se probó con 8 MB y con paralelismo 2, 4 y 8. Resultado del usuario: misma velocidad en todos los casos. Conclusión: el enlace de subida es el techo; Storage aporta 0,24 s por bloque solapados. Se deja `SAS_BLOQUE_MB=4` (reintentos más baratos) y paralelismo 4.

## Pendiente

- Corrida con 250 MB (opcional, la mecánica no cambia).
- Probar desde la app publicada (player) además de Studio, para capturar el origen del player.
- Costo del spike: pendiente de que Cost Management publique datos.
