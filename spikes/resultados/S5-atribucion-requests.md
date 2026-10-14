# S5. Atribución de requests de Dataverse del broker con impersonación

- Fecha de la carga: 2026-10-14, 07:34 UTC
- Estado: **en curso**. La carga ya se generó; la lectura del reporte se hace al día siguiente.

## Hipótesis

Las llamadas que el broker hace a Dataverse con el encabezado `CallerObjectId` (impersonación) se atribuyen al **usuario de aplicación** de la Function (pool de requests no licenciados del tenant), no al usuario final. Si se atribuyen al usuario final, el consumo sale de su cuota de 40.000/día y el pool queda libre.

## Carga generada

- 50 llamadas a `POST /api/sas/subida` con el token del admin (oid `eab4619b-…`) sobre la fila `1a39931e-…`, todas 200.
- Por cada llamada el broker hace contra Dataverse, impersonando al admin: consulta del `systemuserid` por oid, `RetrievePrincipalAccess`, `GET` de la fila y `PATCH` de `cmv8_rutablob`. Estimado: 150 a 200 requests de Dataverse en total.

## Cómo leer el resultado (día siguiente, entre 24 y 48 h después)

1. https://admin.powerplatform.microsoft.com → Recursos → Capacidad → pestaña **Complementos** / **Resumen de solicitudes de Power Platform** (o Analytics → Dataverse → **API calls** por usuario).
2. Buscar el 14 de octubre: cuántas requests aparecen atribuidas a **CMV8 Function** (usuario de aplicación) y cuántas a **admin**.
3. Veredicto:
   - Si ~150 a 200 cuentan para CMV8 Function → la impersonación consume el pool del tenant. Preferir OBO en el Banco si el tenant no tiene Dynamics 365 (pool de 25.000/día).
   - Si cuentan para admin → la impersonación no consume el pool. Se mantiene impersonación, que es más simple que OBO.

## Resultado

Pendiente de lectura.
