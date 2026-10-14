# dataverse

Artefactos de Dataverse de la solución `cmv8ingesta` (publisher `banco_cmv8`, prefijo `cmv8`).

## Tabla cmv8_SolicitudIngesta

Un registro por documento enviado a Content Manager. El binario NO vive acá: vive en Blob Storage y acá queda solo la ruta sin firmar (ADR-002).

| Columna | Tipo | Uso |
| --- | --- | --- |
| cmv8_nombre | texto (primaria) | nombre visible del registro |
| cmv8_nombrearchivo | texto 260 | nombre original del archivo |
| cmv8_tamanobytes | entero grande | tamaño |
| cmv8_contenttype | texto 100 | MIME |
| cmv8_estado | opción | 1 Creada, 2 Subido, 3 Procesando, 4 Archivado, 5 Rechazado, 6 Error |
| cmv8_rutablob | texto 400 | `{solicitudId}/{archivo}` dentro del contenedor, nunca una URL firmada |
| cmv8_cmisobjectid | texto 200 | id del documento en Content Manager |
| cmv8_idcliente, cmv8_idcliente2, cmv8_nombrecliente, cmv8_afiliadopadre, cmv8_iddueno | texto | metadatos obligatorios resueltos desde AS400 |
| cmv8_mensajeerror | memo | último error del worker |

## Crear en el entorno

```bash
az login
DATAVERSE_URL=https://org36e60d9d.crm.dynamics.com bash dataverse/crear-tabla.sh
```

El script usa el token de `az` contra Dataverse (el usuario debe ser administrador del entorno), crea publisher, solución, tabla y columnas si no existen, y publica.

## Lo que falta en esta carpeta

- Rol de seguridad "CMV8 Solicitante" (crear/leer/escribir propio sobre la tabla) y "CMV8 Worker" (leer/escribir organización) para el application user de la Function.
- Registro del application user con el client id de la identidad administrada (salida `identidadClientId` del Bicep). Es una operación del admin center; se documenta en el spike S2.
- Exportación de la solución (`pac solution export`) cuando el PCF y el conector estén adentro.
