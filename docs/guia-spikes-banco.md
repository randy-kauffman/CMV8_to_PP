# Guía paso a paso: qué se hace dentro del Banco y qué evidencia se recoge

Fecha: 2026-10-14. Complementa `docs/plan-solucion.md` §4b y §4c, `docs/backlog-tecnico.md` y `spikes/resultados/`. Todo lo que se podía probar fuera del Banco ya está confirmado; esta guía cubre lo que SOLO se puede probar adentro, en el orden en que conviene hacerlo, con los pasos exactos y la evidencia que cierra cada punto.

## Cómo usar esta guía

- **Orden.** Fase 0 primero, siempre: sin los cimientos en el tenant del Banco no se puede probar nada. Después S0 (red), porque habilita S6 real, S4a y el hallazgo 10. Lo demás en el orden que convenga a la agenda de los equipos.
- **Quién.** Cada fase indica el equipo del Banco que tiene que estar: Identidad (Entra), Red/Cloud (Azure), Power Platform (admin del tenant y maker), IBM i (administrador del AS400), Content Manager (administrador de CM V8), Seguridad.
- **Evidencia.** Un archivo por fase en `spikes/resultados/BANCO-<fase>-<nombre>.md` con la misma estructura que los spikes de afuera: hipótesis, pasos ejecutados, evidencia, veredicto (confirmado / refutado / parcial). Capturas en `spikes/resultados/evidencia/BANCO-<fase>-*.png`. Salidas de comandos pegadas en bloques de código. **Sin secretos**: ni contraseñas, ni tokens, ni SAS completas, ni cadenas de conexión; se enmascaran antes de pegar. Los identificadores (request id, object id, timestamps en UTC) sí van, porque son lo que permite auditar.
- **Costo.** Cada fase anota el costo real de Azure que generó (Cost analysis de la suscripción del Banco, filtrado por el grupo de recursos), igual que hicimos afuera.

## Fase 0. Cimientos en el tenant del Banco

Reproduce lo que ya funciona en el tenant propio. Equipos: Identidad, Cloud, Power Platform. Los comandos exactos de cada paso están en `docs/guia-montaje-banco.md` (índice) y en una guía por componente en `docs/montaje/`, con Bicep, CLI y portal; acá queda el resumen y la evidencia que cierra la fase.

### 0.1 Suscripción, región y proveedores

1. Suscripción y grupo de recursos para la solución (`rg-cmv8-<ambiente>`), región con Flex Consumption para Java 21 (`infra/desplegar.sh` lo verifica con `az functionapp list-flexconsumption-runtimes`).
2. Proveedores registrados: Microsoft.Web, Storage, EventGrid, Insights, OperationalInsights, ManagedIdentity, Security, KeyVault y **Microsoft.App** (lo exige la delegación de subred de Flex Consumption).
3. Presupuesto con alertas sobre el grupo de recursos (el `cmv8-spikes-mensual` de afuera está a nivel de suscripción, 5 USD/mes; adentro conviene por grupo: `docs/montaje/13-presupuesto-y-alertas.md`).

Evidencia: salida de `az account show`, lista de proveedores en `Registered`, captura del presupuesto.

### 0.2 App registrations en Entra (Identidad)

1. **Broker** ("CMV8 Broker (Function)"): exponer API con `api://<appId>`, scope delegado `user_impersonation`, tokens v2 (`accessTokenAcceptedVersion = 2`), single tenant. Permiso delegado **Azure Storage / user_impersonation** con consentimiento de administrador (es lo que habilita el OBO hacia Storage). Sin secretos: después de desplegar la infraestructura (0.3) se agrega la **credencial federada** hacia la identidad administrada de la Function: issuer `https://login.microsoftonline.com/<tenant>/v2.0`, subject = principal id de la UAMI, audiencia `api://AzureADTokenExchange`. Comando de referencia: `az ad app federated-credential create --id <objectId de la app> --parameters credential.json` (ver `docs/backlog-tecnico.md` §6a).
2. **Conector** ("CMV8 Conector (Power Platform)"): cliente del broker, con permiso al scope `user_impersonation` del broker y consentimiento de administrador; redirect URIs `https://global.consent.azure-apim.net/redirect` y la específica por conector que se conoce recién después de crearlo (0.5). Un secreto de cliente, guardado en el vault del Banco, con fecha de rotación en el calendario.
3. Pre-autorizar el conector en el broker (`preAuthorizedApplications`) para que la conexión no pida consentimiento por usuario, si la política del Banco lo permite.

