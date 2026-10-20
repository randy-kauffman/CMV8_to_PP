# 20. Conector personalizado "CMV8 Broker" y política DLP

> Rol que lo ejecuta: Power Platform (creador del conector: administrador del entorno; política DLP: administrador de Power Platform). Depende de: 01 y 02 (las dos apps y el secreto del conector en el vault), 15 y 16 (Function publicada con Easy Auth), 18 (solución `cmv8ingesta`). Lo necesitan: 02 (la URL de redirección específica que se genera acá), 21 (la app llama al conector), 23 (rotación del secreto). Costo: sin costo propio; todo conector personalizado es **premium** (licencia Power Apps Premium por usuario, ya confirmada).

## Qué es y qué hace en esta solución

El conector **CMV8 Broker** es la puerta por la que la canvas app llama a la Function **con el token del usuario**. Está definido por dos archivos del repo:

- `dataverse/conector/cmv8-broker.swagger.json`: OpenAPI 2.0, versión 2.0 (v2, ADR-003), `basePath` `/api`, trece operaciones (tabla de abajo). El `host` apunta al sandbox y se reemplaza por el del ambiente.
- `dataverse/conector/apiProperties.json`: OAuth 2.0 con Entra (`identityProvider: aad`), `clientId` de la app del conector, `resourceUri` = `api://<appId del broker>`, tenant. El `clientSecret` lleva un **marcador**: el secreto nunca está en el repo.

Al crear la conexión, el usuario inicia sesión con Entra; el conector pide un token para `resourceUri` y lo manda como `Authorization: Bearer` a la Function, donde Easy Auth lo valida (guía 15). La respuesta de `EmitirSasLote` trae N SAS (una por adjunto) y UN token de Storage del usuario obtenido por OBO.

### Operaciones v2

| Operación | Ruta | Quién la llama | Visibilidad en el swagger |
| --- | --- | --- | --- |
| `Salud` | `GET /salud` | Prueba del conector; sin token (ruta excluida de Easy Auth) | por defecto |
| `EmitirSasLote` | `POST /sas/lote` | App canvas, PCF. Un adjunto que falla no frena al resto: su error viaja en `items[]` | important |
| `EmitirSasLectura` | `GET /sas/lectura/{tabla}/{id}` | App, PCF. 409 si el documento ya está solo en Content Manager | important |
| `Archivar` | `POST /adjuntos/{tabla}/{id}/archivar` | App, PCF, flujo: diferido que llegó a su evento de negocio, o promoción de un Solo Blob a CM | important |
| `Reanudar` | `POST /adjuntos/{tabla}/{id}/reanudar` | App, PCF: el usuario corrigió un adjunto Observada o en Error | important |
| `IngestarArchivo` | `POST /adjuntos/{tabla}/{id}/ingestar` | Power Automate: el flujo creó la fila y manda el contenido. Tope de 100 MB por acción; la identidad es la de la conexión | important |
| `ExtenderRetencion` | `POST /adjuntos/{tabla}/{id}/retencion` | Administrador documental: nueva fecha (`yyyy-MM-dd`) o sin fecha = permanente. Un tipo inmutable solo admite extender | advanced |
| `DescubrirTipos` | `POST /catalogo/descubrir` | Administrador documental: lee el árbol de tipos de CM y escribe el catálogo, conservando las decisiones humanas | advanced |
| `ActualizarTipos` | `POST /catalogo/actualizar` | Administrador documental: lo mismo, acotado a los tipos en uso | advanced |
| `ValidarTipo` | `GET /catalogo/validar` | Administrador documental: cruza la definición viva con el catálogo; sin tipo valida todos los en uso | advanced |
| `ValidarTabla` | `GET /catalogo/validar-tabla/{tabla}` | Maker, antes de publicar (guía 18) | important |
| `ReanudarConflicto` | `POST /catalogo/conflictos/{id}/reanudar` | Administrador documental: resolvió un conflicto y reencola los adjuntos detenidos | advanced |
| `ConsultarAs400` | `POST /as400/consultas/{nombre}` | Solo consultas declaradas en la configuración de Azure (`AS400_CONSULTAS_JSON`); devuelve la primera fila o 404 | important |

`EmitirSasSubida` y `ResolverCliente` de v1 ya no existen (`EmitirSasSubida` es `EmitirSasLote` de un elemento, ADR-003 §5). Todas pasan por Easy Auth y se autorizan en Dataverse suplantando al usuario; las de administración exigen además el rol CMV8 Administrador documental. `EmitirSasLote` sigue prohibida desde flujos (ADR-003 §4.7): un flujo usa `IngestarArchivo`.

