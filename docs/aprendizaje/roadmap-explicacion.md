# Roadmap de explicación de la solución

Para entender CMV8_to_PP de punta a punta, sin saber nada de antemano, y poder contarla sin mirar el diagrama. Se recorre en orden; cada paso tiene un objetivo, qué se tiene que poder explicar al terminar, dónde está el detalle y tres preguntas de chequeo. El estado se actualiza a medida que avanzamos.

Referencia visual de todo: `docs/arquitectura.md` (diagrama y tabla de componentes). Consultas y mejoras que van saliendo: `docs/aprendizaje/consultas-y-mejoras.md`.

| Paso | Tema | Estado |
| --- | --- | --- |
| 0 | La foto completa: el problema, la analogía de la consigna, las piezas | Hecho (2026-10-14) |
| 1 | El flujo de una subida, paso a paso (los 11 números del diagrama) | Pendiente |
| 2 | Identidad: Entra ID, tokens, las dos app registrations, Easy Auth, OBO, credencial federada, identidad administrada | Pendiente |
| 3 | El depósito: Blob Storage, la SAS, las "dos llaves", por qué la cuenta está endurecida | Pendiente |
| 4 | Dataverse como juez: tabla, estados, roles, suplantación, usuario de aplicación | Pendiente |
| 5 | La app: canvas, el PCF, el conector personalizado, DLP, licencias | Pendiente |
| 6 | El encargado: la Azure Function (broker y worker), cola, Watchdog, Key Vault, idempotencia | Pendiente |
| 7 | El timbre y la bóveda: Event Grid, AS400 con jt400, Content Manager con CMIS, la red del Banco | Pendiente |
| 8 | Infraestructura como código: Bicep, CLI y portal; el cuaderno de valores; el orden de montaje | Pendiente |
| 9 | Operación y seguridad: rotaciones, auditoría, acceso condicional, costos | Pendiente |
| 10 | Lo que falta decidir: backlog, desvíos del ADR, lo que solo se prueba adentro | Pendiente |

## Paso 0. La foto completa

- **Objetivo**: saber qué problema resolvemos y cómo, en una analogía.
- **Al terminar podés explicar**: por qué Power Apps no puede ir directo al Content Manager; por qué el archivo no va a Dataverse; qué significa "Dataverse decide, la Function emite, Storage verifica".
- **Detalle**: `docs/arquitectura.md`, sección "La idea en tres frases".
- **Chequeo**: ¿Quién da el ticket? ¿Dónde queda el archivo? ¿Quién lo lleva al Content Manager?

## Paso 1. El flujo de una subida

- **Objetivo**: seguir un archivo desde que el usuario aprieta "subir" hasta que está en Content Manager.
- **Al terminar podés explicar**: cada uno de los 11 pasos, qué lo protege y qué pasa si falla.
- **Detalle**: `docs/arquitectura.md`, tabla "El flujo de una subida"; ADR-002 sección "Flujo".
- **Chequeo**: ¿Por qué el paso 4 (autorizar en Dataverse) va antes del 5 (pedir el token de Storage)? ¿Qué pasa si el usuario cierra el navegador en el paso 6? ¿Quién marca "Archivado"?

## Paso 2. Identidad

- **Objetivo**: entender quién es quién para Microsoft y cómo se prueba.
- **Al terminar podés explicar**: qué es un token; por qué hay DOS app registrations y qué permiso tiene cada una; qué hace Easy Auth; qué es On-Behalf-Of; por qué el broker no tiene secreto (credencial federada) y qué es una identidad administrada.
- **Detalle**: guías 01, 02, 14, 15; backlog §10 (acceso condicional).
- **Chequeo**: ¿Qué app es el "recurso" y cuál el "cliente"? ¿Por qué la identidad administrada sola no alcanza? ¿Dónde está el único secreto de la solución?

## Paso 3. El depósito

- **Objetivo**: entender Blob Storage y la SAS.
- **Al terminar podés explicar**: qué es una SAS de delegación de usuario; qué significa "ligada al usuario" (`sduoid`); por qué solo la SAS da 403, solo el token da 403, y los dos juntos 201; qué apaga la cuenta endurecida (Shared Key, acceso anónimo) y por qué CORS no es seguridad.
- **Detalle**: guía 07; ADR-002 "Modelo de seguridad" y "Diseño de dos llaves".
- **Chequeo**: ¿Cuánto vive una SAS de subida? ¿A cuántos blobs da acceso? ¿Qué rol tiene la identidad sobre el contenedor y por qué no más?

