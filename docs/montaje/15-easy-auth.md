# 15. Easy Auth en la Function

> Rol que lo ejecuta: Cloud. Depende de: 01 (`BROKER_APP_ID`), la Function App creada por el Bicep (guías 03 a 13: `FUNCTION_NAME`, `RG`). Lo necesitan: 16 (no se publica código antes de esta guía), 20 (el conector manda el token que esto valida). Costo: sin costo propio (es una función de la plataforma App Service).

## Qué es y qué hace en esta solución

La Function no valida tokens en código: la autenticación integrada de App Service ("Easy Auth") valida el token de Entra antes de que la request llegue a Java e inyecta `X-MS-CLIENT-PRINCIPAL-*`. La Function lee la identidad del llamador de esos encabezados (`LectorLlamador`). Sin este paso la Function está abierta y el `oid` es declarativo: cualquiera puede fabricar un token con otro `oid`, porque el código solo lo reenvía sin validar la firma (`function/README.md`, "Autenticación de los endpoints HTTP").

**No publicar código con datos reales antes de este paso.**

## ¿Imprescindible?

**Veredicto: Imprescindible**

- Qué aporta: valida firma, issuer, audiencia y vencimiento del token antes de que la request toque el código, y responde 401 a lo que no trae un token válido. Es la frontera de autenticación de la Function.
- Qué pasa si no está: la Function queda abierta a internet. Cualquiera que conozca la URL puede pedir una SAS con un `oid` inventado; la suplantación contra Dataverse se haría con un usuario que nadie autenticó.
- Qué decisión lo haría prescindible (o distinto): validar el JWT en el código Java (descargar las claves de firma de Entra, validar issuer, audiencia, vencimiento y firma).
- Alternativa y su tradeoff: **validación del JWT en código**. Ventaja: no depende de App Service y se prueba con tests unitarios. Costo: más código propio en el camino crítico de seguridad, más superficie de error (rotación de claves, relojes, versiones de token) y una dependencia más que mantener. Se descartó: Easy Auth lo hace la plataforma, sin código y sin credencial.

## Valores del cuaderno

| Variable | Produce / consume | Cómo se obtiene |
| --- | --- | --- |
| `TENANT_ID` | Consume | Cuaderno |
| `BROKER_APP_ID` | Consume | Guía 01 |
| `RG` | Consume | Cuaderno |
| `FUNCTION_NAME` | Consume | Salida `functionAppName` del Bicep |
| `SUB` | Produce (local) | `az account show --query id -o tsv` |

## Con comandos

```bash
SUB=$(az account show --query id -o tsv)
cat > /tmp/easyauth.json <<EOF
{
  "properties": {
    "platform": { "enabled": true, "runtimeVersion": "~1" },
    "globalValidation": {
      "requireAuthentication": true,
      "unauthenticatedClientAction": "Return401",
      "excludedPaths": ["/runtime/webhooks/eventgrid", "/api/salud"]
    },
    "identityProviders": {
      "azureActiveDirectory": {
        "enabled": true,
        "registration": {
          "clientId": "$BROKER_APP_ID",
          "openIdIssuer": "https://login.microsoftonline.com/$TENANT_ID/v2.0"
        },
        "validation": {
          "allowedAudiences": ["api://$BROKER_APP_ID", "$BROKER_APP_ID"]
        },
        "login": { "disableWWWAuthenticate": false }
      }
    },
    "login": { "tokenStore": { "enabled": true } }
  }
}
EOF
az rest --method PUT \
  --url "https://management.azure.com/subscriptions/$SUB/resourceGroups/$RG/providers/Microsoft.Web/sites/$FUNCTION_NAME/config/authsettingsV2?api-version=2023-12-01" \
  --body @/tmp/easyauth.json -o none
```

El grupo `az functionapp auth` necesita la extensión `authV2` de la CLI; sin ella se configura con `az rest --method PUT` sobre la URL de `authsettingsV2`, como arriba.

### Por qué cada campo

| Campo | Valor | Por qué |
| --- | --- | --- |
| `platform.enabled` | `true` | Enciende Easy Auth |
| `globalValidation.requireAuthentication` | `true` | Ninguna request llega al código sin token válido (salvo las rutas excluidas) |
| `globalValidation.unauthenticatedClientAction` | `Return401` | Es una API: responde 401, no redirige a una página de inicio de sesión (302) |
| `globalValidation.excludedPaths` | `/runtime/webhooks/eventgrid` | Event Grid entrega con la clave de sistema del host, no con Entra |
| | `/api/salud` | El monitoreo y el despliegue pueden verificar sin token (guía 16) |
| `registration.clientId` | `BROKER_APP_ID` | La app de la guía 01 es la audiencia |
| `registration.openIdIssuer` | `…/<tenant>/v2.0` | Issuer **v2**, porque la app pide tokens v2 (`requestedAccessTokenVersion: 2`, guía 01) |
| `validation.allowedAudiences` | `api://<appId>` y `<appId>` | Dos audiencias porque un token v2 trae el GUID pelado en `aud`; el `api://` cubre a quien pida el token por el Application ID URI |
| `login.disableWWWAuthenticate` | `false` | El 401 lleva `WWW-Authenticate: Bearer …`, útil para diagnosticar |
| `login.tokenStore.enabled` | `true` | Valor por defecto de la plataforma |

