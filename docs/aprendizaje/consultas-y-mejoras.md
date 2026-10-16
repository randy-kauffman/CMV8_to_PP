# Consultas y puntos de mejora

Bitácora de las preguntas que van surgiendo al recorrer `docs/aprendizaje/roadmap-explicacion.md` y de los puntos de mejora de la solución que salen de esas conversaciones. Cada mejora tiene un estado; cuando se implementa o se descarta, se dice por qué. Los hallazgos técnicos de las guías de montaje están en `docs/backlog-tecnico.md` §11; acá solo se referencian.

## Consultas

| Fecha | Pregunta | Respuesta corta | Detalle |
| --- | --- | --- | --- |
| 2026-10-14 | ¿Tengo que crear app registrations en el tenant del Banco? | Sí, dos (broker y conector), porque una app registration vive en un solo directorio y las nuestras son single tenant. Application Developer alcanza para crearlas; Cloud Application Administrator para el consentimiento de administrador. | guías 01 y 02 |
| 2026-10-14 | ¿Qué permisos necesita cada app registration? | Broker: permiso delegado Azure Storage `user_impersonation` (para el OBO) y expone su propio scope `user_impersonation`. Conector: solo el scope del broker. Ninguna necesita Graph, permisos de aplicación, roles RBAC ni usuario de aplicación en Dataverse (eso lo tiene la identidad administrada). | guías 01 y 02 |
| 2026-10-14 | No entiendo nada: ¿me lo explicás desde cero? | Se armó el roadmap de 10 pasos y se recorrió el paso 0 (el problema, la analogía de la consigna de equipaje, las piezas). | `roadmap-explicacion.md` |
| 2026-10-15 | Paso 3: si el token se genera cuando el usuario entra a la app, ¿cómo sabe Entra que está "dirigido a la Function"? | No es el mismo token. El de entrar a la app es para Power Apps. Cuando el usuario crea la conexión del conector hay OTRO login OAuth en el que la app Conector pide el scope `api://<broker>/user_impersonation`; Entra emite un token con `aud` = id de la app Broker, y ese es el que viaja a la Function. Easy Auth compara ese `aud` con el id del broker. Un token tiene un solo destinatario; hay uno por destino. | guías 02, 15, 20 |
| 2026-10-15 | Paso 4: si otra persona con rol "global" sobre la tabla usa la misma solicitud, ¿puede subir? ¿A nombre de quién queda el archivo? | Sí, puede. El broker no compara dueño: le pregunta a Dataverse (`RetrievePrincipalAccess`) si ESE usuario tiene `WriteAccess` sobre ESA fila; un rol con escritura a nivel organización la tiene. La SAS y el token de Storage se emiten a nombre del que llama (`sduoid` = su oid), así que el blob se sube y queda auditado a nombre de quien realmente subió (`RequesterUpn` en `StorageBlobLogs`); la fila sigue siendo del dueño original. Diseño: "Dataverse decide" quiere decir que mandan los roles del Banco, no una regla nuestra de "solo el dueño". | `AutorizadorSolicitudDataverse.java`; guía 18 |
| 2026-10-15 | Entre 5 y 6: ¿qué es la "clave de delegación" y quién firma la SAS? | La clave de delegación (user delegation key) es un sello temporal que Storage le entrega a una identidad de Entra que tenga el rol Blob Delegator (acá, la identidad administrada); vive como máximo 7 días. La firma la hace NUESTRO código en la Function (`generateUserDelegationSas` del SDK) con esa clave; Storage la reconoce porque él mismo la emitió y la SAS lleva quién la pidió (`skoid`). Nunca se usa la clave de la cuenta (Shared Key está apagada). | `EmisorSasBlob.java`; guías 07, 08 |
| 2026-10-15 | Hay muchas conexiones (usuario→Entra, conector→Function, Function→Dataverse, Function→Entra, Function→Storage, PCF→Storage, worker→AS400/CM). ¿Cómo se debuguea en cuál falla y por qué? | Hay una fuente por tramo y una llave que las une: el `solicitudId` (va en los logs de la Function, en la SAS como `scid`, y es la fila). Tramos: app y conector → Monitor de Power Apps (status y cuerpo de la respuesta); Easy Auth → hoy solo el 401 que ve el conector (no exportamos `AppServiceAuthenticationLogs`, ver M10); código del broker y del worker → Application Insights (trazas con `solicitudId`, excepciones, dependencias hacia Dataverse, Entra, Storage, AS400 y CM); OBO → el error AADSTS en la traza más los registros de inicio de sesión de Entra; Storage → `StorageBlobLogs` (status, `AuthenticationType`, `RequesterUpn`, `scid`); Event Grid → métricas de entregas fallidas; worker → además la fila: estado `Error` con `cmv8_mensajeerror`. Falta una tabla única "paso → dónde mirar → qué consulta", anotada como M10. | guías 05, 23 |
| 2026-10-16 | Paso 2: si la aplicación tiene dos roles, ¿por qué una sola Function y no dos? | Las dos app registrations no son dos roles de la Function: Broker representa a la Function y Conector representa al conector de Power Platform (otro software); partir la Function no las reduce. Lo que sí tiene fundamento es separar broker y worker en dos Function Apps (M5): hoy están juntos por pragmatismo de los spikes (un despliegue, una identidad, un Bicep). A favor de partir: solo el worker necesita VNet, Key Vault y AS400; solo el broker necesita puerta HTTP, Easy Auth, Blob Delegator y credencial federada; escalado y despliegue independientes. En contra: dos apps, dos identidades, dos pipelines, decidir si comparten cola y Storage host. El código hexagonal ya separa los casos de uso y los adaptadores de entrada, así que es empaquetado e infra, no reescritura. Postura: partir cuando exista la VNet (S0). | M5; `function/src/main/java/.../application/` |
| 2026-10-16 | Paso 3: ¿por qué dos cuentas de Storage? | La cuenta host la exige Flex Consumption: paquete de código, cola `ingesta-pendiente`, candados de timers y estado del host. Para eso el runtime necesita Blob Data Owner, Queue Data Contributor y Table Data Contributor sobre esa cuenta, y no se puede recortar. Si los archivos del Banco vivieran ahí, la identidad de la Function sería dueña de todos y un bug podría firmar SAS para cualquier blob. En la cuenta de adjuntos la identidad tiene solo Blob Data Contributor sobre el contenedor `cmv8-ingesta` y Blob Delegator (que es de cuenta porque la clave de delegación es de cuenta). Además las configuraciones son distintas: política de SAS, SAS ligada a usuario, ciclo de vida de 7 días (borraría el paquete de despliegue), CORS, firewall. Costo extra casi nulo. | guías 06, 07, 08; `infra/main.bicep` |
| 2026-10-15 | ¿Qué es `sduoid`? | `signedDelegatedUserObjectId`: un campo de la SAS de delegación (versión 2025-07-05 o posterior) con el `oid` de Entra del usuario final. Cuando está, Storage exige que la petición traiga además un bearer cuyo `oid` sea el mismo. Es lo que ata la SAS a María. No confundir con `skoid` (quién firmó: la identidad administrada) ni con `saoid` (que exige Blob Data Owner y no pide bearer). | ADR-002; guía 07 |

