# 19. Componentes PCF: `CargaAdjuntoBlob` (canvas) y `AdjuntosGrilla` (model-driven)

> Rol que lo ejecuta: Power Platform (desarrollador con administrador del entorno). Depende de: 18 (característica de componentes de código para canvas habilitada, publisher `cmv8`), salida del Bicep `storageAdjuntosName` (guías 03 a 13) para el dominio del manifiesto; para model-driven, además, 24 (app registration «CMV8 PCF»). Lo necesitan: 21 (la app canvas inserta `CargaAdjuntoBlob`) y los formularios model-driven de cada solución del maker (`AdjuntosGrilla`). Costo: sin costo propio; los componentes declaran `external-service-usage`, lo que los vuelve **premium** (licencia Power Apps Premium por usuario, ya confirmada).


> **Empaquetado con dos controles (2026-10-20)**: `pac pcf push` rechaza un proyecto con más de un manifiesto ("Found more than one project source file named ControlManifest.Input.xml"). Desde que existe `AdjuntosGrilla`, los dos controles se publican como solución: `pcf/cmv8controles/` es un proyecto de solución (`pac solution init --publisher-name banco_cmv8 --publisher-prefix cmv8` + `pac solution add-reference --path ..`); `dotnet build` en esa carpeta genera `bin/Debug/cmv8controles.zip` con los dos controles y `pac solution import --path bin/Debug/cmv8controles.zip --publish-changes` los deja en el entorno (`cmv8_Banco.CargaAdjuntoBlob` y `cmv8_Banco.AdjuntosGrilla`, verificado en desarrollo). En el Banco, ese zip es el artefacto que viaja por el pipeline.

## Qué es y qué hace en esta solución

### `CargaAdjuntoBlob` 1.0.0 (canvas)

Componente de código para canvas apps (namespace `Banco`, versión 1.0.0, v2 de ADR-003 §4.1) que arma una **cola de archivos** y los sube **directo desde el navegador a Blob Storage**, sin pasar por Power Automate ni por un conector. Referencia completa: `pcf/CargaAdjuntoBlob/README.md`.

- **Cola multi-archivo.** El usuario arrastra o elige varios archivos; cada uno se valida al instante (lote lleno, tipo, tamaño, vacío, duplicado por nombre y tamaño) y los rechazados quedan en la lista como «No se agregó» con el motivo.
- **Lotes.** «Subir todo» publica un **pedido**: la salida `pendientes` con `[{indice, id, nombreArchivo, tamanoBytes, contentType}]`, un `numeroPedido` nuevo y `estado = "listo-para-sas"`. La app atiende el pedido (crea las filas, llama `EmitirSasLote` por el conector) y devuelve la respuesta en la propiedad `lote`. **El control no llama al broker**: en canvas solo habla con Storage.
- **Subida.** Empareja cada item del lote con su archivo, descarta el texto del lote en el acto (no lo guarda) y sube hasta `paralelismoArchivos` archivos a la vez, cada uno en bloques de `bloqueBytes` con `paralelismoBloques` bloques en vuelo, con **las dos llaves en cada PUT** de ADR-002: la SAS en la URL y el `tokenStorage` del usuario (uno para todo el lote) como `Authorization: Bearer`.
- **Resultados.** Cada archivo que termina aparece en la salida `resultados` (`subido`, `error` o `cancelado`, con su `id`); la app marca Subido. Un archivo fallido se reintenta con un lote de uno (el broker acepta filas en Creada o Error).
- **Aspecto desde el diseño.** Las tres variantes (`fluent`, `institucional`, `moderno`), la densidad y el modo de color son propiedades del control. Los **18 tokens marcados `maker`** en `docs/diseno/anatomia-y-tokens/tokens.json` son propiedades de entrada (`PrimaryColor`, `FontFamily`, `BorderRadius`, etc.): vacío = valor de la variante. Los **textos** salen de `docs/diseno/anatomia-y-tokens/textos.es.json` como recursos `.resx` (3082 español; 1033 con los mismos textos marcados para traducir).

El protocolo con la app (qué hace la app en cada `OnChange`) está en la guía 21; las reglas de seguridad, en `docs/checklist-construccion-app.md`.

### El manifiesto se genera, no se edita

`ControlManifest.Input.xml`, `generated/ManifestTypes.d.ts`, los `.resx`, `tokens.generado.ts`, `textos.generado.ts` y `vista/iconos.generado.ts` los escribe `pcf/CargaAdjuntoBlob/herramientas/generar_desde_diseno.py` a partir de `tokens.json`, `textos.es.json`, los íconos del prototipo y la tabla `PROPIEDADES` del propio generador. **Cualquier cambio de propiedades, textos, tokens, versión o dominio se hace en la fuente y se regenera**; un cambio a mano en el manifiesto se pisa en la próxima generación.

