# S5. Atribución de requests de Dataverse del broker con impersonación

- Fecha de la carga: 2026-10-14, 07:34 UTC
- Estado: **en curso**. La carga ya se generó; la lectura del reporte se hace al día siguiente.

## Hipótesis

Las llamadas que el broker hace a Dataverse con el encabezado `CallerObjectId` (impersonación) se atribuyen al **usuario de aplicación** de la Function (pool de requests no licenciados del tenant), no al usuario final. Si se atribuyen al usuario final, el consumo sale de su cuota de 40.000/día y el pool queda libre.

## Carga generada

- 50 llamadas a `POST /api/sas/subida` con el token del admin (oid `eab4619b-…`) sobre la fila `1a39931e-…`, todas 200.
- Por cada llamada el broker hace contra Dataverse, impersonando al admin: consulta del `systemuserid` por oid, `RetrievePrincipalAccess`, `GET` de la fila y `PATCH` de `cmv8_rutablob`. Estimado: 150 a 200 requests de Dataverse en total.

## Cómo leer el resultado (día siguiente, entre 24 y 48 h después)

**Corrección (2026-10-14, verificada en Microsoft Learn):** el reporte descargable de *Microsoft Power Platform requests* del admin center
(Licencias → Complementos de capacidad → Resumen → Descargar informes) **solo incluye requests de Power Automate**. La documentación dice textual:
"API requests from Dataverse, Microsoft Copilot Studio, and Power Apps aren't included at this time", y además la aplicación de los límites
no empieza hasta al menos seis meses después de que ese reporte salga de vista previa
(https://learn.microsoft.com/power-platform/admin/api-request-limits-allocations#frequently-asked-questions).
Por lo tanto la atribución *de licenciamiento* de las requests de Dataverse hoy **no se puede leer en ningún reporte**.

Lo que sí se puede leer es a qué identidad le cuenta Dataverse las **operaciones** (Retrieve, RetrieveMultiple, Update) en el análisis
de Dataverse, que se actualiza cada 24 h:

1. https://admin.powerplatform.microsoft.com → **Administrar** → Productos → **Dataverse** → análisis del entorno `SANIC-ESTUDIO-DEV`.
2. Panel **Usuarios activos** → *Usuarios más activos que realizan operaciones*, rango que incluya el 14 de octubre.
3. Seleccionar al usuario para ver *Número de lecturas por entidad* y *Número de actualizaciones por entidad*. Buscar `cmv8_solicitudingesta`
   y `systemuser`: la carga dejó unas 50 actualizaciones y entre 100 y 150 lecturas.
4. Veredicto:
   - Si aparecen bajo **admin** → Dataverse ejecuta y contabiliza las operaciones impersonadas como del usuario final (contexto de ejecución = impersonado).
   - Si aparecen bajo **CMV8 Function** (usuario de aplicación) → las contabiliza como del impersonador.
   - Lo que esto NO prueba: a qué pool le descontaría el medidor de requests de licenciamiento, porque ese medidor no se reporta ni se aplica
     para Dataverse todavía. Queda como riesgo documentado, no como decisión a tomar hoy: impersonación (más simple que OBO) se mantiene,
     y se revisa cuando Microsoft publique el reporte de requests de Dataverse.

## Resultado

Pendiente de lectura.
