# 14. Credencial federada del broker

> Rol que lo ejecuta: Identidad. Depende de: 01 (object id del broker) y del despliegue con Bicep (guías 03 a 13: `identidadPrincipalId` de la identidad administrada). Lo necesitan: 16 (la primera SAS que emite la Function hace OBO con esta credencial). Costo: sin costo propio.

## Qué es y qué hace en esta solución

La app del broker confía en la identidad administrada (asignada por el usuario) de la Function: el OBO se firma con un token de esa identidad y no hay secreto que rotar.

Mecánica (Microsoft Entra, *Configure an application to trust a managed identity*): la Function pide a Entra un token de su identidad administrada con audiencia `api://AzureADTokenExchange`, y lo presenta como `client_assertion` al pedir el token OBO como app del broker. Entra comprueba que el `issuer` y el `subject` de ese token coincidan con los de la credencial federada configurada en la app; si coinciden, emite el token de Storage del usuario. En el código (`backlog-tecnico.md` §6a) es `ClientCredentialFactory.createFromClientAssertion(...)` de MSAL4J; el proveedor de aserción se llama en cada adquisición, así que la aserción nunca vence en caché.

## ¿Imprescindible?

**Veredicto: Imprescindible** (para cumplir "cero secretos en el broker")

- Qué aporta: el broker es un cliente confidencial (lo exige el OBO) sin ningún secreto ni certificado. Es el orden de preferencia que da Microsoft para credenciales de cliente confidencial: identidad administrada, luego federación, luego certificado; secreto solo para desarrollo.
- Qué pasa si no está: el OBO falla (la app del broker no tiene con qué autenticarse), la Function no puede obtener el token de Storage del usuario y toda emisión de SAS ligada falla.
- Qué decisión lo haría prescindible (o distinto): aceptar un client secret en la app del broker. La Function lo soporta (`BROKER_CLIENT_SECRET`, hoy reservado a desarrollo local, ver `function/README.md`).
- Alternativa y su tradeoff: **client secret en el broker**. Ventaja: no depende de la identidad administrada (sirve para correr el broker fuera de Azure). Costo: un segundo secreto que custodiar (además del del conector), rotación manual cada 6 meses con su ventana de corte, y un valor que vive en un app setting o en el vault. Se eligió la credencial federada porque elimina todo eso a cambio de un paso de configuración único. Certificado: misma necesidad de rotación, más gestión.

## Valores del cuaderno

| Variable | Produce / consume | Cómo se obtiene |
| --- | --- | --- |
| `TENANT_ID` | Consume | Cuaderno |
| `BROKER_OBJ_ID` | Consume | Guía 01 (object id de la **app**, no del service principal) |
| `MI_PRINCIPAL_ID` | Consume | Salida `identidadPrincipalId` del Bicep (guías 03 a 13). Es el **principal id** (object id del service principal) de la identidad, NO su client id |

## Con comandos

```bash
cat > /tmp/fc.json <<EOF
{
  "name": "uami-cmv8-broker",
  "issuer": "https://login.microsoftonline.com/$TENANT_ID/v2.0",
  "subject": "$MI_PRINCIPAL_ID",
  "audiences": ["api://AzureADTokenExchange"]
}
EOF
az ad app federated-credential create --id "$BROKER_OBJ_ID" --parameters /tmp/fc.json -o none
az ad app federated-credential list --id "$BROKER_OBJ_ID" --query '[].{name:name, subject:subject}'
```

Campo por campo:

- `issuer`: la autoridad v2 del tenant. Sin espacios al principio ni al final (Entra bloquea el intercambio si los hay).
- `subject`: el **principal id** de la identidad administrada, sensible a mayúsculas. Si se confunde con el client id, el OBO falla con `AADSTS700211` o `AADSTS70021` al primer `POST /api/sas/subida`.
- `audiences`: `api://AzureADTokenExchange` (nube pública). Es un valor fijo de Entra, no tiene relación con el `api://` del broker.

La identidad administrada y la app deben estar en el mismo tenant. Crear la credencial requiere Application Administrator, Application Developer, Cloud Application Administrator o ser owner de la app. Una app admite hasta 20 credenciales federadas.

## Con el portal

En el **Microsoft Entra admin center** (https://entra.microsoft.com):

1. **Entra ID** → **App registrations** → `CMV8 Broker (Function)`.
2. **Manage** → **Certificates & secrets** → pestaña **Federated credentials** → **Add credential**.
3. **Federated credential scenario**: **Managed Identity**.
4. **Issuer**: viene completado con `https://login.microsoftonline.com/<tenant>/v2.0`; no cambiarlo.
5. **Select managed identity**: elegir la identidad administrada que creó el Bicep (`cmv8-mi-…`, en el grupo de recursos del ambiente). El portal completa el subject con su principal id: así se evita el error de confundir client id con principal id.
6. **Name**: `uami-cmv8-broker`. **Description** (opcional): "La Function firma el OBO con su identidad administrada".
7. **Audience**: `api://AzureADTokenExchange` (valor por defecto para la nube pública) → **Add**.

## Verificación

`az ad app federated-credential list --id "$BROKER_OBJ_ID"` muestra `uami-cmv8-broker` con `subject` igual a `MI_PRINCIPAL_ID`. Comparar a mano:

```bash
az ad app federated-credential list --id "$BROKER_OBJ_ID" --query '[0].subject' -o tsv
echo "$MI_PRINCIPAL_ID"
```

La prueba funcional llega con la guía 16 y el humo (guía 22): el primer `POST /api/sas/subida` con un usuario autorizado devuelve `tokenStorage`, y en App Insights aparece la traza `OBO del broker con la identidad administrada`.

## Errores que ya vimos

| Síntoma | Causa | Arreglo |
| --- | --- | --- |
| `AADSTS700211` / `AADSTS70021` en el OBO, al primer `POST /api/sas/subida` | Credencial federada con `subject` equivocado (se puso el client id de la identidad, o la identidad de otro ambiente) | Borrar la credencial y crearla con `MI_PRINCIPAL_ID` |