## Paso 4. Dataverse como juez

- **Objetivo**: entender por qué la autorización vive en una tabla.
- **Al terminar podés explicar**: la tabla `SolicitudIngesta` y sus estados; el rol CMV8 Solicitante; qué es suplantación y por qué la Function entra como "usuario de aplicación" pero actúa como el usuario real.
- **Detalle**: guía 18; ADR-002 "Dataverse: suplantación, no OBO"; `dataverse/`.
- **Chequeo**: ¿Qué verifica el broker antes de dar una SAS? ¿Qué pasa si la solicitud no está en Creada? ¿A quién le cuenta Dataverse las requests?

## Paso 5. La app

- **Objetivo**: entender lo que ve y usa el usuario.
- **Al terminar podés explicar**: qué es un PCF y por qué hace falta; qué es un conector personalizado y por qué es la única forma de llamar con la identidad del usuario; qué es la política DLP; por qué todos necesitan Premium.
- **Detalle**: guías 19, 20, 21; `docs/checklist-construccion-app.md`.
- **Chequeo**: ¿Dónde viven la SAS y el token dentro de la app? ¿Por qué no se persisten? ¿Qué operaciones expone el conector?

## Paso 6. El encargado

- **Objetivo**: entender la Azure Function por dentro.
- **Al terminar podés explicar**: Flex Consumption; las rutas del broker; cómo el worker encadena Event Grid, cola y procesamiento; para qué está el Watchdog; qué es idempotencia; cómo lee las contraseñas de Key Vault sin verlas.
- **Detalle**: guías 09, 10, 16, 17; `function/README.md`.
- **Chequeo**: ¿Por qué dos cuentas de Storage? ¿Qué hace el Watchdog a los 15 minutos? ¿Qué pasa si el mismo evento llega dos veces?

## Paso 7. El timbre y la bóveda

- **Objetivo**: entender cómo llega el archivo a los sistemas del Banco.
- **Al terminar podés explicar**: Event Grid y sus reintentos; jt400 y el AS400; CMIS y el Content Manager; qué falta probar en red (S0, VNet).
- **Detalle**: guías 11, 12; ADR-001; `docs/guia-spikes-banco.md` S0, S4a, S6.
- **Chequeo**: ¿Qué pasa si Event Grid no logra entregar en 24 h? ¿Por qué el AS400 se consulta en el worker y no en la app? ¿Qué riesgo tiene OpenCMIS?

## Paso 8. Infraestructura como código

- **Objetivo**: entender cómo se monta todo y en qué orden.
- **Al terminar podés explicar**: qué es Bicep y por qué un solo despliegue; cuándo conviene a mano; qué es el cuaderno de valores; por qué Easy Auth va antes del código y Event Grid después.
- **Detalle**: `docs/guia-montaje-banco.md`; guías 03 a 08, 13; `infra/`.
- **Chequeo**: ¿Qué permiso hace falta sobre el grupo de recursos y por qué Contributor no alcanza? ¿Qué salidas del Bicep van al cuaderno? ¿Qué parámetro cambia entre spike y Banco?

## Paso 9. Operación y seguridad

- **Objetivo**: entender qué hay que hacer para que siga funcionando.
- **Al terminar podés explicar**: qué se rota y cada cuánto; cómo se audita quién subió qué; qué pasa con el acceso condicional del Banco; cuánto cuesta y de qué depende.
- **Detalle**: guías 22, 23; `docs/costos-estimados.md`; `docs/justificacion-financiera-blob-vs-dataverse.md`; backlog §10.
- **Chequeo**: ¿Qué secreto vence a los 6 meses? ¿Dónde se ve el UPN del que subió? ¿Qué error da el broker si el acceso condicional pide MFA que el token no tiene?

## Paso 10. Lo que falta decidir

- **Objetivo**: conocer los puntos abiertos para llevarlos al Banco.
- **Al terminar podés explicar**: cada ítem del backlog §11; los desvíos respecto del ADR-002 (token de Storage al navegador); lo que solo se puede probar adentro.
- **Detalle**: `docs/backlog-tecnico.md`; `docs/plan-solucion.md` sección 4c; `docs/guia-spikes-banco.md`.
- **Chequeo**: ¿Cuál es la decisión de red que destraba todo? ¿Qué eliminaría el último secreto? ¿Qué cambia si el Banco rechaza entregar el token al navegador?
