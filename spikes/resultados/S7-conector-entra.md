# S7. Conector personalizado con Entra ID y Function protegida

- Fecha: 2026-10-14
- Estado: **confirmado** de punta a punta: conexión creada desde Studio con consentimiento del usuario, `EmitirSasSubida` llamada por el conector, SAS entregada al PCF 0.0.4 por el input `uploadUrl`, subida de 100 MB completada.

## Hipótesis

Un custom connector generado del OpenAPI de la Function, con autenticación Entra ID, llama a la Function con la identidad del usuario, sin claves compartidas, y la Function rechaza toda llamada sin token.

## Configuración

| Pieza | Valor |
| --- | --- |
| App registration del broker | `CMV8 Broker (Function)`, appId `fe2f40d6-cac3-451b-b24d-ab9e93c80482`, URI `api://fe2f40d6-…`, scope `user_impersonation`, tokens v2 |
| App registration del conector | `CMV8 Conector (Power Platform)`, appId `72ec6834-cb06-4d9e-a008-bca52f7f41dd`, redirect `https://global.consent.azure-apim.net/redirect`, secreto vence 2027-04-14, consentimiento de admin otorgado |
| Easy Auth en `cmv8-func-supfcopn` | proveedor Microsoft Entra, issuer `https://login.microsoftonline.com/<tenant>/v2.0`, audiencias `api://<appId>` y `<appId>`, acción Return401, rutas excluidas `/runtime/webhooks/eventgrid` y `/api/salud` |
| Conector | `CMV8 Broker`, id `2102807c-9ac7-f111-a05a-000d3a3b5b91`, en la solución `cmv8ingesta`. Definición en `dataverse/conector/` |

## Evidencia

| Prueba | Resultado |
| --- | --- |
| `GET /api/salud` (ruta excluida) | 200 |
| `POST /api/sas/subida` sin token | **401** con `WWW-Authenticate: Bearer realm=…` |
| `POST /api/sas/subida` con token de Entra del admin (`aud` = broker, `scp` = user_impersonation) | **200**, SAS emitida con `saoid` = oid del usuario (evidencia histórica: horas después se descubrió que `saoid` exige Blob Data Owner y rompe cada bloque con 403; se eliminó, ver S2 «Parte Dataverse» y ADR-002) |
| Webhook de Event Grid sin clave | 401 del host de Functions (pide la clave), no de Easy Auth: la exclusión funciona |
| App Insights | traza `SAS de subida emitida…` para la solicitud 4444…; desde esta versión la traza incluye `llamador=ContextoLlamador[oid=…]` |

## Aprendizajes

- `az functionapp auth` no existe en az 2.91 sin extensión; se configuró con `az rest` sobre `config/authsettingsV2`.
- Las dos app registrations son single tenant, por eso el conector usa el tenant id real y no `common`.
- `pac connector create` falló dos veces con `PessimisticConcurrencyConflict`; a los 5 minutos pasó con los mismos archivos. El nombre interno queda `new_cmv8-20broker`, pac no usa el prefijo del publisher.
- El secreto del conector vive solo en el conector (y en el scratchpad de esta sesión, fuera del repo). El repo tiene un placeholder.

## Prueba desde canvas (2026-10-14)

- Primer intento de conexión: `AADSTS50011`, la redirect URI es por conector (`…/redirect/new-5fcmv8-20broker-5ff95a98b5516c5717`). Se agregó a la app registration del conector.
- Fórmula del botón Preparar: `Patch` de la fila + `'CMV8 Broker'.EmitirSasSubida({...})`; el PCF recibe `sas.uploadUrl` y `sas.bloqueBytes`, con `urlBroker` vacío.
- Resultado: 200 del broker, subida de 100 MB en 19,3 s a 43,4 Mbps. Traza en App Insights con el oid del usuario y la IP de salida de Power Platform.

## Pendiente

- ~~Mover la fila a Subido desde la app al terminar~~ Hecho el 2026-10-14 12:30 UTC (ver S1b, «Prueba desde Studio»): el `OnChange` con `Patch` pasó la fila a Subido en el mismo segundo del `PutBlockList`.