## Puntos de mejora

| # | Fecha | Punto de mejora | Qué aporta | Estado |
| --- | --- | --- | --- | --- |
| M1 | 2026-10-14 | Conector personalizado con identidad administrada en lugar de client secret (soportado por Microsoft, no probado acá) | Elimina el único secreto que queda en la solución y su rotación de 6 meses | Por probar en desarrollo (backlog 11.10) |
| M2 | 2026-10-14 | Dead-letter en la suscripción de Event Grid | Hoy un evento no entregado en 24 h se descarta y solo lo recupera el Watchdog | Por agregar al Bicep (backlog 11.1) |
| M3 | 2026-10-14 | Parámetro para reutilizar el Log Analytics central del Banco | Evita crear un workspace propio donde ya hay uno conectado al SIEM | Por agregar al Bicep (backlog 11.4) |
| M4 | 2026-10-15 | Solución agnóstica de la tabla: hoy el broker y el worker están atados a `cmv8_solicitudingesta`. Debería configurarse, solución por solución, en qué tabla se guarda la solicitud, con un contrato de columnas mínimas (estado, ruta del blob, `cmisObjectId`, mensaje de error, datos del cliente). | Reutilizar el mismo broker y worker desde varias soluciones del Banco sin tocar código; cada solución elige su tabla y mapea las columnas. Opinión preliminar: de acuerdo; el puerto `RepositorioSolicitudes` ya aísla Dataverse, falta que el mapeo de tabla y columnas sea configuración (y decidir si una Function sirve a N tablas o si se despliega una por solución). | A discutir al final (paso 10) |
| M5 | 2026-10-15 | Separar el broker y el worker en Azure Functions distintas en lugar de varias funciones dentro de una sola Function App. | Fronteras más claras: el broker tiene Easy Auth y atiende personas; el worker no tiene puerta HTTP y necesita red interna (VNet). Permisos, escalado, red y despliegue independientes. Opinión preliminar: tiene sentido sobre todo por la red (solo el worker necesita VNet) y por mínimo privilegio (solo el worker necesita Key Vault y AS400); el costo es duplicar plan, identidad y pipeline. Justificación ampliada en la consulta del 2026-10-16; postura: partir cuando exista la VNet (S0). | A discutir al final (paso 10) |
| M6 | 2026-10-15 | Hoy se asume que TODO blob que cae en el contenedor va a Content Manager y va DE INMEDIATO. No es así: algunos archivos no van a CM, y otros van después (bajo demanda o por un job). | Falta un atributo en la solicitud (o en el tipo de documento) que diga destino y momento: "a CM ahora", "a CM cuando se apruebe", "solo blob". El disparador por `BlobCreated` pasaría a encolar y el worker decidiría según ese atributo. | A discutir al final (paso 10) |
| M7 | 2026-10-15 | El PCF sube un solo archivo por vez. Debería aceptar varios, como el control de adjuntos nativo de Power Apps. ¿Funciona también en model-driven apps y desde flujos de Power Automate? | Multi-archivo implica N solicitudes (o una solicitud con N adjuntos) y N SAS. Model-driven: el mismo PCF se puede ligar a un campo o a una página personalizada (por verificar). Power Automate: no hay PCF ni token del usuario de la misma forma; un flujo podría llamar al conector, pero mover 100 MB por el flujo no es viable y la identidad sería la de la conexión del flujo. | A discutir al final (paso 10) |
| M8 | 2026-10-15 | Cada documento en Content Manager tiene un tipo (ItemType) y cada tipo exige metadatos distintos (nombre y los dos ids de cliente son comunes, pero no los únicos). Hoy el mapeo es fijo. Hacen falta: catálogo de tipos con sus metadatos, validaciones de formato (inmediatas, en la app) y de existencia/validez (contra AS400, en el worker, sincrónicas o diferidas), y un camino de resolución para solicitudes con metadatos faltantes o inválidos detectados después de la carga. | Hoy el worker marca `Error` con mensaje y el Watchdog reintenta; no hay estado "falta información" ni manera de que el usuario complete y reanude. Propuesta a evaluar: estado `Observada` + columna con qué falta + reencolado al corregir. | A discutir al final (paso 10) |
| M9 | 2026-10-15 | Hay archivos que deben quedar en Blob aunque ya se hayan archivado en Content Manager. Hoy el worker borra el blob al terminar y el ciclo de vida borra todo a los 7 días. | Atributo de retención por solicitud o tipo (borrar al archivar / conservar N días / conservar siempre) y política de ciclo de vida por prefijo o tag en lugar de global. | A discutir al final (paso 10) |
| M10 | 2026-10-15 | Mapa de diagnóstico por tramo: una tabla "paso del flujo → dónde se ve → consulta KQL o pantalla" en la guía 23, más exportar `AppServiceAuthenticationLogs` de la Function a Log Analytics (hoy los rechazos de Easy Auth no quedan en ningún lado nuestro). | Permite saber en cuál de las siete conexiones falló y por qué sin adivinar. | Por hacer (doc + Bicep) |

