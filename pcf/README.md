# CargaAdjuntoBlob (PCF para canvas apps)

Componente PCF (namespace `Banco`, versión 0.0.5) que sube un archivo (videos de hasta 250 MB) directo desde el navegador a Azure Blob Storage, sin Power Automate ni conectores. Spikes S1 / S1b del plan (ver `docs/decisiones/ADR-002-blob-storage-para-binarios.md`).

## Flujo

1. El usuario elige un archivo y presiona **Subir**.
2. El componente valida tamaño (<= `tamanoMaximoMB`) y que `solicitudId` sea un GUID.
3. `POST {urlBroker}/api/sas/subida` con `{ solicitudId, nombreArchivo, tamanoBytes, contentType }` y, si hay `tokenBroker`, `Authorization: Bearer ...`. Respuesta: `{ uploadUrl, rutaBlob, expiraUtc, bloqueBytes, versionServicio }` y, con SAS ligada al usuario (S1b), `tokenStorage` y `tokenStorageExpiraUtc`. En el camino preferido la app obtiene todo eso por el conector y lo pasa en `uploadUrl` y `tokenStorage`.
4. Subida con la API REST de bloques, con `fetch` y sin SDK:
   - `PUT {uploadUrl}&comp=block&blockid=<base64 del índice de 6 dígitos>` con `x-ms-version` (2025-07-05 o `versionServicio`; 2025-07-05 es el mínimo para SAS ligada al usuario).
   - Si hay `tokenStorage` (S1b), cada Put Block y el Put Block List llevan además `Authorization: Bearer <tokenStorage>`. La SAS ligada (`sduoid`) sin ese bearer da 403 `AuthenticationFailed`; el bearer solo, sin SAS, da 403 `AuthorizationPermissionMismatch` (el usuario no tiene RBAC en el contenedor).
   - Bloques de `bloqueBytes` (4 MiB por defecto), hasta `paralelismo` en vuelo (4 por defecto, máximo 8).
   - 3 reintentos por bloque con espera de 500 ms, 1 s y 2 s ante error de red, 5xx o 429. Ante 403 corta de inmediato.
   - `PUT {uploadUrl}&comp=blocklist` con el `BlockList` XML (`<Latest>` por bloque, en orden), `x-ms-blob-content-type` y `Content-Type: application/xml`.
5. Las salidas se actualizan y se llama a `notifyOutputChanged` en cada cambio de estado (el progreso, solo cuando cambia el porcentaje entero). El `OnChange` de la app hace el PATCH de la fila a "Subido".

## Propiedades

| Propiedad | Uso | Tipo | Requerida | Descripción |
|---|---|---|---|---|
| `urlBroker` | entrada | SingleLine.Text | Sí | URL base de la Function, p. ej. `https://xxx.azurewebsites.net` |
| `tokenBroker` | entrada | SingleLine.Text | No | Token Bearer para el broker (S7). Nunca se registra en consola |
| `uploadUrl` | entrada | SingleLine.Text | No | URL del blob con SAS obtenida por el conector (`EmitirSasSubida().uploadUrl`). Si se informa, no se llama al broker |
| `tokenStorage` | entrada | SingleLine.Text | No | S1b: token de Storage del usuario (`EmitirSasSubida().tokenStorage`). Se envía como `Authorization: Bearer` a Storage. Obligatorio cuando el broker emite SAS ligadas (`SAS_LIGADA_USUARIO=true`). Nunca se registra |
| `bloqueBytes` | entrada | Whole.None | No | Tamaño de bloque recomendado por el broker (`bloqueBytes` de la respuesta) |
| `solicitudId` | entrada | SingleLine.Text | Sí | GUID de la fila SolicitudIngesta |
| `tiposAceptados` | entrada | SingleLine.Text | No | Atributo `accept`. Por defecto `video/*,application/pdf` |
| `tamanoMaximoMB` | entrada | Whole.None | No | Por defecto 250 |
| `paralelismo` | entrada | Whole.None | No | Bloques en vuelo. Por defecto 4 |
| `disparadorReinicio` | entrada | Whole.None | No | Al cambiar su valor, el control vuelve a `inactivo` |
| `estado` | salida | SingleLine.Text | - | `inactivo`, `solicitandoSas`, `subiendo`, `confirmando`, `completado`, `error` |
| `progreso` | salida | Whole.None | - | 0 a 100 |
| `rutaBlob` | salida | SingleLine.Text | - | Ruta del blob devuelta por el broker |
| `nombreArchivo` | salida | SingleLine.Text | - | Nombre del archivo elegido |
| `tamanoBytes` | salida | Whole.None | - | Tamaño en bytes |
| `contentType` | salida | SingleLine.Text | - | MIME del archivo |
| `mensajeError` | salida | SingleLine.Text | - | Detalle del error, vacío si no hubo |
| `duracionMs` | salida | Whole.None | - | Duración total |

Nota: `tamanoBytes` es `Whole.None` (entero de 32 bits en el canvas); alcanza para 250 MB, no para archivos de más de 2 GB.

## Estructura

```
pcf/
  CargaAdjuntoBlob/
    ControlManifest.Input.xml
    index.ts            ciclo de vida PCF + DOM
    brokerSas.ts        fetch al broker
    subidaBloques.ts    lógica pura de bloques (codificarIdBloque, construirBlockListXml, subirEnBloques)
    css/CargaAdjuntoBlob.css
    strings/CargaAdjuntoBlob.3082.resx
```

No hay corredor de pruebas en la plantilla de `pac pcf init`, por eso no se incluyen tests unitarios todavía. `subidaBloques.ts` no depende del DOM ni de PCF, así que es fácil de probar con Vitest cuando se decida agregarlo.

## Probar con el test harness

```bash
cd pcf
npm install
npm start watch
```

El harness corre en `http://localhost:8181`. Ese origen debe estar en el CORS de la cuenta de Storage (ya está en `infra/spikes.bicepparam`). El CORS debe permitir `PUT`, `OPTIONS` y los headers `x-ms-version`, `x-ms-blob-content-type`, `Content-Type` y `Authorization` (S1b; hoy `allowedHeaders: *`). El CORS del broker también debe permitir ese origen.

## Empaquetar en una solución (solo documentación, no ejecutar todavía)

```bash
mkdir solucion && cd solucion
pac solution init --publisher-name banco --publisher-prefix banco
pac solution add-reference --path ../
npm --prefix .. run build
dotnet build --configuration Release
```

El zip queda en `solucion/bin/Release/`. Importarlo en el entorno de desarrollo y agregar el componente a la canvas app (Insertar > Obtener más componentes > Código).

## Requisitos del entorno

- En el entorno debe estar habilitado **"Power Apps component framework for canvas apps"** (Centro de administración > Configuración del entorno > Producto > Características).
- El manifiesto declara `external-service-usage enabled="true"` con `*.azurewebsites.net` y `*.blob.core.windows.net`, porque el control llama a servicios externos. Esto lo vuelve un componente premium (requiere licencia Power Apps). Ajustar los dominios reales antes de desplegar.

## Pendiente

- S1b resuelto sin MSAL en el navegador: el broker hace OBO y devuelve `tokenStorage` (ver `spikes/resultados/S1b-sas-ligada-usuario.md`). Si el token vence a mitad de una subida larga (vive 60-90 min), la app debe pedir otra SAS y reiniciar.
- El estado `confirmando` cubre hoy solo un instante: el `blocklist` se envía dentro de `subirEnBloques`. Si se necesita reflejarlo, agregar un callback de fase.