**`uploadUrl` y `tokenStorage` son internos.** En la respuesta de `EmitirSasLote`, los dos campos llevan `x-ms-visibility: internal` (descripción: "nunca persistir ni mostrar"): son detalle de plomería entre el broker y el PCF, no datos para el usuario ni para un flujo. La app los pasa al PCF sin mostrarlos ni guardarlos (verificar en la app que Power Fx los siga exponiendo para pasarlos al componente). En `EmitirSasLectura`, `tokenStorage` no está marcado como interno.

La **política DLP** del entorno decide con qué otros conectores puede combinarse en una misma app: tiene que quedar en el mismo grupo que Dataverse.

## ¿Imprescindible?

**Veredicto: Imprescindible**

- Qué aporta: es el único camino para que la canvas app llame a la Function con la identidad del usuario (y así el broker pueda hacer OBO con ESE usuario). La conexión es por usuario, nunca compartida.
- Qué pasa si no está: la app no tiene cómo pedir las SAS ni el token de Storage; el componente no tiene qué subir. Tampoco hay operaciones de catálogo para el administrador documental ni `IngestarArchivo` para los flujos.
- Qué decisión lo haría prescindible (o distinto): que el componente llame directo al broker con un token propio (`urlBroker` + `tokenBroker`, entradas que el PCF todavía tiene). Exigiría que el PCF obtenga el token del broker con MSAL en el navegador, lo mismo que se descartó en S1b.
- Alternativa y su tradeoff: **Custom API de Dataverse + plugin que llame a la Function**. Se descartó: el plugin llamaría con SU identidad, no con la del usuario; el broker no podría hacer OBO del usuario hacia Storage y se rompe el diseño de dos llaves.
- **Política DLP**: imprescindible si el entorno tiene una política DLP. Un conector personalizado sin clasificar cae en el grupo por defecto (normalmente Non-business) y la app no puede combinarlo con Dataverse (Business). Si el entorno no tiene ninguna política, no hace falta tocar nada, pero un entorno administrado de banca normalmente la tiene.

## Valores del cuaderno

| Variable | Produce / consume | Cómo se obtiene |
| --- | --- | --- |
| `FUNCTION_HOST` | Consume | Salida del Bicep |
| `CONECTOR_APP_ID` | Consume | Guía 02 |
| `BROKER_APP_ID` | Consume | Guía 01 |
| `TENANT_ID` | Consume | Cuaderno |
| `ENV_ID` | Consume | `pac env list` |
| Secreto del conector | Consume, NUNCA se anota | Vault de identidad, `cmv8-conector-secreto` (guía 02) |
| Id del conector | Produce | `pac connector list` (para `pac connector update` en la guía 23) |
| URL de redirección específica | Produce | Pestaña **Security** del conector → se registra en la guía 02 |

## Con comandos

### Crear el conector

El swagger y las propiedades del repo apuntan al sandbox. Se arma una copia **fuera del repo** con los valores del Banco y el secreto del conector, se crea el conector, y se borra la copia.

```bash
T=$(mktemp -d); chmod 700 "$T"
jq --arg h "$FUNCTION_HOST" '.host = $h' dataverse/conector/cmv8-broker.swagger.json > "$T/swagger.json"
jq --arg c "$CONECTOR_APP_ID" --arg t "$TENANT_ID" --arg r "api://$BROKER_APP_ID" \
   --arg s "$(az keyvault secret show --vault-name '<vault de identidad>' --name cmv8-conector-secreto --query value -o tsv)" \
   '.properties.connectionParameters.token.oAuthSettings.clientId = $c
    | .properties.connectionParameters.token.oAuthSettings.clientSecret = $s
    | .properties.connectionParameters.token.oAuthSettings.customParameters.tenantId.value = $t
    | .properties.connectionParameters.token.oAuthSettings.customParameters.resourceUri.value = $r' \
   dataverse/conector/apiProperties.json > "$T/apiProperties.json"

pac connector create \
  --api-definition-file "$T/swagger.json" \
  --api-properties-file "$T/apiProperties.json" \
  --solution-unique-name cmv8ingesta \
  --environment "$ENV_ID"
rm -rf "$T"
pac connector list --environment "$ENV_ID"
```

Ojo: `apiProperties.json` tiene el app id del broker de desarrollo también en `properties.AzureActiveDirectoryResourceId`. El `jq` de arriba solo reemplaza `customParameters.resourceUri` (que es el que usa el runtime al pedir el token); para que la copia no lleve rastros del tenant de desarrollo, agregar al filtro `| .properties.connectionParameters.token.oAuthSettings.properties.AzureActiveDirectoryResourceId = $r` y revisar la copia antes de crear (verificar con `jq` que no quede ningún GUID de desarrollo).

`pac connector create` deriva el nombre interno del `info.title` con prefijo `new_` (no el prefijo del publisher).

Para versionar los valores del Banco sin el secreto, se puede guardar `swagger.json` y un `apiProperties.json` con el marcador en `dataverse/conector/banco-<ambiente>/`; el secreto siempre se inyecta en el momento de crear o actualizar.

### Actualizar un conector existente