Evidencia: `az ad app show` de las dos apps (sin secretos), captura del consentimiento de administrador otorgado, `az ad app federated-credential list` del broker mostrando la credencial.

### 0.3 Infraestructura y Function (Cloud)

1. Copiar `infra/spikes.bicepparam` a `infra/banco-<ambiente>.bicepparam` y ajustar: `origenesCors` (ver fase 6), `ipsPermitidas` (rangos de salida del Banco si la carga siempre es desde la red del banco), `requerirSasLigadaAUsuario=true`, `accionSasNoLigada='Block'`, `brokerClientId` y `brokerTenantId` del tenant del Banco, `keyVaultAccesoPublico=false` (con private endpoint; ver S0), `as400Host`, `as400Usuario`, `as400SqlCliente` reales, `cmisUrl` y demás `cmis*` reales, `habilitarDefender` según fase 7, `oidOperadorKeyVault` = object id de quien carga los secretos.
2. `bash infra/desplegar.sh rg-cmv8-<ambiente> <región> infra/banco-<ambiente>.bicepparam`.
3. Easy Auth sobre la Function con `az rest` sobre `config/authsettingsV2` (comandos y cuerpo en `dataverse/conector/README.md`): proveedor Entra, issuer v2 del tenant del Banco, audiencias `api://<broker>` y `<broker appId>`, acción Return401, rutas excluidas `/runtime/webhooks/eventgrid` y `/api/salud`.
4. Secretos: `bash infra/cargar-secretos.sh rg-cmv8-<ambiente> <archivo con AS400_PASSWORD y CMIS_PASSWORD>` desde una estación autorizada, y `az functionapp restart`. Verificar la resolución con `GET …/sites/<function>/config/configreferences/appsettings?api-version=2023-12-01` (todas `Resolved`).
5. Despliegue del código: `cd function && mvn clean package azure-functions:deploy -DfunctionAppName=<function> -DresourceGroup=<rg> -Dregion=<región>` (no usar `func azure functionapp publish` en Flex). `GET /api/salud` → 200.

Evidencia: `infra/ultimo-despliegue.outputs.json`, salida de `authsettingsV2` (GET), salida de `configreferences` con `Resolved`, `curl -i /api/salud` 200, `POST /api/sas/subida` sin token → 401.

### 0.4 Dataverse (Power Platform)

1. Entorno administrado del Banco, publisher con prefijo `cmv8`, solución `cmv8ingesta`.
2. `DATAVERSE_URL=<url> bash dataverse/crear-tabla.sh` y `DATAVERSE_URL=<url> APP_CLIENT_ID=<identidadClientId> bash dataverse/crear-roles.sh` (roles CMV8 Worker y CMV8 Solicitante, con mínimo privilegio como se decidió afuera).
3. Usuario de aplicación para la identidad administrada en el admin center, con el rol CMV8 Worker, y nada más.
4. Rol CMV8 Solicitante a los usuarios piloto.

Evidencia: tabla y columnas en el explorador de soluciones, roles con sus privilegios (captura), usuario de aplicación con su rol, lista de usuarios piloto.

### 0.5 Conector y componente (Power Platform)

