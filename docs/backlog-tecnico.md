# Backlog técnico: cómo resolver los hallazgos 5, 6 y 7 de los spikes

Fecha: 2026-10-14. Fuente de los hallazgos: `docs/plan-solucion.md` §4c. Cada sección cierra con una recomendación y los pasos concretos. Nada de esto está implementado todavía; son decisiones listas para ejecutar.

## 5. Pool de conexiones jt400

### Problema

`ResolutorClienteJt400` instancia `AS400JDBCDriver` y abre una conexión por invocación. En S6 contra PUB400 eso costó 3,6 s de conexión (4,5 s con TLS) contra ~120 ms por consulta en una conexión ya abierta. En producción, cada `ResolverCliente` desde la app pagaría segundos de latencia visible al usuario, y cada archivado del worker también.

### Opciones

| Opción | A favor | En contra |
| --- | --- | --- |
| `AS400JDBCConnectionPoolDataSource` + `AS400JDBCConnectionPool` (nativo de jt400) | Sin dependencias nuevas; conoce todas las propiedades del driver | API vieja, sin métricas, mantenimiento de conexiones ociosas tosco, sin validación configurable |
| **HikariCP con `AS400JDBCDataSource`** | Estándar de facto, validación y keepalive, `maxLifetime`, métricas por JMX o Micrometer, arranque perezoso | Una dependencia más (~160 KB); hay que fijar `connectionTestQuery` porque jt400 no implementa `isValid` de forma confiable en todas las versiones |
| Una conexión estática perezosa por instancia | Mínimo código | Reconexión a mano; una sola conexión por instancia no escala con la concurrencia HTTP de Flex Consumption (16 por instancia por defecto) |

### Recomendación: HikariCP

- Pool por instancia de la Function, creado una sola vez en `Fabrica` y guardado en un campo estático (patrón de cliente estático recomendado para Functions). En Flex Consumption cada instancia tiene su pool; el arranque en frío paga una conexión, las invocaciones calientes no.
- Tamaño: `maximumPoolSize` 4, `minimumIdle` 1. La concurrencia HTTP por instancia es 16, pero la resolución de cliente es una consulta de ~120 ms: 4 conexiones absorben decenas de consultas por segundo por instancia. Más grande solo consume jobs QZDASOINIT en el IBM i.
- `maxLifetime` menor que el timeout de inactividad del job QZDASOINIT y que el idle timeout del firewall del Banco (valor a confirmar con infraestructura; empezar en 10 min). `keepaliveTime` 2 min. `connectionTestQuery` = `VALUES 1`. `connectionTimeout` 10 s. `initializationFailTimeout` -1 para que la Function arranque aunque el IBM i no responda y el error salga en la invocación, no en el arranque.
- `DataSource`: `AS400JDBCDataSource` con `setSecure(true)`, `setPrompt(false)`, `setNaming("sql")`, `setLoginTimeout(30)`, `setSocketTimeout(60000)`, `setExtendedDynamic(true)` + `setPackageCache(true)` (cache de sentencias preparadas de jt400, a medir contra el prepared statement reutilizado de S6).
- Cambio en el hexágono: `ResolutorClienteJt400` recibe un `javax.sql.DataSource` en el constructor en vez de `ParametrosAs400`; la construcción del pool queda en `Fabrica` (adaptador de salida). El puerto `ResolutorCliente` no cambia. Los tests unitarios inyectan un `DataSource` falso; la prueba de integración de S6 (`spikes/s6-jt400/`) se repite con el pool para medir el segundo y tercer `ResolverCliente`.
- Riesgo: perfil de servicio con `QMAXSIGN`: un secreto equivocado en Key Vault multiplicado por los reintentos del pool puede deshabilitar el perfil. Mitigación: `initializationFailTimeout` -1 y no reintentar la conexión dentro del mismo request.

## 6. Credenciales sin secretos: broker (OBO), AS400 y CMIS

### Problema