## Discusión del paso 10 (2026-10-16)

Posiciones tomadas al cerrar el roadmap. Son propuestas para decidir con el Banco, no decisiones.

### M4. Solución agnóstica de la tabla

- **Opción A, libro de ingesta + lookup (recomendada).** `cmv8_solicitudingesta` deja de ser "la tabla del negocio" y queda como el libro de ingesta: el contrato mínimo (estado, ruta, `cmisObjectId`, error, tipo de documento). Cada solución del Banco tiene su tabla de negocio y la liga al libro con una lookup (patrón "Regarding" de las actividades). La Function y la autorización no cambian; una solución nueva es una lookup y un rol en Dataverse. Costo: una fila del libro además del registro de negocio.
- **Opción B, mapeo configurable por solución.** La Function recibe por configuración tabla y columnas y opera sobre N esquemas. Viable (`RetrievePrincipalAccess` y la Web API sirven para cualquier tabla; el puerto `RepositorioSolicitudes` aísla el cambio), pero N contratos que validar, cada solución nueva toca configuración de Azure, y el mensaje de ingesta debe decir de qué solución es.
- Posición: A, porque pone la extensibilidad del lado de Power Platform y deja la Function estable.

### M5. Broker y worker separados

Partir en dos Function Apps cuando exista la VNet (S0). Detalle en la consulta del 2026-10-16.