1. **PCF**: antes de compilar, cambiar en `pcf/CargaAdjuntoBlob/ControlManifest.Input.xml` los dominios de `external-service-usage` por el hostname real de la Function y de la cuenta de Storage del Banco (hoy tienen los del spike). `npm run build -- --buildMode production` y `pac pcf push --publisher-prefix cmv8` contra el entorno del Banco.
2. **Conector**: copia temporal de `dataverse/conector/apiProperties.json` fuera del repo con el client id, el tenant id y el secreto del conector del Banco; `pac connector create` con el swagger y esa copia (comandos en `dataverse/conector/README.md`). Leer en la pestaña Seguridad del conector la URL de redirección específica y agregarla a la app registration del conector (0.2).
3. Política DLP: el conector en el grupo de datos de negocio junto con Dataverse.
4. App canvas con el checklist de `docs/checklist-construccion-app.md`.

Evidencia: versión del componente importado, conector listado con `pac connector list`, captura de la política DLP, captura de las fórmulas del botón Preparar y del `OnChange`.

### 0.6 Humo: repetir S2 y S7 en corto

1. Desde Studio con un usuario piloto: crear la conexión (consentimiento), Preparar, subir un video de 100 MB.
2. Con otro usuario sin el rol: pedir SAS sobre la fila del primero → 403.
3. `StorageBlobLogs`: `PutBlock`/`PutBlockList` 201 con `AuthenticationType=DelegationSas` y `RequesterUpn` del usuario piloto.
4. La fila pasa a Subido.

Evidencia: `BANCO-0-cimientos.md` con la traza de App Insights (`SAS de subida emitida… ligadaUsuario=true`), la consulta KQL y su resultado, el 403 del segundo usuario, el estado de la fila, duración y velocidad que reporta el componente. Costo de la fase.

## S0. Red: la VNet, la salida a internet y el acceso privado

Equipos: Red/Cloud, Identidad. Resuelve el hallazgo 10 (ubicación), habilita S6 real y S4a, y permite cerrar el Key Vault con private endpoint.

### Preguntas que el Banco responde antes de tocar nada

1. ¿Cómo salen a internet las VMs que hoy se conectan a My Apps: NAT Gateway con IP reservada, Azure Firewall con ruta por defecto, o breakout on-prem por ExpressRoute con proxy?
2. ¿Cuál es esa IP pública? Desde una de esas VMs: `curl https://ifconfig.me`.
3. ¿Esa IP figura en una **ubicación con nombre** marcada como de confianza en Entra? (Identidad: Protección → Acceso condicional → Ubicaciones con nombre.)
4. ¿Hay en esa VNet, o en un spoke emparejado con la misma tabla de rutas, una subred `/27` libre, delegable a `Microsoft.App/environments`, sin private endpoints ni otras delegaciones, con nombre sin guion bajo?
5. ¿Desde esa VNet hay ruta al IBM i (puertos 9471 y 9476, o 8471 y 8476 si no hay TLS) y al servidor CMIS de CM V8 (puerto HTTPS del IHS o del WAS)?

### Pasos

1. Agregar a `main.bicep` la integración de red de la Function (`virtualNetworkSubnetId` en `Microsoft.Web/sites`) y, si la subred no la tiene, asociarle el **mismo** NAT Gateway o la misma tabla de rutas que las VMs. Integrar la VNet sola NO cambia la IP de salida: sin NAT o ruta, la Function sigue saliendo por sus IPs de plataforma.
2. Key Vault con `keyVaultAccesoPublico=false` y private endpoint en esa VNet, con la zona DNS privada `privatelink.vaultcore.azure.net` enlazada. Lo mismo para la cuenta de adjuntos si el Banco decide cerrar también su endpoint público al worker (el navegador sigue entrando por el endpoint público con SAS).
3. Función de diagnóstico temporal `GET /api/diagnostico/red` (detrás de Easy Auth, se quita al terminar) que devuelva: IP pública de salida (`https://api.ipify.org`), y resultado de abrir TCP contra el IBM i (9471, 9476) y contra CM V8 (host y puerto del CMIS). Se agrega al repo antes de la salida al Banco.
4. Ejecutar el diagnóstico con un token del broker y comparar la IP con la respuesta 2.
5. En Entra, Registros de inicio de sesión → **Inicios de sesión no interactivos**, filtrar por aplicación "CMV8 Broker (Function)" o recurso "Azure Storage": debe aparecer la IP de la Function y, en Acceso condicional, las políticas evaluadas con resultado "Correcto".