Hoy `IntercambiadorTokenObo` usa `ClientCredentialFactory.createFromSecret(BROKER_CLIENT_SECRET)`, un secreto de app registration que vive en los app settings y se rota a mano cada 6 meses. `AS400_PASSWORD` y la credencial CMIS son contraseñas de sistemas que no hablan Entra y van a existir sí o sí; la pregunta es dónde viven.

### 6a. Secreto del broker: credencial federada con la identidad administrada

Microsoft documenta que una app registration puede **confiar en una identidad administrada asignada por el usuario** como credencial, sin secreto ni certificado ("Configure an application to trust a managed identity", Entra workload identity federation). La UAMI pide un token con audiencia `api://AzureADTokenExchange`, y ese token se presenta como `client_assertion` al pedir el token OBO de la app del broker.

Orden de preferencia que da Microsoft para credenciales de cliente confidencial: identidad administrada, luego federación, luego certificado o aserción firmada; secreto solo para desarrollo.

Pasos:

1. En la app registration `CMV8 Broker (Function)`: Certificados y secretos → Credenciales federadas → Agregar → escenario *Managed Identity*: issuer `https://login.microsoftonline.com/<tenant>/v2.0`, subject = **principal id** de la UAMI `cmv8-mi-…`, audiencia `api://AzureADTokenExchange`. Por CLI: `az ad app federated-credential create --id <objectId de la app> --parameters credential.json`. Requiere rol Application Administrator o ser owner de la app. La UAMI y la app deben estar en el mismo tenant.
2. En el código (msal4j ≥ 1.15): `ManagedIdentityApplication.builder(ManagedIdentityId.userAssignedClientId(BROKER_MI_CLIENT_ID))` para obtener el token de la UAMI con recurso `api://AzureADTokenExchange`, y `ClientCredentialFactory.createFromClientAssertion(() -> tokenDeLaUami)` en el `ConfidentialClientApplication` del OBO. El proveedor de aserción se llama en cada adquisición, así que la aserción nunca vence en caché.
3. Bicep: nada nuevo, la UAMI ya está asignada a la Function. Sacar `BROKER_CLIENT_SECRET` de los app settings y de `infra/desplegar.sh`, y borrar el secreto `obo-storage-s1b` de la app registration cuando el OBO con aserción esté probado.
4. Probar primero en el tenant propio (repetir la prueba 8 de S1b, SAS + bearer = 201).

Nota: la misma federación sirve para Easy Auth si algún día necesita secreto (`OVERRIDE_USE_MI_FIC_ASSERTION_CLIENTID`), pero hoy Easy Auth solo valida tokens y no lo necesita.

### 6b. `AS400_PASSWORD` y credencial CMIS: referencias de Key Vault

Estas contraseñas no desaparecen; lo que se elimina es su presencia en texto plano en app settings, pipelines y scripts.

1. Key Vault con RBAC (no access policies), sin acceso público, con private endpoint en la VNet de la Function (Flex Consumption enruta todo el tráfico saliente por la VNet sin configuración adicional).
2. A la UAMI de la Function: rol **Key Vault Secrets User** sobre el vault.
3. En la Function: `keyVaultReferenceIdentity` = resource id de la UAMI (si no, App Service intenta con la identidad asignada por el sistema, que no existe). Por CLI: `az functionapp update --set keyVaultReferenceIdentity=<id de la UAMI>`. Agregarlo al Bicep.
4. App settings: `AS400_PASSWORD=@Microsoft.KeyVault(SecretUri=https://<kv>.vault.azure.net/secrets/as400-password/)` y lo mismo para `CMIS_PASSWORD`. URI **sin versión** para que la rotación no requiera redeploy (la referencia se refresca a las 24 h o al reiniciar). El Bicep ya tiene el patrón para `referenciaKeyVaultBroker`; generalizarlo.
5. Rotación: política de expiración del perfil IBM i alineada con una alerta de expiración del secreto en Key Vault (Event Grid `SecretNearExpiry`). El cambio de contraseña del perfil IBM i se puede hacer con `AS400.changePassword`, demostrado en S6.
6. Qué NO hacer: leer Key Vault desde el código con el SDK para estas dos credenciales. La referencia de app setting es más simple, no agrega dependencia y el código sigue leyendo una variable de entorno. El SDK solo se justifica si hace falta rotación sin reinicio.

