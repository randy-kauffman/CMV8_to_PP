# Checklist de construcción de la app canvas (carga de adjuntos)

Reglas que la app debe cumplir para que el modelo de seguridad de ADR-002 se sostenga. Salen del hallazgo 7 de los spikes (`docs/plan-solucion.md` §4c): la respuesta del conector trae dos llaves, la SAS (`uploadUrl`) y el token de Storage del usuario (`tokenStorage`). Ninguna sirve sola, pero juntas suben a ese blob durante la vida de la SAS. Se revisan en la revisión de la app antes de publicar.

## Manejo de la respuesta del conector

- [ ] La respuesta de `EmitirSasSubida` / `EmitirSasLectura` se guarda en UNA variable de contexto o global (`varSas`), nunca en una colección.
- [ ] Al terminar la subida, en el `OnChange` del componente, se limpia: `If(Self.estado in ["completado", "error"], Set(varSas, Blank()))`. Lo mismo al salir de la pantalla (`OnHidden`).
- [ ] Ningún `Patch`, `Collect` ni `SaveData` escribe `uploadUrl` ni `tokenStorage`. En Dataverse solo van estado, ruta sin firmar (`rutaBlob`), nombre y tamaño.
- [ ] Ningún control muestra `uploadUrl` ni `tokenStorage` (ni en etiquetas de depuración). Si hace falta diagnosticar, mostrar `rutaBlob`, `expiraUtc` y `tokenStorageExpiraUtc`.
- [ ] El componente recibe `uploadUrl`, `tokenStorage` y `bloqueBytes` desde `varSas`; `urlBroker` queda vacío (el broker lo llama el conector, no el PCF).
- [ ] Si la subida tarda más que la SAS (15 min), la app pide otra SAS con el botón Preparar; no se reintenta con la vieja.

## Flujos y conector

- [ ] `EmitirSasSubida` y `EmitirSasLectura` NO se llaman desde Power Automate. Si un día hace falta, la acción lleva *Secure Inputs* y *Secure Outputs* activados para que el token no quede en el historial de ejecución.
- [ ] El conector `CMV8 Broker` está en el grupo de datos de negocio de la política DLP del entorno, junto con Dataverse, y no en el grupo bloqueado ni en el de no negocio.
- [ ] La conexión del conector es por usuario (Entra ID), nunca compartida ni embebida con una cuenta de servicio.

## Fila de Dataverse

- [ ] El botón Preparar crea la fila en estado Creada antes de pedir la SAS; el broker rechaza filas en otro estado (409).
- [ ] Al completar: `Patch` con estado Subido, nombre de archivo y tamaño en bytes, en el mismo `OnChange`.
- [ ] En error: el mensaje del componente (`mensajeError`) se muestra al usuario; la fila queda en Creada para reintentar.

## Fórmulas de referencia

Botón Preparar:

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

`OnChange` del componente:

```powerfx
If(Self.estado = "completado",
    Patch('Solicitudes de ingesta', varFila, {
        Estado: 'Estado (Solicitudes de ingesta)'.Subido,
        'Nombre de archivo': Self.nombreArchivo,
        'Tamaño en bytes': Self.tamanoBytes }));
If(Self.estado in ["completado", "error"], Set(varSas, Blank()))
```
