# S4b. Worker: blob de 100 MB hacia CMIS 1.1 en streaming

- Fecha: 2026-10-14
- Entorno: VPS Linux local (sin despliegue a Azure, sin cambios en recursos). Lectura del blob real en `cmv8adjsupfcopn` / `cmv8-ingesta` con `az login` (admin con Storage Blob Data Reader). Servidor CMIS de prueba en Docker, publicado solo en `127.0.0.1`.
- Estado: **confirmado** contra un servidor CMIS de referencia. Falta repetirlo contra IBM CM V8 real (S4a, dentro del Banco).

## Hipótesis

El worker (`AlmacenAdjuntosBlob.abrir` + `ArchivoDocumentalCmis.archivar`) lleva un archivo de 100 MB desde Blob Storage a un servidor CMIS 1.1 por Browser binding (OpenCMIS 1.1.0) **sin cargarlo en memoria**: la lectura del blob es por rangos de 4 MiB y OpenCMIS envía el multipart con chunked streaming. El `cmis:objectId` devuelto se puede volver a encontrar con `buscarPorSolicitud` (base de la idempotencia).

Criterio de aceptación: la prueba pasa con `-Xmx256m` (y, como prueba más fuerte, con un heap menor que el archivo).

## Configuración probada

| Pieza | Versión / valor |
| --- | --- |
| Servidor CMIS | Apache Chemistry OpenCMIS InMemory 1.1.0 (`chemistry-opencmis-server-inmemory-1.1.0.war`, SHA-1 `a3571ac1c5ffb712a0d61fdbad76282adfb08bb2` verificado contra Maven Central) |
| Contenedor | `tomcat:9-jre8` (Tomcat 9.0.123, Java 1.8.0_504), digest `sha256:c6ee914942c2d44684f9168e90f24357a654352d86b6132ebb44b369e64e886c` |
| Publicación | `-p 127.0.0.1:18080:8080` (8080 en loopback ya lo usa otro proyecto en este VPS) |
| Repositorio | `repositoryId=A1`, `cmisVersionSupported=1.1`, `rootFolderId=100`, `capabilityQuery=bothcombined` |
| Cliente | OpenCMIS client 1.1.0 (Browser binding), Java 21.0.12-tem, Maven 3.9.9, azure-storage-blob 12.36.0, azure-identity 1.18.7 (`DefaultAzureCredential` → `AzureCliCredential`) |
| Blob | `22222222-2222-4222-8222-222222222222/video-100mb.bin`, 104857600 bytes, BlockBlob, todo ceros |
| Destino | carpeta `/`, tipo `cmis:document`, `CMIS_PROPIEDADES_JSON={}` (sin mapeo: el nombre lleva el prefijo `<solicitudId>_`) |

Comandos:

```bash
spikes/s4b-cmis-docker.sh            # idempotente: rm -f del contenedor, descarga y verifica el WAR, espera 200
cd function
mvn -q -Pintegracion -Dtest=S4bStreamingCmisIT -DargLine="-Xmx256m" test
mvn -q -Pintegracion -Dtest=S4bStreamingCmisIT -DargLine="-Xmx1g"   test
mvn -q -Pintegracion -Dtest=S4bStreamingCmisIT -DargLine="-Xmx64m"  test
docker stop cmis-inmemory
```

La prueba (`function/src/test/java/com/banco/cmv8/spikes/S4bStreamingCmisIT.java`, `@Tag("integracion")`) usa las clases reales de `src/main`: arma `AlmacenAdjuntosBlob` y `ArchivoDocumentalCmis` igual que `Fabrica`, borra restos de corridas previas, ejecuta `archivar(solicitud, almacen.abrir(ruta), almacen.tamano(ruta), Map.of())`, luego `buscarPorSolicitud` y lee el documento con una sesión OpenCMIS aparte. Un hilo muestrea `totalMemory() - freeMemory()` cada 200 ms durante la subida.

El `mvn test` por defecto sigue corriendo solo las 37 pruebas unitarias: el pom excluye el grupo `integracion` y el perfil `-Pintegracion` invierte el filtro e incluye `**/*IT.java`.

## Evidencia

| Corrida | Heap máx. (`-Xmx`) | Tiempo de `archivar` | Throughput | Heap usado máx. muestreado | objectId | `buscarPorSolicitud` | `contentStreamLength` (cliente) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 256 MiB | 14,9 s (primera, en frío) | 6,7 MB/s | 93,3 MiB | `136` | `136` | 104857600 |
| 2 | 1 GiB | 9,0 s | 11,1 MB/s | 143,9 MiB | `137` | `137` | 104857600 |
| 3 | **64 MiB** | 8,9 s | 11,2 MB/s | 60,2 MiB | `138` | `138` | 104857600 |
| 4 | 256 MiB | 9,5 s | 10,6 MB/s | 153,0 MiB | `139` | `139` | 104857600 |

Heap base antes de la subida (después de `System.gc()`): 14,7 a 14,9 MiB en todas las corridas. Las cuatro pasaron (`Tests run: 1, Failures: 0, Errors: 0`).

Verificación del lado del servidor, independiente del cliente Java:

