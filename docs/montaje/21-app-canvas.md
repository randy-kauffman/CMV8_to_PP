# 21. App canvas de carga de adjuntos

> Rol que lo ejecuta: Power Platform (creador de la app). Depende de: 18 (tabla y roles), 19 (componente), 20 (conector y DLP). Lo necesitan: 22 (humo). Costo: sin costo propio; cada usuario necesita Power Apps Premium (conector personalizado y componente con `external-service-usage`).

## Qué es y qué hace en esta solución

Es el producto: la pantalla donde el usuario crea la solicitud, elige el archivo y lo sube. La app se construye con `docs/checklist-construccion-app.md`: la respuesta del conector trae la SAS y el token de Storage del usuario, y la app los guarda en UNA variable, se los pasa al componente y los limpia al terminar; nunca los persiste ni los muestra. Las fórmulas de referencia del botón Preparar y del `OnChange` están ahí y se repiten abajo.

Flujo dentro de la app:

1. **Preparar**: crea la fila en estado Creada y pide la SAS al conector (`EmitirSasSubida`).
2. El componente sube en bloques con las dos llaves.
3. `OnChange` del componente: al completar, `Patch` de la fila a Subido con nombre y tamaño; al completar o fallar, limpia la variable.

## ¿Imprescindible?

**Veredicto: Imprescindible** (por definición: es lo que usa el usuario)

- Qué aporta: la experiencia de usuario y el orden correcto de las llamadas (fila antes de SAS; limpieza de las llaves al terminar).
- Qué pasa si no está: no hay solución; el resto es infraestructura sin cliente.
- Qué decisión lo haría distinto: el **tipo** de app. Podría ser una app model-driven (formulario de la tabla con el componente) o una página personalizada dentro de una model-driven.
- Alternativa y su tradeoff: **model-driven / página personalizada**. Ventaja: formularios, vistas y seguridad de Dataverse sin construir pantallas, buena para el seguimiento de solicitudes. Costo: el componente está construido como componente de campo para canvas; en un formulario model-driven habría que adaptar su enlace de propiedades y la llamada al conector (en model-driven el conector no se invoca igual que desde Power Fx); la página personalizada sí acepta Power Fx y conectores, con las limitaciones de las páginas personalizadas. Se eligió canvas porque es donde se probaron los spikes S1, S2 y S7.

## Valores del cuaderno

| Variable | Produce / consume | Cómo se obtiene |
| --- | --- | --- |
| Conector `CMV8 Broker` | Consume | Guía 20 |
| Componente `CargaAdjuntoBlob` | Consume | Guía 19 |
| Tabla `Solicitudes de ingesta` | Consume | Guía 18 |

No hay identificadores nuevos: todo lo resuelve Studio por nombre.

## Con comandos

No aplica: una canvas app se construye en Power Apps Studio. Cuando esté terminada, se agrega a la solución `cmv8ingesta` y viaja con ella (`pac solution export` cuando el Banco defina su ALM).

## Con el portal

En **make.powerapps.com** → entorno del ambiente → **Solutions** → `cmv8ingesta` → **New** → **App** → **Canvas app** (crearla dentro de la solución evita moverla después).

1. **Data** → **Add data**: la tabla **Solicitudes de ingesta** (Dataverse) y el conector **CMV8 Broker** (crea la conexión del creador).
2. **Insert** → **Get more components** → **Code** → `CargaAdjuntoBlob` → **Import**. Insertarlo en la pantalla (queda como `CargaAdjuntoBlob1`).
3. Propiedades del componente (resumen del cableado):

| Propiedad | Valor |
| --- | --- |
| `uploadUrl` | `varSas.uploadUrl` |
| `tokenStorage` | `varSas.tokenStorage` |
| `bloqueBytes` | `varSas.bloqueBytes` |
| `solicitudId` | `Text(varFila.'Solicitud de ingesta')` |
| `urlBroker` | vacío (al broker lo llama el conector, no el componente) |

4. Botón **Preparar**, propiedad `OnSelect`:

```powerfx
Set(varFila,
    Patch('Solicitudes de ingesta', Defaults('Solicitudes de ingesta'),
        { Estado: 'Estado (Solicitudes de ingesta)'.Creada }));
Set(varSas,
    'CMV8 Broker'.EmitirSasSubida({
        solicitudId:   Text(varFila.'Solicitud de ingesta'),
        nombreArchivo: CargaAdjuntoBlob1.nombreArchivo,
        tamanoBytes:   CargaAdjuntoBlob1.tamanoBytes,
        contentType:   CargaAdjuntoBlob1.contentType }))
```

5. Componente, propiedad `OnChange`:

```powerfx
If(Self.estado = "completado",
    Patch('Solicitudes de ingesta', varFila, {
        Estado: 'Estado (Solicitudes de ingesta)'.Subido,
        'Nombre de archivo': Self.nombreArchivo,
        'Tamaño en bytes': Self.tamanoBytes }));
If(Self.estado in ["completado", "error"], Set(varSas, Blank()))
```

6. Pantalla, `OnHidden`: `Set(varSas, Blank())`.
7. Mostrar `CargaAdjuntoBlob1.mensajeError` al usuario cuando `estado = "error"`; la fila queda en Creada para reintentar.
8. **Save** → **Publish**.
9. **Share**: compartir la app con los usuarios piloto. Compartir también el conector (guía 20). La conexión del conector es por usuario; nunca compartida ni embebida con una cuenta de servicio.

Reglas del checklist que se revisan antes de publicar (resumen; la lista completa está en `docs/checklist-construccion-app.md`):

- La respuesta del conector va en UNA variable (`varSas`), nunca en una colección.
- Ningún `Patch`, `Collect` ni `SaveData` escribe `uploadUrl` ni `tokenStorage`; en Dataverse solo van estado, ruta sin firmar, nombre y tamaño.
- Ningún control muestra `uploadUrl` ni `tokenStorage`, ni siquiera para depurar.
- Si la subida tarda más que la SAS (15 min), se pide otra SAS con Preparar; no se reintenta con la vieja.
- `EmitirSasSubida` y `EmitirSasLectura` no se llaman desde Power Automate (si algún día hace falta, con *Secure Inputs/Outputs*).

## Verificación

- Un piloto abre la app, prepara y sube un archivo chico: la fila pasa de Creada a Subido.
- Revisión del checklist marcada completa antes de publicar.
- La prueba de 100 MB, el usuario sin rol y los logs de Storage son el humo (guía 22).

## Errores que ya vimos

| Síntoma | Causa | Arreglo |
| --- | --- | --- |
| 409 del broker | La fila no está en Creada | La app crea la fila antes de pedir la SAS (botón Preparar) |
| 403 `AccesoDenegado` del broker | El usuario no tiene CMV8 Solicitante o la fila no es suya | Guía 18 |
| 403 `AuthorizationPermissionMismatch` al subir | Faltó el bearer de Storage junto con la SAS ligada (`tokenStorage` no cableado), o el usuario tiene un rol de datos que no debería | Cableado de esta guía; roles del contenedor (guías de infraestructura) |
| 401 con `WWW-Authenticate: Bearer error="interaction_required"` | Acceso condicional sobre Azure Storage que el token del conector no satisface | Hallazgo 10 de la guía de spikes; `docs/backlog-tecnico.md` §10 |