### Evidencia (`BANCO-S0-red.md`)

- Respuestas 1 a 5 con quién las dio y cuándo.
- Salida del diagnóstico (IP y puertos abiertos) y la captura de la ubicación con nombre que contiene esa IP.
- Captura del inicio de sesión no interactivo con las políticas aplicadas.
- `nslookup <kv>.vault.azure.net` desde la Function (en el diagnóstico) resolviendo a la IP privada.
- Veredicto: la Function sale por una IP de confianza y llega al IBM i y a CM V8 por red privada.

## Hallazgo 10. Acceso condicional y OBO con usuarios reales

Equipos: Identidad, Seguridad, un usuario piloto con equipo corporativo. Requiere S0 cerrado.

### Pasos

1. Usuario piloto, desde su equipo corporativo con Edge (SSO del navegador para que viajen los claims de dispositivo): entrar a la app, crear la conexión del conector, subir un archivo. Esperado 201.
2. Mismo usuario, desde un equipo NO conforme o un navegador sin SSO, si la política del Banco exige dispositivo: esperado que falle al crear la conexión (lo bloquea la política sobre la app del conector o sobre Power Apps), o que el broker devuelva 401 con `WWW-Authenticate: Bearer error="interaction_required"`. Anotar cuál de los dos pasa: define el mensaje que la app le muestra al usuario.
3. Con Identidad, leer los tres inicios de sesión de la cadena en los registros: interactivo (crear la conexión, con MFA y dispositivo), no interactivo del conector (IP de Power Platform) y no interactivo del broker hacia Azure Storage (IP de la Function). En cada uno, la pestaña Acceso condicional con las políticas y su resultado.
4. Si el Banco tiene evaluación continua de acceso: deshabilitar la cuenta del piloto en medio de una subida larga y confirmar que los PUT siguientes fallan con 401 (el token se revoca) y que la fila queda en Creada para reintentar.
5. Decisión de Seguridad sobre la desviación documentada en `docs/backlog-tecnico.md` §10 (el token OBO viaja al navegador): aceptada con mitigaciones, o se cambia a carga por conector estándar.

### Evidencia (`BANCO-10-acceso-condicional.md`)

- Capturas de los tres inicios de sesión con políticas y resultados.
- Traza de App Insights `OBO del broker con la identidad administrada` y `SAS de subida emitida… ligadaUsuario=true` del piloto.
- Respuesta 401 con el encabezado `WWW-Authenticate` en la prueba negativa (cuerpo JSON, sin tokens).
- Acta de la decisión de Seguridad (quién, cuándo, qué).

## S4a. Worker contra IBM Content Manager V8 real por CMIS

Equipos: Content Manager, Red/Cloud. Requiere S0 (ruta al servidor CMIS). Afuera se probó contra OpenCMIS InMemory (S4b); acá se prueba lo que ese servidor no puede: tipo real, propiedades, credenciales, TLS, proxy.

### Antes de empezar, CM entrega

- URL del servicio CMIS de CM V8 con **Browser binding** (JSON), el id del repositorio, el usuario de servicio con permiso de crear documentos en la carpeta destino, el item type (`CMIS_TYPE_ID`) y la lista de atributos obligatorios con su tipo (texto, entero, fecha, lista de valores).
- Certificado de la CA interna si el endpoint es HTTPS con CA propia.
- Si hay IHS, WebSEAL o balanceador delante: quién lo administra.

### Pasos