`external-service-usage` declara solo el dominio de la cuenta de Storage de adjuntos (`<cuenta>.blob.core.windows.net`): en canvas el control no llama a la Function. Según el comentario del manifiesto, la declaración está por licenciamiento (componente premium).

### `AdjuntosGrilla` (model-driven)

Para apps model-driven existe un control de **dataset** propio, `Banco.AdjuntosGrilla`, que se liga a la **subcuadrícula de adjuntos** del formulario del registro padre (ADR-003 §4.6). A diferencia del de canvas, orquesta solo: adquiere su propio token con MSAL Browser contra la app registration «CMV8 PCF» (guía 24), llama al broker directo, recibe las dos llaves, sube, y crea y actualiza las filas con la Web API del componente con la identidad del usuario. El broker no cambia. Sus propiedades, su compilación y su configuración están en [`pcf/AdjuntosGrilla/README.md`](../../pcf/AdjuntosGrilla/README.md); esta guía no las repite.

## ¿Imprescindible?

### `CargaAdjuntoBlob`

**Veredicto: Imprescindible** (si el cliente de carga es canvas)

- Qué aporta: es la única forma, dentro de una canvas app, de subir archivos de 100 MB o más en bloques con SAS + bearer desde el navegador, varios por vez. El control de adjuntos nativo de canvas no puede hacer Put Block ni mandar las dos llaves.
- Qué pasa si no está: no hay carga directa a Blob desde canvas. El binario tendría que pasar por un conector o por Dataverse, que es justo lo que ADR-002 eliminó.
- Qué decisión lo haría prescindible (o distinto): que el Banco use solo model-driven (entonces alcanza `AdjuntosGrilla`), abandonar Power Platform como cliente de carga (una página web propia), o bajar el requisito de tamaño por debajo de 50 MB.
- Alternativa y su tradeoff: ninguna viable dentro de canvas sin PCF. El conector de Azure Blob Storage desde Power Fx llega a 50 MB sin chunking (el chunking es de flows); `IngestarArchivo` desde Power Automate existe en v2 pero pasa los bytes por la Function, con tope de 100 MB por acción y la cuenta de la conexión como actor (ADR-003 §4.7 y §9, desvío 1); subir a Dataverse conserva el costo de capacidad File que motivó ADR-002.

### `AdjuntosGrilla`

**Veredicto: Imprescindible solo si el Banco adjunta desde apps model-driven**

- Qué aporta: el mismo flujo de subida dentro del formulario del padre, sin pantalla canvas ni página personalizada.
- Qué pasa si no está: en model-driven solo queda embeber una página personalizada con `CargaAdjuntoBlob` o mandar al usuario a una app canvas.
- Qué decisión lo haría prescindible: que todas las soluciones del Banco usen canvas.
- Alternativa y su tradeoff: si el entorno bloquea popups e iframes y MSAL no puede obtener el token, Custom API + plugin con identidad administrada como segundo llamador del broker; pierde `sduoid` (ADR-003 §4.6). Exige además aceptar una app registration de cliente público (ADR-003 §9, desvío 2; decisión del Banco, §11).

## Valores del cuaderno

| Variable | Produce / consume | Cómo se obtiene |
| --- | --- | --- |
| `STORAGE_ADJ` | Consume | Salida `storageAdjuntosName` del Bicep |
| `ENV_ID` | Consume | `pac env list` |
| App registration «CMV8 PCF» | Consume (solo model-driven) | Guía 24 |

## Con comandos

### Dominio del manifiesto

El dominio vive en el generador, no en el manifiesto. Se reemplaza ahí y se regenera, desde la raíz del repo:

```bash
python3 - "$STORAGE_ADJ" <<'EOF'
import re, sys, pathlib
p = pathlib.Path("pcf/CargaAdjuntoBlob/herramientas/generar_desde_diseno.py")
x = p.read_text()
x = re.sub(r"<domain>[^<]*blob\.core\.windows\.net</domain>", f"<domain>{sys.argv[1]}.blob.core.windows.net</domain>", x)
p.write_text(x)
EOF
python3 pcf/CargaAdjuntoBlob/herramientas/generar_desde_diseno.py
rg -n '<domain>' pcf/CargaAdjuntoBlob/ControlManifest.Input.xml
```

Se debe ver exactamente una línea: `<cuenta>.blob.core.windows.net`. Este cambio modifica archivos versionados: no commitear el dominio de un ambiente sobre el de otro sin decidirlo (o mantener una rama o copia por ambiente).