Easy Auth no necesita credencial de la app: solo valida firmas. No hay `clientSecretSettingName`.

No se configura `defaultAuthorizationPolicy` (aplicaciones cliente permitidas): cualquier cliente del tenant con un token para el broker pasa. Quién puede pedir ese token lo controlan el consentimiento (solo el conector y, opcional, Azure CLI) y Dataverse decide qué puede hacer cada usuario.

## Con el portal

En el **Azure portal** (https://portal.azure.com):

1. Abrir la Function App (`FUNCTION_NAME`) → **Settings** → **Authentication** → **Add identity provider**.
2. **Identity provider**: **Microsoft**. **Tenant type**: **Workforce configuration (current tenant)**.
3. **App registration type**: **Pick an existing app registration in this directory** (no crear una nueva) → elegir `CMV8 Broker (Function)`. Si el portal ofrece un **Client secret**, dejarlo vacío: Easy Auth solo valida tokens (verificar en el portal si el campo es opcional en esta pantalla).
4. **Issuer URL**: `https://login.microsoftonline.com/<TENANT_ID>/v2.0`.
5. **Allowed token audiences**: agregar `api://<BROKER_APP_ID>` (el client id ya se acepta por defecto, según Learn; agregarlo igual es inocuo y deja la configuración igual a la del comando).
6. **Additional checks**:
   - **Client application requirement**: **Allow requests from any application** o, más estricto, **Allow requests from specific client applications** con el app id del conector (y Azure CLI si se pre-autorizó). **No** dejar la opción por defecto *Allow requests only from this application itself*: rechazaría los tokens del conector, cuyo cliente es otra app.
   - **Identity requirement**: **Allow requests from any identity**.
   - **Tenant requirement**: **Allow requests only from the issuer tenant**.
7. **App Service authentication settings**: **Restrict access** = **Require authentication**; **Unauthenticated requests** = **HTTP 401 Unauthorized: recommended for APIs**; **Token store** marcado.
8. **Add**.
9. Rutas excluidas: `/runtime/webhooks/eventgrid` y `/api/salud`. El portal muestra una sección **Excluded paths** al configurar la autenticación (verificar en el portal dónde queda en la versión actual); si no aparece, aplicarlas con el `az rest --method PUT` de arriba, que deja todo el resto igual.

## Verificación

GET sobre la misma URL:

```bash
az rest --method GET \
  --url "https://management.azure.com/subscriptions/$SUB/resourceGroups/$RG/providers/Microsoft.Web/sites/$FUNCTION_NAME/config/authsettingsV2?api-version=2023-12-01" \
  --query '{req:properties.globalValidation.requireAuthentication, accion:properties.globalValidation.unauthenticatedClientAction, excluidas:properties.globalValidation.excludedPaths, issuer:properties.identityProviders.azureActiveDirectory.registration.openIdIssuer, aud:properties.identityProviders.azureActiveDirectory.validation.allowedAudiences}'
```

Se espera: `requireAuthentication: true`, `unauthenticatedClientAction: Return401`, las dos rutas excluidas, el issuer v2 y las dos audiencias.

La prueba de comportamiento llega con la guía 16: `/api/salud` → 200 y `POST /api/sas/subida` sin token → 401.

## Errores que ya vimos

| Síntoma | Causa | Arreglo |
| --- | --- | --- |
| 401 de la Function con token válido | Audiencia o issuer de Easy Auth distintos de los del token (v1 contra v2, GUID contra `api://`) | Revisar `openIdIssuer` (v2) y las dos `allowedAudiences`; revisar `requestedAccessTokenVersion: 2` en la guía 01 |
| `POST /api/sas/subida` sin token devuelve 200 en vez de 401 | Easy Auth no quedó aplicado | Repetir el PUT de esta guía y verificar con el GET |
| (No observado, esperable) La suscripción de Event Grid no valida el endpoint | `/runtime/webhooks/eventgrid` no está en `excludedPaths` | Agregarlo y repetir el PUT |
