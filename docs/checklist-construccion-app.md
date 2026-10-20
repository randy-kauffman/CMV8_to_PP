# Checklist de construcción de la app canvas (carga de adjuntos, v2)

Reglas que la app debe cumplir para que el modelo de seguridad de ADR-002 se sostenga con el flujo por lotes de ADR-003 (§4.1). Salen del hallazgo 7 de los spikes (`docs/plan-solucion.md` §4c): la respuesta de `EmitirSasLote` trae dos llaves, N SAS de un blob cada una (`items[].uploadUrl`) y UN token de Storage del usuario (`tokenStorage`) para todo el lote. Ninguna sirve sola, pero juntas suben a esos blobs durante la vida de la SAS (15 minutos). Se revisan en la revisión de la app antes de publicar. Fórmulas completas: guía 21 y `pcf/CargaAdjuntoBlob/README.md`.

## Manejo de la respuesta del conector

- [ ] La respuesta de `EmitirSasLote` se guarda en UNA variable (`varLote`), como `JSON(respuesta)`, con las SAS y el token juntos. Nunca en una colección, ni partida en varias variables.
- [ ] `varLote` entra al componente solo por la propiedad `lote`.
- [ ] `varLote` se limpia apenas el componente deja de esperar SAS: `If(Self.estado <> "listo-para-sas" && !IsBlank(varLote), Set(varLote, Blank()))` en el `OnChange`. Lo mismo en `OnHidden` de la pantalla.
- [ ] Ningún `Patch`, `Collect`, `ClearCollect` ni `SaveData` escribe `varLote`, `uploadUrl` ni `tokenStorage`. En Dataverse solo van estado, tipo, nombre, tamaño, content type, padre y metadatos; la ruta sin firmar (`rutablob`) la escribe el broker.
- [ ] Ningún control muestra `varLote`, `uploadUrl` ni `tokenStorage` (ni en etiquetas de depuración). Para diagnosticar: `CargaAdjuntoBlob1.estado`, `resumen` y `resultados` (no traen URLs ni tokens).
- [ ] La respuesta de `EmitirSasLectura` (`downloadUrl` y `tokenStorage`) sigue las mismas reglas en su propia variable.
- [ ] Si la subida tarda más que la SAS, no se reutiliza un lote viejo: el componente pide uno nuevo al reintentar.

## Pedidos del componente

- [ ] Cada `numeroPedido` se atiende **una sola vez** (`varPedidoAtendido`): el `OnChange` se dispara muchas veces por el progreso.
- [ ] Cada pedido recibe **siempre una respuesta** en `varLote`, aun ante error (`{"error": "...", "codigo": ...}`); si no, los archivos quedan esperando permiso.
- [ ] Los items de `EmitirSasLote` van **en el mismo orden** de `pendientes`: el control empareja por posición.
- [ ] `disparadorReinicio` no cambia mientras la app espera la respuesta de `EmitirSasLote`.

## Filas de Dataverse

- [ ] La app crea las N filas en **Creada** antes de pedir el lote, con `tipodocumento` (lookup obligatoria a `cmv8_tipodocumento`, solo tipos En uso), `nombrearchivo`, `tamanobytes`, `contenttype`, el padre y los metadatos que pida el tipo. El broker solo emite SAS para filas en Creada o Error.
- [ ] Un reintento usa la fila existente (el `id` viene en `pendientes`); no crea otra.
- [ ] **No hacer `Patch` a Subido si la fila no está en Creada o Error**: el worker puede haberla tomado ya y la haría retroceder.
- [ ] Subido se marca una vez por id (`colAtendidos`).
- [ ] Las filas de archivos cancelados quedan en Creada: la app decide si las borra.

## Estados y acciones

- [ ] La lista muestra los diez estados con texto e ícono y `mensajeerror` en Observada, Error y Rechazado.
- [ ] **Observada** ofrece corregir la fila y **Reanudar**; sin `Reanudar` la corrección no se procesa.
- [ ] **Detenida** solo informa que actúa el administrador documental; no ofrece acciones al usuario.
- [ ] **Archivar** solo sobre adjuntos en Almacenado, cuando ocurre el evento de negocio.
- [ ] `ConsultarAs400` solo con consultas con nombre declaradas en Azure; la app nunca arma SQL.

## Flujos y conector

- [ ] `EmitirSasLote` y `EmitirSasLectura` NO se llaman desde Power Automate (ADR-003 §4.7). Un flujo que ingesta un archivo usa **`IngestarArchivo`** (el broker escribe el blob; tope de 100 MB por acción). Si un día un flujo necesitara `EmitirSasLectura`, la acción lleva *Secure Inputs* y *Secure Outputs* activados para que el token no quede en el historial de ejecución.
- [ ] El conector `CMV8 Broker` está en el grupo de datos de negocio de la política DLP del entorno, junto con Dataverse, y no en el grupo bloqueado ni en el de no negocio.
- [ ] La conexión del conector es por usuario (Entra ID), nunca compartida ni embebida con una cuenta de servicio.