### M6. Destino y momento del archivado

- Atributos en el tipo de documento (o en la solicitud): **destino** = Content Manager / solo Blob; **momento** = al subir / bajo demanda.
- Worker en `BlobCreated`: "solo Blob" → estado nuevo **Almacenado**, no borra; "CM al subir" → como hoy; "CM bajo demanda" → estado **Pendiente de archivo**.
- Disparador del "bajo demanda": el Watchdog suma el estado **Aprobada para archivar** y encola (sin componentes nuevos, latencia de hasta 15 minutos). Alternativas: webhook o plugin de Dataverse (inmediato, camino de entrada nuevo); Power Automate (descartado: consume requests). Endpoint "archivar ahora" en el broker solo si hace falta inmediatez.

### M7. Varios archivos, model-driven, Power Automate

- **Varios archivos**: tabla hija **adjunto** (1 solicitud : N adjuntos), cada uno con ruta, estado, `cmisObjectId` y su propia SAS; el PCF acepta N archivos y pide N SAS; el worker procesa por adjunto. Alternativa: N solicitudes hermanas (más simple en código, peor para el usuario y la auditoría).
- **Model-driven**: mismo framework; el manifiesto hoy declara el control para canvas y hay que agregar la variante de formulario, o usar una página personalizada (canvas dentro de model-driven). Pendiente de verificar.
- **Power Automate**: no es el mismo caso de uso. Un flujo no tiene navegador ni PCF, el token sería el de la conexión del dueño del flujo y 100 MB por un flujo no es viable. Ingesta de archivos que ya están en otro sistema = "ingesta de sistema", con identidad propia y sin las dos llaves; nombrarla aparte.

### M8. Tipos de Content Manager, metadatos y validaciones

- Tabla **tipo de documento**: ItemType de CM, carpeta destino, atributos de M6 y M9.
- Tabla **definición de metadato** por tipo: nombre, propiedad CMIS, obligatorio, validación de formato (expresión regular, largo, lista de valores), validación de existencia (ninguna / consulta al AS400 parametrizada).
- Valores por solicitud: tabla hija **valor de metadato** (preferida: se busca, se audita, se muestra en vistas) o columna JSON.
- Dónde se valida: formato en la app (reglas armadas desde el catálogo) y de nuevo en el broker antes de la SAS; existencia contra el AS400 en el broker cuando la consulta es rápida (como `ResolverCliente` hoy) y en el worker cuando es lenta. El worker puede leer la definición del tipo por CMIS antes de `createDocument` y verificar lo obligatorio.
- Resolución de faltantes: estado **Observada** con el detalle en `mensajeerror`; el usuario corrige en la app, la fila vuelve a **Subido** y el Watchdog la reencola. Una fila Observada no se reintenta sola.
- `MapeoPropiedadesCmis` pasa a ser dirigido por datos. Es el cambio de código más grande y el de más valor.

### M9. Retención en Blob

Atributo de **retención** en el tipo o la solicitud (borrar al archivar / conservar N días / conservar). El worker no borra cuando corresponde; la política de ciclo de vida filtra por etiquetas de índice del blob (`retencion=permanente` excluido de la regla de 7 días); lectura con la SAS de lectura existente. Decidir con el Banco qué significa "conservar", porque un depósito que solo crece necesita su propia política.

### M10. Mapa de diagnóstico

Se hace: tabla por tramo en la guía 23 y categoría `AppServiceAuthenticationLogs` al Log Analytics en el Bicep.

### Orden propuesto

1. Ahora, barato: M10, M2, M1 en desarrollo.
2. Diseño "v2 del modelo de datos" antes de construir la app real en el Banco: M8 como eje; M4, M6 y M9 como atributos del tipo de documento; M7 como tabla hija. Registrarlo como ADR-003.
3. M5 cuando S0 responda.
4. Las decisiones del Banco (red, token OBO al navegador, acceso condicional, plan, Defender, Key Vault, Log Analytics, DLP, Owner) en paralelo.