1. **Descubrimiento**: desde una VM de la VNet, `curl -u <usuario> https://<cm>/<ruta>/cmis/browser` → JSON con los repositorios, `cmisVersionSupported` 1.1 y `capabilities`. Guardar la salida (sin el usuario).
2. **Chunked** (hallazgo 8): `curl -v -u <usuario> -H "Transfer-Encoding: chunked" -X POST --data-binary @archivo-1mb.bin "https://<cm>/<ruta>/cmis/browser/<repo>/root?cmisaction=createDocument&..."`, o más simple, el paso 3 directamente: si el proxy rechaza chunked, OpenCMIS falla con 411 o 400 en el primer `createDocument`. Si falla, Red cambia la política del proxy para esa ruta o se evalúa un `HttpInvoker` propio (backlog).
3. **Prueba de integración desde la VNet**: en una VM con Java 21 y el repo, `cd function && S4B_CMIS_URL=https://<cm>/<ruta>/cmis/browser S4B_CMIS_REPOSITORY_ID=<repo> mvn -Pintegracion -Dtest=S4bStreamingCmisIT test` con `S4B_CMIS_USER` y `S4B_CMIS_PASSWORD` reales exportados en la sesión (nunca en la línea de comandos ni en archivos del repo). Correr con `-Xmx64m` y 100 MB, después con 250 MB. Anotar heap máximo y tiempo.
4. **Tipo y propiedades**: configurar `CMIS_TYPE_ID` y `CMIS_PROPIEDADES_JSON` con los atributos reales. Si hay atributos no textuales (fechas, enteros), hoy `MapeoPropiedadesCmis` envía todo como texto: ajustar el mapeo con el tipo que declare CM (se ve en `getTypeDefinition`). Idempotencia: mapear `${solicitudId}` a un atributo de CM y verificar que `buscarPorSolicitud` lo encuentra por consulta CMIS QL (queryName correcto, atributo consultable).
5. **Punta a punta desde la app**: subir un video, confirmar el evento de Event Grid, el worker en App Insights (`ProcesarIngesta`), la fila en Archivado con `cmisObjectId`, el documento en CM con sus atributos, y el blob borrado.
6. **Repetir la misma solicitud** (reencolar a mano): mismo `cmisObjectId`, sin duplicado en CM.
7. **Credenciales malas**: cambiar `cmis-password` en Key Vault por un valor incorrecto, reiniciar, procesar: la fila pasa a Error con un mensaje sin la contraseña; restaurar el secreto.
8. **Versionado**: si el tipo es versionable, confirmar qué hace `VersioningState.MAJOR` en CM (versión 1.0 creada, no "checked out").
9. **Límites**: tamaño máximo de contenido y timeouts del servidor CMIS y del proxy, con el archivo de 250 MB.

### Evidencia (`BANCO-S4a-cm-v8.md`)

- JSON del descubrimiento, resultado de chunked (código HTTP y, si falló, quién lo corrigió).
- Log de `S4bStreamingCmisIT` con heap y tiempos para 100 y 250 MB.
- Definición del tipo (`getTypeDefinition`) y el `CMIS_PROPIEDADES_JSON` final (sin credenciales).
- Captura del documento en CM con atributos, `cmisObjectId` en la fila, traza del worker, evidencia del blob borrado (`az storage blob list` vacío para esa solicitud).
- Resultado de idempotencia, de credenciales malas y de versionado.
- Throughput medido y límites encontrados.

## S6 real. IBM i del Banco con jt400

Equipos: IBM i, Red/Cloud, Seguridad. Requiere S0 (puertos abiertos). Afuera se probó la mecánica contra PUB400 y el pool desde Azure.

### Antes de empezar, IBM i entrega

- Host y puertos: 9471 (base de datos TLS) y 9476 (signon TLS). 449 solo si no se fijan puertos. 8471/8476 solo si no hay TLS (no recomendado).
- Certificado del servidor (DCM) y su CA.
- Perfil de servicio dedicado: solo lectura sobre las bibliotecas del cliente, `PWDEXPITV` definido con rotación acordada, `INLMNU(*SIGNOFF)`, sin autoridades especiales. Confirmar `QMAXSIGN` (intentos fallidos antes de deshabilitar) y el timeout de inactividad de los jobs QZDASOINIT (`QINACTITV` o el del subsistema) y el idle timeout del firewall: `AS400_POOL_VIDA_MAX_MINUTOS` tiene que ser menor que ambos.
- Las consultas reales (las provee el usuario de este proyecto): una sola con un `?` que devuelva `IDCLIENTE, IDCLIENTE2, NOMBRE, AFILIADOPADRE, IDDUENO`.