Misma copia temporal, y:

```bash
pac connector update --connector-id <id> \
  --api-definition-file "$T/swagger.json" \
  --api-properties-file "$T/apiProperties.json" \
  --environment "$ENV_ID"
```

`pac connector update` sobre el mismo id no cambia la URL de redirección específica.

### URL de redirección específica

En cuanto existe el conector: copiar la **Redirect URL** de la pestaña **Security** (make.powerapps.com → **Custom connectors** → CMV8 Broker → **Security**) y registrarla en la app del conector junto con la global (comando en la guía 02, sección "URL de redirección específica"). Sin eso, la primera conexión falla con `AADSTS50011`.

`apiProperties.json` usa `redirectMode: Global`, pero los conectores OAuth usan una URL por conector de todas formas (Microsoft Learn). El valor recomendado es `GlobalPerConnector`; cambiarlo no evita registrar la URL específica, solo hace explícito lo que ya pasa.

### Política DLP

El conector **CMV8 Broker** va en el grupo **Business** de la política DLP del entorno, junto con Dataverse. Si queda en Non-business o en Blocked, la app no puede usarlo con Dataverse en la misma pantalla. Lo hace un administrador de Power Platform en el centro de administración (pasos en "Con el portal"); también se puede con los comandos de PowerShell de data policies (no se usaron en este proyecto).

## Con el portal

### Crear el conector

Alternativa a `pac connector create`, en **make.powerapps.com** (entorno del ambiente):

1. **Solutions** → `cmv8ingesta` → **New** → **Automation** → **Custom connector** (verificar en el portal la ruta exacta del menú; también se crea desde **Custom connectors** → **New custom connector** → **Import an OpenAPI file**, pero entonces queda fuera de la solución).
2. **Import an OpenAPI file**: la copia de `swagger.json` con el `host` del ambiente. **Connector name**: `CMV8 Broker` → **Continue**.
3. **General**: verificar **Host** = `FUNCTION_HOST` y **Base URL** = `/api`.
4. **Security**: **Authentication type** = **OAuth 2.0**; **Identity provider** = **Azure Active Directory** (Microsoft Entra ID); **Client ID** = `CONECTOR_APP_ID`; **Client secret** = el secreto (pegado desde el vault, sin dejarlo en ningún archivo); **Tenant ID** = `TENANT_ID` (no `common`); **Resource URL** = `api://<BROKER_APP_ID>`; **Scope** = `user_impersonation`; **Enable on-behalf-of login** = `false`.
5. **Create connector** (o **Update connector**). Al guardar, el campo **Redirect URL** queda completo: copiarlo para la guía 02.

### Política DLP

En el **Power Platform admin center**, como administrador de Power Platform:

1. **Security** → **Data and privacy** → **Data policy** (en versiones anteriores del centro, **Policies** → **Data policies**).
2. Elegir la política que aplica al entorno → **Edit policy**.
3. Paso **Prebuilt connectors** / **Custom connectors**: en una política **a nivel de entorno**, el conector personalizado aparece en la lista junto con los prediseñados; seleccionarlo → **Move to Business**. En una política **a nivel de tenant**, los conectores personalizados no aparecen por nombre: se clasifican por patrón de **Host URL** en la página **Custom connectors** → **Add connector pattern** → `https://<FUNCTION_HOST>*` → **Business**, ordenado antes del `*` final.
4. Confirmar que **Microsoft Dataverse** está en **Business** → **Next** hasta **Update policy**.

### Compartir

make.powerapps.com → **Custom connectors** → CMV8 Broker → **Share** → usuarios o grupo de pilotos con el permiso de uso, no de edición (verificar en el portal el nombre exacto del nivel). La conexión la crea cada usuario.

## Verificación

- `pac connector list --environment "$ENV_ID"` muestra el conector (anotar el id).
- Crear una conexión del conector con un usuario piloto desde make.powerapps.com → **Connections** → **New connection** → CMV8 Broker. Entra pide iniciar sesión y, si el consentimiento de administrador de la guía 02 está dado, no pide consentimiento individual.
- Probar la operación `Salud` desde la pestaña **Test** del conector: 200.

## Errores que ya vimos

| Síntoma | Causa | Arreglo |
| --- | --- | --- |
| `pac connector create` falla con `PessimisticConcurrencyConflict` ("Operation on the API is in progress") | Operación previa en curso en el APIM interno. No es un error de la definición | Reintentar a los 5 minutos sin cambiar nada (pasó dos veces en el sandbox) |
| `AADSTS50011` al crear la conexión | Falta la URL de redirección específica del conector en la app del conector | Guía 02 |
| 401 de la Function con token válido | Audiencia o issuer de Easy Auth distintos de los del token | Guía 15 |
| (Documentado, no observado) La app no puede usar el conector junto con Dataverse | El conector quedó en Non-business o Blocked en la DLP | Moverlo a Business (esta guía) |
