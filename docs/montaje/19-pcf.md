# 19. Componente PCF `CargaAdjuntoBlob`

> Rol que lo ejecuta: Power Platform (desarrollador con administrador del entorno). Depende de: 18 (característica de componentes de código para canvas habilitada, publisher `cmv8`), salidas del Bicep (guías 03 a 13: `functionAppHostName` y `storageAdjuntosName` para el manifiesto). Lo necesitan: 21 (la app inserta el componente). Costo: sin costo propio; el componente declara `external-service-usage`, lo que lo vuelve **premium** (licencia Power Apps Premium por usuario, ya confirmada).

## Qué es y qué hace en esta solución

Componente de código para canvas apps (namespace `Banco`, versión 0.0.5) que sube el archivo **directo desde el navegador a Blob Storage**, sin pasar por Power Automate ni por un conector. Recibe de la app la SAS (`uploadUrl`) y el token de Storage del usuario (`tokenStorage`), corta el archivo en bloques (`bloqueBytes`, 4 MiB por defecto), sube hasta 4 en paralelo con Put Block y cierra con Put Block List, mandando **ambas llaves en cada PUT**: la SAS en la URL y el token como `Authorization: Bearer`, con `x-ms-version: 2025-07-05`. Reintenta 3 veces por bloque ante error de red, 5xx o 429; ante 403 corta. Expone `estado`, `progreso`, `rutaBlob`, `mensajeError` y métricas como salidas (`pcf/README.md`).

Power Apps bloquea cualquier llamada desde un componente a un dominio que no esté declarado en `external-service-usage` del manifiesto; por eso, antes de compilar, el manifiesto tiene que declarar los dominios reales del ambiente.

## ¿Imprescindible?

**Veredicto: Imprescindible**

- Qué aporta: es la única forma, dentro de una canvas app, de subir 100 MB en bloques con SAS + bearer desde el navegador. El control de adjuntos nativo de canvas no puede hacer Put Block ni mandar las dos llaves.
- Qué pasa si no está: no hay carga directa a Blob. El binario tendría que pasar por un conector o por Dataverse, que es justo lo que ADR-002 eliminó.
- Qué decisión lo haría prescindible (o distinto): abandonar canvas como cliente de carga (por ejemplo, una página web propia fuera de Power Platform), o bajar el requisito de tamaño por debajo de 50 MB.
- Alternativa y su tradeoff: ninguna viable dentro de canvas sin PCF. El conector de Azure Blob Storage desde Power Fx llega a 50 MB sin chunking (el chunking es de flows); Power Automate no mueve 100 MB de forma viable y conservaría el consumo de requests; subir a Dataverse y que la Function lo mueva conserva el costo de capacidad File que motivó el ADR (ADR-002, «Cómo sube el archivo: opciones evaluadas»).

## Valores del cuaderno

| Variable | Produce / consume | Cómo se obtiene |
| --- | --- | --- |
| `FUNCTION_HOST` | Consume | Salida `functionAppHostName` del Bicep |
| `STORAGE_ADJ` | Consume | Salida `storageAdjuntosName` del Bicep |
| `ENV_ID` | Consume | `pac env list` |

## Con comandos

### Dominios del manifiesto

```bash
python3 - "$FUNCTION_HOST" "$STORAGE_ADJ" <<'EOF'
import re, sys, pathlib
p = pathlib.Path("pcf/CargaAdjuntoBlob/ControlManifest.Input.xml")
x = p.read_text()
x = re.sub(r"<domain>[^<]*azurewebsites\.net</domain>", f"<domain>{sys.argv[1]}</domain>", x)
x = re.sub(r"<domain>[^<]*blob\.core\.windows\.net</domain>", f"<domain>{sys.argv[2]}.blob.core.windows.net</domain>", x)
p.write_text(x)
EOF
rg -n '<domain>' pcf/CargaAdjuntoBlob/ControlManifest.Input.xml
```

Se deben ver exactamente dos líneas: el hostname de la Function y `<cuenta>.blob.core.windows.net`. Este cambio modifica un archivo versionado: no commitear los dominios de un ambiente sobre los de otro sin decidirlo (o mantener una rama o copia por ambiente).

### Compilar y subir

```bash
cd pcf
npm ci
npm run build -- --buildMode production
pac auth create --environment "$ENV_ID"
pac pcf push --publisher-prefix cmv8 --environment "$ENV_ID"
cd ..
```

`pac pcf push` sube el control en una solución temporal (`PowerAppsTools_cmv8`); para el ALM del Banco el control se agrega a la solución `cmv8ingesta` desde el diseñador de soluciones o se exporta con `pac solution` cuando esté todo adentro. pac 2.11 acepta también `--solution-unique-name cmv8ingesta` en `pac pcf push`, que agrega el componente directamente a esa solución (verificado en `pac pcf push help`; no probado en este proyecto).

El namespace del control es `Banco` (manifiesto); si el Banco tiene una convención de namespaces, cambiarlo ANTES del primer push, porque después el control ya existe con ese nombre.

### Versión nueva

Subir `version` en el manifiesto (`<control … version="0.0.6">`) y repetir esta guía; la app toma la versión nueva al abrirla en Studio (guía 23).

## Con el portal

No aplica para compilar: el componente se construye con node y se sube con `pac`. Alternativa al push sin `pac` en el entorno destino: empaquetar el componente en una solución (`pcf/README.md`, "Empaquetar en una solución") e importarla en make.powerapps.com → **Solutions** → **Import solution**.

Lo que se ve en el portal como resultado:

1. make.powerapps.com → **Solutions** → `PowerAppsTools_cmv8` (o `cmv8ingesta`) → componente `Banco.CargaAdjuntoBlob`.
2. Para pasarlo a `cmv8ingesta` desde el portal: abrir `cmv8ingesta` → **Add existing** → **More** → **Developer** → **Custom control** → `Banco.CargaAdjuntoBlob` (verificar en el portal la ruta exacta del menú).
3. En Studio: **Insert** → **Get more components** → pestaña **Code** → aparece `CargaAdjuntoBlob`.

## Verificación

`pac pcf push` termina con el control importado y publicado; en Studio, Insertar → Obtener más componentes → Código muestra `CargaAdjuntoBlob`.

## Errores que ya vimos

| Síntoma | Causa | Arreglo |
| --- | --- | --- |
| El componente no aparece o no se puede importar | Falta **Allow publishing of canvas apps with code components** en el entorno | Guía 18 |
| (Documentado, no observado) La carga falla en el navegador sin llegar a Storage (llamada bloqueada) | El dominio de la Function o de la cuenta no está en `external-service-usage` | Repetir el reemplazo de dominios y el push |
| 403 `AuthenticationFailed` en Put Block | Falta el bearer de Storage junto con la SAS ligada (`tokenStorage` vacío) | Cableado de la app, guía 21 |
| 403 `AuthorizationPermissionMismatch` al subir | Faltó el bearer junto con la SAS, o el usuario tiene un rol de datos en el contenedor que no debería | Guía 21 y roles del contenedor (guías de infraestructura) |