### Pasos

1. Diagnóstico de red (S0): 9471 y 9476 abiertos desde la Function.
2. **TLS**: instalar la CA del IBM i para la Function. Dos opciones, decidir con Cloud: (a) incluir un truststore en el paquete y `JAVA_OPTS`/`JAVA_TOOL_OPTIONS` con `-Djavax.net.ssl.trustStore`, o (b) emitir el certificado del IBM i con una CA que Java ya confíe. Probar primero con `bash spikes/s6-jt400/correr.sh <settings>` desde una VM de la VNet con Java 21, que imprime signon, TLS y latencias.
3. Cargar `as400-password` con `infra/cargar-secretos.sh`, configurar `as400Host`, `as400Usuario`, `as400SqlCliente` reales en el bicepparam, desplegar, reiniciar.
4. `POST /api/clientes/resolver` con ids reales: uno existente, uno inexistente (404), uno con acentos o eñes en el nombre (CCSID: verificar que llegan bien; si hay columnas `FOR BIT DATA`, propiedad `translate binary`).
5. Latencias: primera llamada (pool frío), 10 llamadas calientes. Comparar con las de afuera (7,85 s y ~1,5 s desde eastus2 a PUB400; adentro deberían ser decenas de ms).
6. Pool: `bash spikes/s6-jt400/correr.sh <settings> pool` desde la VM, y dejar la Function una hora sin tráfico; después una llamada: tiene que responder sin error de conexión cerrada (si falla, `AS400_POOL_VIDA_MAX_MINUTOS` es mayor que el timeout del otro lado).
7. Rotación: cambiar la contraseña del perfil, actualizar el secreto en Key Vault, reiniciar, volver a llamar. Anotar el procedimiento acordado y quién lo ejecuta.
8. Seguridad: intentar con el perfil de servicio una consulta de escritura o a otra biblioteca → debe fallar por autoridad.

### Evidencia (`BANCO-S6-ibm-i.md`)

- Salida de `correr.sh` (signon, TLS, latencias) con usuario y host enmascarados si Seguridad lo pide.
- Respuestas de `/api/clientes/resolver` (existente, inexistente, con acentos) y tabla de latencias.
- Resultado de la prueba de una hora sin tráfico y el valor final de `AS400_POOL_VIDA_MAX_MINUTOS` con los timeouts que lo justifican.
- Procedimiento de rotación y resultado de la prueba de autoridad.

## Fase 6. CORS: comodín o proxy (hallazgo 9)

Equipos: Cloud, Power Platform, Seguridad.

1. En Storage, reemplazar `*` por `https://*.powerapps.com` (y, si el Banco usa el player de Teams o móvil, agregar los orígenes que aparezcan en el paso 3) con `origenesCors` del bicepparam.
2. Subir desde Studio y desde la app publicada (player web) con un usuario piloto. Si el PUT falla por CORS, en la consola del navegador (F12 → Red → el PUT fallido) se lee el `Origin` real: anotarlo.
3. Repetir desde Teams y desde el móvil si el Banco los usa.
4. En la Function, cerrar CORS (`allowedOrigins: []`): al broker lo llama el conector desde los servidores de Microsoft, no el navegador. Confirmar que la app sigue funcionando.
5. Si el comodín de un nivel no cubre los orígenes de varios niveles (`authoring.us-il301.gateway.prod.island.powerapps.com`), decidir con Seguridad: volver a `*` (CORS no es frontera de seguridad: la dan la SAS ligada y el bearer) o poner un proxy con el origen fijo delante de Storage, que cambia el diseño de carga directa.

Evidencia (`BANCO-6-cors.md`): configuración CORS final de Storage y Function (`az storage cors list`, `az functionapp cors show`), orígenes observados por cliente (Studio, player, Teams, móvil), resultado de cada subida y la decisión.

## Fase 7. S3: Defender for Storage