```bash
curl -s -u test:test "http://127.0.0.1:18080/cmis/browser/A1/root?objectId=139&cmisselector=object"
# cmis:objectId=139, cmis:name=22222222-2222-4222-8222-222222222222_video-100mb.bin, cmis:contentStreamLength=104857600
curl -s -u test:test "http://127.0.0.1:18080/cmis/browser/A1/root?objectId=139&cmisselector=content" | sha256sum
# 20492a4d0d84f8beb1767f6616229f85d44c2827b64bdbfb260ee12fa1109e0e = sha256 de 104857600 bytes en cero
```

El contenido guardado coincide byte a byte con el blob (100 MiB de ceros; MD5 `LygrhOfmCNWFJEntlAv8UQ==`). El blob no trae `Content-MD5` porque se subió por bloques, así que la comparación es contra el contenido conocido.

Lectura de los números de heap: el valor muestreado incluye basura todavía no recolectada, por eso sube con `-Xmx` (con más heap el GC tarda más en correr). Lo que prueba el streaming es la corrida 3: **100 MiB pasaron por una JVM con 64 MiB de heap**, algo imposible si el archivo se cargara entero en memoria. El tiempo lo domina la descarga desde Azure (internet hacia el VPS), no el heap: con 64 MiB y con 1 GiB tarda lo mismo.

## Veredicto

- **Confirmado**: el worker transmite 100 MB de Blob a CMIS 1.1 Browser binding en streaming, con memoria acotada e independiente del tamaño del archivo. Pasa con 256 MiB y con 64 MiB de heap.
- **Confirmado**: `buscarPorSolicitud` encuentra el mismo `objectId` recién creado (consulta CMIS QL por `cmis:name` + `IN_FOLDER`), así que la idempotencia de `ProcesarIngesta` funciona en el camino sin mapeo de propiedades.
- **Confirmado**: el servidor guarda `contentStreamLength=104857600` y el contenido es idéntico.
- Sin cambios en el código de producción: no apareció ningún bug. Solo se agregó la prueba de integración y la configuración de surefire.

## Gotchas

1. **Tomcat 9, no 10+**: el WAR es `javax.servlet`; Tomcat 10+ es `jakarta` y no lo despliega.
2. **Tomcat 9 con Java 17 tampoco sirve**: con `tomcat:9-jre17` el despliegue falla con `java.lang.ClassNotFoundException: javax.xml.ws.Endpoint`, porque el WAR también registra el binding Web Services y JAX-WS ya no viene con Java 11+. Con `tomcat:9-jre8` levanta en segundos.
3. **Chunking en OpenCMIS**: `DefaultHttpInvoker` (el invocador por defecto, basado en `HttpURLConnection`) llama `setChunkedStreamingMode(65535)` **siempre que hay cuerpo**, sin importar si se conoce el largo del `ContentStream` (verificado con `javap` sobre `chemistry-opencmis-client-bindings-1.1.0.jar`). No hace falta ningún `SessionParameter` para el streaming. Riesgo para S4a: si delante de CM V8 hay un proxy o WAS/IHS que rechaza `Transfer-Encoding: chunked`, hay que verlo en el Banco; no existe opción de largo fijo en este invocador. No activar `org.apache.chemistry.opencmis.binding.compression`.
4. **Lectura del blob**: `openInputStream` con bloque de 4 MiB lee por rangos (`BlobInputStream`); el buffer de 4 MiB es la memoria acotada por subida.
5. **Autenticación del InMemory**: exige el encabezado Basic (sin credenciales devuelve 401) pero acepta cualquier usuario y clave (`foo:bar` → 200). No sirve para probar credenciales: eso es S4a.
6. **Puerto**: `127.0.0.1:8080` lo ocupa un contenedor Alfresco de otro proyecto en este VPS; el script usa `CMIS_PUERTO=18080` por defecto.
7. El InMemory guarda el contenido en la memoria del servidor (el contenedor llegó a ~1,3 GiB después de 4 subidas). Es un tema del servidor de prueba, no del worker.
8. `InMemoryServer.MaxSize=20971520` del `repository.properties` del WAR no impidió crear documentos de 100 MB.

## Qué queda para S4a (dentro del Banco, contra IBM CM V8 real)

- **Tipo y propiedades reales**: `CMIS_TYPE_ID` del item type de CM V8 y `CMIS_PROPIEDADES_JSON` con sus atributos (obligatorios, tipos no texto como fechas o enteros; hoy `MapeoPropiedadesCmis` envía todo como texto).
- **Idempotencia por propiedad**: mapear `${solicitudId}` a un atributo de CM y verificar que la consulta CMIS QL por ese atributo + `IN_FOLDER` funciona en CM V8 (queryName, índice, permisos de consulta). Acá solo se probó el camino por `cmis:name`.
- **Versionado**: si el tipo es versionable, `archivar` crea con `VersioningState.MAJOR`; validar el comportamiento en CM V8.
- **TLS**: URL `https` con la CA interna del Banco en el truststore de Java (o del host del worker).
- **Credenciales**: usuario de servicio de CM en Key Vault, leído como referencia de app setting (`CMIS_USER` / `CMIS_PASSWORD`); probar el rechazo con credenciales malas, que el InMemory no valida.
- **Red y chunking**: el camino real (túnel o red privada hacia CM) y si acepta `Transfer-Encoding: chunked`; medir throughput con 100 MB y 250 MB.
- **Límites de CM**: tamaño máximo de contenido y timeouts del servidor; el cliente ya tiene `READ_TIMEOUT` de 30 min.
