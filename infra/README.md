# infra

Bicep de la solución (spikes fuera del Banco y montaje adentro: `docs/guia-montaje-banco.md`). Implementa el modelo de gobierno de ADR-002: una cuenta de Storage endurecida para adjuntos, un contenedor por solución, una identidad administrada por solución con mínimo privilegio, y una Function en Flex Consumption (Java 21) que actúa de broker de SAS y de worker CMIS.

## Archivos

| Archivo | Para qué |
| --- | --- |
| `main.bicep` | Plantilla completa, con Defender y Event Grid opcionales |
| `spikes.bicepparam` | Parámetros del sandbox propio |
| `cargar-secretos.sh` | Carga las contraseñas de AS400 y CMIS en Key Vault sin imprimirlas |
| `desplegar.sh` | Registra providers, valida región, crea el RG, valida y despliega |

## Despliegue

```bash
az login
az account set -s "<subscription id>"
bash infra/desplegar.sh rg-cmv8-spikes eastus2
```

Las salidas (nombre de la Function, client id de la identidad, cuenta y contenedor de adjuntos) quedan en `infra/ultimo-despliegue.outputs.json`, que está ignorado por git.

## Orden de activación de opciones

1. Primer despliegue con todo en `false`. Se obtiene la Function vacía y los Storage.
2. Desplegar el código de `function/` con `mvn clean package azure-functions:deploy` (en Flex Consumption no se usa `func azure functionapp publish`).
3. Para S3: `habilitarDefender = true`, redeploy. Luego `crearSuscripcionEventGrid = true`, redeploy. Si no se activa Defender, con `crearSuscripcionEventGrid = true` se crea en cambio el system topic de BlobCreated.
4. Para S1b: `requerirSasLigadaAUsuario = true` con `accionSasNoLigada = 'Log'` primero, y `'Block'` cuando el PCF envíe el bearer del usuario.

## Key Vault

Key Vault (`cmv8-kv-<sufijo>`, RBAC): contraseñas de AS400 y CMIS como referencias `@Microsoft.KeyVault(VaultName=…;SecretName=as400-password)` resueltas con la identidad administrada (`keyVaultReferenceIdentity`). Los valores se cargan fuera de banda: `bash infra/cargar-secretos.sh rg-cmv8-spikes` (lee `function/local.settings.json`, nunca imprime) y después `az functionapp restart`. En el spike el vault es público con RBAC (la Function no tiene VNet); en el Banco `keyVaultAccesoPublico=false` + private endpoint. Estado de resolución: `GET …/sites/<function>/config/configreferences/appsettings?api-version=2023-12-01`.

## Qué NO está en este Bicep todavía

- VNet integration de la Function y private endpoint del Storage: solo tienen sentido dentro del Banco (S0). Se agregan como módulo opcional cuando crucemos.
- Azure Policy de cumplimiento (HTTPS, TLS 1.2, sin Shared Key): va a nivel de suscripción o management group, no de este RG.

## Decisiones embebidas

- Dos cuentas de Storage, no una: el runtime de Functions necesita Blob Data Owner y colas/tablas sobre su cuenta. Mezclarlo con los adjuntos rompería el mínimo privilegio del contenedor.
- Identidad asignada por el usuario, no asignada por el sistema: evita la dependencia circular entre la creación del sitio y las asignaciones de rol sobre el storage de despliegue. Es la misma elección del quickstart oficial de Flex Consumption.
- Rol Contributor scoped al contenedor y Delegator a nivel de cuenta: la clave de delegación de usuario se pide por cuenta, no por contenedor. La SAS que emite la Function solo puede abrir lo que la identidad puede abrir, es decir, ese contenedor.
- Política de SAS a nivel de cuenta: expiración máxima 1 hora con acción Block. Ninguna SAS, de ningún origen, puede vivir más de eso.