Equipos: Seguridad, Cloud. Solo si el Banco ya tiene Defender for Storage habilitado a nivel suscripción o acepta el costo fijo (`docs/justificacion-financiera-blob-vs-dataverse.md` §8).

1. `habilitarDefender=true` en el bicepparam; desplegar. Después de desplegar el código, `crearSuscripcionEventGrid=true` y volver a desplegar (la suscripción valida el endpoint de la Function).
2. Subir `eicar.com` (el archivo de prueba estándar) desde la app: esperado evento `Microsoft.Security.MalwareScanningResult` con veredicto malicioso, fila en Rechazado, blob borrado.
3. Subir el video de 100 MB: veredicto limpio, fila encolada, worker procesa.
4. Medir el tiempo entre `PutBlockList` y el evento de resultado, para 100 MB y 250 MB.
5. Leer el costo en Cost analysis al día siguiente: cuota por cuenta, GB escaneados, transacciones.

Evidencia (`BANCO-S3-defender.md`): JSON de los dos eventos (sin SAS), estado de las filas, trazas del worker, tabla de latencias, costo del día.

## Fase 8. S5 en el Banco: atribución con el mix de licencias real

Equipos: Power Platform admin.

1. Confirmar en el admin center del Banco qué licencias hay (Dynamics 365 Enterprise o Professional definen un pool de 500.000+; solo Power Apps, 25.000) y qué otras integraciones consumen hoy el pool de usuarios no licenciados.
2. Con un usuario piloto, 50 subidas desde la app (o 50 llamadas a `/api/sas/subida` con su token, como se hizo afuera), anotando la hora UTC.
3. Al día siguiente: Administrar → Dataverse → análisis del entorno → Usuarios activos → "Usuarios más activos que realizan operaciones", y el detalle por usuario: lecturas y actualizaciones sobre `cmv8_solicitudingesta` y `systemuser`. Anotar si caen bajo el piloto o bajo el usuario de aplicación "CMV8 Function".
4. Recordar que el reporte de "Power Platform requests" del admin center excluye Dataverse (Learn), así que la atribución de licenciamiento sigue sin ser legible; el riesgo del pool queda documentado y se revisa cuando Microsoft publique el reporte.

Evidencia (`BANCO-S5-atribucion.md`): captura de licencias, hora y cantidad de la carga, capturas del análisis de Dataverse del día siguiente, veredicto.

## Lista final de salida (go / no-go)

| Punto | Evidencia que lo cierra | Responsable |
| --- | --- | --- |
| Fase 0 cimientos | `BANCO-0-cimientos.md`: salud 200, 401 sin token, subida 100 MB con `RequesterUpn` del piloto, 403 del usuario sin rol | Cloud + Power Platform |
| S0 red | `BANCO-S0-red.md`: IP de salida en ubicación de confianza, puertos al IBM i y CM abiertos, Key Vault por DNS privado | Red + Identidad |
| Hallazgo 10 | `BANCO-10-acceso-condicional.md`: tres inicios de sesión con políticas en "Correcto", acta de Seguridad sobre el token en el navegador | Identidad + Seguridad |
| S4a | `BANCO-S4a-cm-v8.md`: documento real en CM con atributos, `cmisObjectId` en la fila, blob borrado, chunked aceptado, 250 MB | Content Manager |
| S6 real | `BANCO-S6-ibm-i.md`: resolución con ids reales y acentos, latencias, pool tras una hora, rotación probada | IBM i |
| CORS | `BANCO-6-cors.md`: configuración final y subidas desde cada cliente | Cloud + Seguridad |
| S3 | `BANCO-S3-defender.md`: EICAR rechazado, video limpio, latencia, costo | Seguridad |
| S5 | `BANCO-S5-atribucion.md`: análisis de Dataverse del día siguiente | Power Platform admin |
| Costo | Cost analysis del grupo de recursos por fase, en cada archivo | Cloud |

Cuando las nueve filas tienen evidencia, el ADR-002 pasa de "Aceptada con ajustes" a "Aceptada" y arranca la construcción de la app real con `docs/checklist-construccion-app.md`.