### Cambiar propiedades, textos, tokens o versión

1. Editar la fuente: `docs/diseno/anatomia-y-tokens/tokens.json` (tokens; los que tienen `maker` son propiedades), `textos.es.json` (textos), la tabla `PROPIEDADES` o `VERSION_CONTROL` del generador.
2. Regenerar: `python3 pcf/CargaAdjuntoBlob/herramientas/generar_desde_diseno.py`.
3. Revisar el diff del manifiesto y de los `.resx` antes de compilar.

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

El namespace del control es `Banco` (lo fija el generador); si el Banco tiene una convención de namespaces, cambiarlo ANTES del primer push, porque después el control ya existe con ese nombre.

### Versión nueva

Subir `VERSION_CONTROL` en el generador, regenerar y repetir «Compilar y subir». La app toma la versión nueva al abrirla en Studio (guía 23). La 1.0.0 cambia el contrato respecto de la v1 (0.0.x): las propiedades `uploadUrl`, `tokenStorage`, `bloqueBytes`, `solicitudId` y `urlBroker` ya no existen; una app construida con la v1 se recablea según la guía 21.

### `AdjuntosGrilla`

Compilación, dominios (Storage y broker), propiedades y alta en el formulario: [`pcf/AdjuntosGrilla/README.md`](../../pcf/AdjuntosGrilla/README.md). Antes, la app registration de la guía 24.

## Con el portal

No aplica para compilar: los componentes se construyen con node y se suben con `pac`. Alternativa al push sin `pac` en el entorno destino: empaquetar el componente en una solución (`pcf/README.md`, "Empaquetar en una solución") e importarla en make.powerapps.com → **Solutions** → **Import solution**.

Lo que se ve en el portal como resultado:

1. make.powerapps.com → **Solutions** → `PowerAppsTools_cmv8` (o `cmv8ingesta`) → componente `Banco.CargaAdjuntoBlob` (y `Banco.AdjuntosGrilla` si se subió).
2. Para pasarlo a `cmv8ingesta` desde el portal: abrir `cmv8ingesta` → **Add existing** → **More** → **Developer** → **Custom control** → `Banco.CargaAdjuntoBlob` (verificar en el portal la ruta exacta del menú).
3. Canvas: en Studio, **Insert** → **Get more components** → pestaña **Code** → aparece `CargaAdjuntoBlob`.
4. Model-driven: en el formulario del registro padre, la subcuadrícula de adjuntos → **+ Component** → `AdjuntosGrilla` (detalle en su README).

## Verificación

- `pac pcf push` termina con el control importado y publicado.
- En Studio, Insertar → Obtener más componentes → Código muestra `CargaAdjuntoBlob`, y sus propiedades incluyen `lote`, `tabla`, los tokens del maker (`PrimaryColor`…) y las salidas `pendientes`, `numeroPedido` y `resultados`.
- `rg -n '<domain>' pcf/CargaAdjuntoBlob/ControlManifest.Input.xml` muestra la cuenta del ambiente.
- Regenerar sin cambios en las fuentes no deja diff (`git status` limpio en `pcf/CargaAdjuntoBlob`).

## Errores que ya vimos

| Síntoma | Causa | Arreglo |
| --- | --- | --- |
| El componente no aparece o no se puede importar | Falta **Allow publishing of canvas apps with code components** en el entorno | Guía 18 |
| (Anticipado) Un cambio en el manifiesto desaparece | Se editó `ControlManifest.Input.xml` a mano y después se corrió el generador | Hacer el cambio en el generador o en `docs/diseno/...` y regenerar |
| 403 `AuthenticationFailed` en Put Block | Falta el bearer de Storage junto con la SAS ligada (`lote` vacío o sin `tokenStorage`) | Cableado de la app, guía 21 |
| 403 `AuthorizationPermissionMismatch` al subir | Faltó el bearer junto con la SAS, o el usuario tiene un rol de datos en el contenedor que no debería | Guía 21 y roles del contenedor (guías de infraestructura) |
| (Anticipado) Los archivos quedan en «Solicitando permiso» | La app no atendió el pedido: no deja una respuesta en `lote` por cada `numeroPedido`, ni siquiera ante un error | `OnChange` de la guía 21: en error, `{"error": ..., "codigo": ...}` en `varLote` |
| (Anticipado) Un archivo pasa a Error sin intentar subir | El control no pudo emparejar con seguridad un item del lote con su archivo (la app cambió el orden de los items o el lote no corresponde al pedido) | Mandar los items en el mismo orden de `pendientes`; reintentar el archivo |