## 7. El token de Storage viaja en la respuesta del conector

### Problema

`EmitirSasSubida` devuelve `tokenStorage` (token OBO del usuario para Azure Storage, vida 60 a 90 min) junto con la SAS (15 min). El PCF lo necesita para cada PUT. Mientras vive en la app está en una variable de Power Fx; si alguien llama la misma operación desde Power Automate, queda en el historial de ejecución; si alguien lo persiste con `Patch`, queda en Dataverse.

### Qué protege ya el diseño

- El token solo sirve para `https://storage.azure.com` y el usuario no tiene ningún rol en Storage: sin la SAS no abre nada (S1b, prueba 7).
- La SAS sin el bearer del mismo `oid` tampoco (S1b, prueba 3). El robo útil exige las dos piezas, del mismo usuario, dentro de la ventana de la SAS.
- Nada del broker ni del PCF escribe el token en logs: `Resultado.toString()` lo enmascara y el PCF no lo expone como salida.

### Opciones para reducir la exposición

| Opción | Qué hace | Costo / límite |
| --- | --- | --- |
| **Higiene en la app** (obligatoria) | `Set(varSas, Blank())` en `OnChange` cuando `estado` sea `completado` o `error`; nunca `Patch` del token; nunca mostrarlo en controles; el token nunca va a una colección persistida (`SaveData`) | Cero. Es una regla de construcción de la app, hay que auditarla en revisión |
| **Prohibir la operación desde flujos** (obligatoria) | Documentar que `EmitirSasSubida` y `EmitirSasLectura` no se usan desde Power Automate; si alguna vez hace falta, la acción va con *Secure Inputs/Outputs* activados para que no quede en el historial de ejecución | Cero. Se puede reforzar con una política DLP que ponga el conector solo en el grupo de negocio y revisar flujos que lo usen |
| **Acortar la vida del token OBO** (a validar) | Política `tokenLifetimePolicy` con `AccessTokenLifetime` mínimo `00:10:00` (el mínimo que permite Entra), asignada al service principal del recurso Azure Storage en el tenant | Entra permite asignar la política a un service principal específico; falta validar en el tenant propio que acepte el SP first-party de Azure Storage. Si funciona, el token vive 10 min y el broker tendría que pedir SAS de ≤ 5 min o refrescar el token por subida (hoy exige token ≥ SAS + 5 min). Reduce la ventana de robo de 60-90 min a 10 |
| **Acortar la SAS** | SAS de 5 min en vez de 15, con reemisión desde el PCF si la subida tarda más (ya devuelve 409 si la fila cambió de estado) | Más llamadas al broker para videos grandes en enlaces lentos (100 MB a 10 Mbps son 80 s, entra; 250 MB en enlaces de 5 Mbps no) |
| No devolver el token: que el PCF lo pida él mismo | El PCF obtendría el token de Storage con MSAL en el navegador | Es exactamente lo que se descartó en S1b: MSAL dentro de un PCF en canvas no tiene redirect propio ni cache confiable, y el token terminaría igual en memoria del navegador |
| No devolver el token: subir por el broker | El broker haría de proxy de los bloques | Rompe el ADR-002: la Function pagaría tráfico y tiempo de 100 MB por subida y vuelve el límite de memoria y de costo que motivó la carga directa |

### Recomendación

Las dos primeras son obligatorias y gratis; se escriben en el checklist de construcción de la app y en el modelo de gobierno. La tercera se prueba en el tenant propio en 30 minutos (crear la política, asignarla al SP de Storage, pedir una SAS y leer `tokenStorageExpiraUtc`); si funciona, se adopta junto con SAS de 10 min y `token ≥ SAS` en el broker. Las dos últimas quedan descartadas con la razón anotada.
