# 16. Publicar el código de la Function

> Rol que lo ejecuta: Cloud. Depende de: la Function App del Bicep (guías 03 a 13), 15 (Easy Auth aplicada ANTES), 14 (para que el OBO funcione en la primera SAS). Lo necesitan: 17 (el reinicio que resuelve las referencias de Key Vault), segundo despliegue del Bicep con Event Grid, 20 (el conector llama a estas rutas), 22 (humo). Costo: sin costo propio (la ejecución se paga en el plan Flex Consumption de la guía de la Function App).

## Qué es y qué hace en esta solución

Compila el proyecto Java 21 de `function/` (v2, ADR-003: broker de SAS por lote, operaciones de adjuntos y del catálogo, consultas con nombre al AS400, worker dirigido por el catálogo, timers `Watchdog`, `VigilarCatalogo` y `Depurar`), corre los tests unitarios y sube el paquete a la Function App Flex Consumption.

Lo importante de esta guía no es el comando sino el **orden**:

1. **Easy Auth antes de publicar** (guía 15): así nunca existe una ventana en la que el código del broker esté en internet sin autenticación. Si se publica primero, durante ese intervalo cualquiera puede pedir SAS con un `oid` inventado.
2. **Event Grid después de publicar**: la suscripción de Event Grid valida que el endpoint de destino (`OnScanResult` / `OnBlobCreated`) exista al crearse. Si se crea antes de que el código esté publicado, el despliegue del Bicep falla.

El orden sigue igual que en v1. Lo nuevo es un requisito previo: **la v2 exige, antes del primer arranque, el esquema v2 de Dataverse y las tablas de Storage**.

- **Esquema v2 de Dataverse** (guía 18): choice global `cmv8_estadoingesta`, catálogo (`cmv8_tipodocumento`, `cmv8_propiedadcm`, `cmv8_conflicto`), columnas reservadas en la tabla de adjuntos y roles v2. Sin eso, el broker y el worker no tienen dónde leer el catálogo ni escribir el estado.
- **Azure Tables `ingesta` y `depuracion`** con Table Data Contributor de la identidad, y el contenedor `cmv8-almacenados` (guía 07). Las crea el Bicep; a mano, la guía 07. Sin ellas, `SasLote` no puede escribir el registro operativo y los timers no tienen qué leer.
- Los timers arrancan solos en cuanto el código está publicado: `Depurar` y el Watchdog leen las tablas desde el primer ciclo.

## ¿Imprescindible?

**Veredicto: Imprescindible**

- Qué aporta: sin código no hay broker, ni worker, ni watchdog. Es obvio; lo que no es obvio es el orden de arriba.
- Qué pasa si no está: la Function App existe pero responde 404 a todo.
- Qué decisión lo haría prescindible (o distinto): ninguna. Lo que puede cambiar es **cómo** se publica: hoy desde la estación con Maven; en el Banco, desde un pipeline.
- Alternativa y su tradeoff: publicar desde un pipeline de CI/CD (Azure DevOps o GitHub Actions) con el mismo plugin de Maven y una identidad de despliegue. Ventaja: trazabilidad y sin credenciales en estaciones. Costo: definir el pipeline y su identidad; queda para cuando el Banco defina su ALM. `func azure functionapp publish` no es alternativa: no es el camino soportado para Flex Consumption con Java.

## Valores del cuaderno

| Variable | Produce / consume | Cómo se obtiene |
| --- | --- | --- |
| `FUNCTION_NAME` | Consume | Salida `functionAppName` del Bicep |
| `FUNCTION_HOST` | Consume | Salida `functionAppHostName` del Bicep |
| `RG`, `REGION` | Consume | Cuaderno |

## Con comandos

```bash
cd function
mvn clean package azure-functions:deploy -DfunctionAppName="$FUNCTION_NAME" -DresourceGroup="$RG" -Dregion="$REGION"
cd ..
```

En Flex Consumption se publica con el plugin de Maven; `func azure functionapp publish` no es el camino soportado para este plan. La publicación compila y corre los tests unitarios antes de subir.

### Segundo despliegue: Event Grid

Recién ahora, si se va a usar Event Grid (con o sin Defender), poner `crearSuscripcionEventGrid = true` en `infra/banco-<ambiente>.bicepparam` y repetir el despliegue del Bicep (guías 03 a 13): la suscripción valida que el endpoint `OnScanResult` / `OnBlobCreated` exista.

```bash
bash infra/desplegar.sh "$RG" "$REGION" infra/banco-$AMBIENTE.bicepparam
```

## Con el portal

No aplica: la publicación de una Function Java en Flex Consumption se hace con el plugin de Maven (o desde un pipeline). El portal no compila Java.

Lo que se ve en el portal como resultado (Azure portal → Function App):

1. **Overview** → pestaña de funciones: aparecen `SasLote`, `SasLectura`, `Archivar`, `Reanudar`, `Ingestar`, `ExtenderRetencion`, `DescubrirTipos`, `ActualizarTipos`, `ValidarTipo`, `ValidarTabla`, `ReanudarConflicto`, `ConsultarAs400`, `Salud`, `OnScanResult`, `OnBlobCreated`, `ProcesarIngesta`, `Watchdog`, `VigilarCatalogo` y `Depurar`.
2. **Monitoring** → **Log stream** o Application Insights → **Live metrics**: el arranque del host después de la publicación.

## Verificación

```bash
curl -s -o /dev/null -w '%{http_code}\n' "https://$FUNCTION_HOST/api/salud"                 # 200
curl -s -o /dev/null -w '%{http_code}\n' -X POST "https://$FUNCTION_HOST/api/sas/lote"       # 401
```

Si el 200 tarda en aparecer, esperar un minuto: el host de Flex arranca en frío. `/api/salud` devuelve `{estado:"ok", version}`: comparar la versión con la que se acaba de compilar.

Si el segundo da 200 en vez de 401, Easy Auth no quedó aplicado: volver a la guía 15.

## Errores que ya vimos

| Síntoma | Causa | Arreglo |
| --- | --- | --- |
| `/api/salud` no responde 200 en el primer minuto | Arranque en frío del host de Flex | Esperar un minuto y repetir |
| `POST /api/sas/lote` sin token devuelve 200 | Easy Auth no aplicado | Guía 15 |
| (Anticipado) `SasLote` responde 500 y en App Insights hay 403 o 404 contra `*.table.core.windows.net` | Faltan las tablas `ingesta`/`depuracion` o el rol Table Data Contributor de la identidad | Guía 07, "Tablas" y "Roles v2" |
| (Anticipado) `SasLote` devuelve error por adjunto aunque el usuario tenga rol | La tabla de adjuntos no tiene las columnas reservadas v2 o el esquema v2 no se aplicó | Guía 18 y `ValidarTabla` |
| El despliegue del Bicep con `crearSuscripcionEventGrid = true` falla al crear la suscripción | Se corrió antes de publicar el código: el endpoint de destino no existe | Publicar primero (esta guía) y repetir el despliegue |
