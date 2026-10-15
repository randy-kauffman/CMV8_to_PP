# Guía de montaje en el tenant del Banco

Fecha: 2026-10-14. Índice del montaje de la solución completa (Entra, Azure, Function, Dataverse, componente, conector y app) en un tenant y una suscripción del Banco, en el orden en que las piezas se necesitan unas a otras. Reproduce lo que ya funciona en el tenant de desarrollo; donde algo depende de una decisión del Banco, lo dice.

El detalle está **segregado por componente** en `docs/montaje/`: una guía por elemento, cada una con qué es, si es imprescindible y por qué, los valores que produce o consume, cómo se monta **con Bicep**, **con Azure CLI** y **con el portal**, cómo se verifica y qué errores ya vimos. Cómo funciona todo junto, con el diagrama: `docs/arquitectura.md`.

Complementa `docs/guia-spikes-banco.md`: esa guía dice QUÉ se prueba adentro y qué evidencia cierra cada punto; esta dice CÓMO se monta lo que esa guía da por existente (su Fase 0). Las pruebas que solo se pueden hacer adentro (red, CM V8 real, IBM i real, acceso condicional con usuarios reales, Defender) siguen allá.

## 1. Antes de empezar

### 1.1 Qué hace falta tener

| Pieza | Qué se necesita | Quién lo da |
| --- | --- | --- |
| Suscripción de Azure | Suscripción con un grupo de recursos nuevo por ambiente (`rg-cmv8-<ambiente>`); rol **Owner** sobre ese grupo (hay que asignar roles RBAC, Contributor no alcanza) | Cloud |
| Región | Una que soporte Flex Consumption con Java 21 (`az functionapp list-flexconsumption-runtimes --location <región> --runtime java`) | Cloud |
| Entra ID | Permiso para crear app registrations (Application Developer alcanza para crearlas; **Cloud Application Administrator o superior** para dar consentimiento de administrador) | Identidad |
| Power Platform | Entorno administrado (Managed Environment) con Dataverse; quien corre los scripts es **administrador del entorno**; un administrador de Power Platform para la política DLP | Power Platform |
| Licencias | Todos los usuarios de la app con Power Apps Premium (el conector personalizado y el componente con `external-service-usage` son premium) | Power Platform |
| Secretos | Contraseña del perfil de servicio de IBM i (solo lectura) y del usuario de servicio de Content Manager. Se cargan en Key Vault desde una estación autorizada, nunca pasan por el repo ni por el chat | IBM i, Content Manager |
| Estación de trabajo | Linux, macOS o WSL con: az CLI 2.91+ con Bicep 0.48+, Java 21 (Temurin) y Maven 3.9+, node 22, pac CLI 2.11+, jq, git, curl | quien monta |

Clonar el repo y pararse en su raíz; todos los comandos de las guías se corren desde ahí.

### 1.2 Cuaderno de valores

A medida que se avanza aparecen identificadores que los pasos siguientes necesitan. Conviene tener un archivo **fuera del repo** (por ejemplo `~/cmv8-<ambiente>.env`) con estas variables y cargarlo con `source` antes de cada bloque de comandos. Ninguna es secreta; los secretos NO van acá. Cada guía dice en su sección "Valores del cuaderno" cuáles produce y cuáles consume.

```bash
AMBIENTE=        # dev | qa | prod
RG=rg-cmv8-$AMBIENTE
REGION=          # p. ej. eastus2
TENANT_ID=       # az account show --query tenantId -o tsv
PREFIJO=cmv8
SUFIJO=          # 8 minúsculas; solo si se monta a mano (el Bicep lo deriva de uniqueString)

# Identidad (guías 01, 02, 14)
BROKER_APP_ID=
BROKER_OBJ_ID=
BROKER_SCOPE_ID=
CONECTOR_APP_ID=

# Azure (guías 03 a 13; con Bicep salen de infra/ultimo-despliegue.outputs.json)
MI_NAME=         # identidad administrada (04)
MI_ID=           # resource id de la identidad (04)
MI_CLIENT_ID=
MI_PRINCIPAL_ID=
LOG_NAME=        # Log Analytics (05)
LOG_ID=          # resource id del workspace (05)
APPI_NAME=       # Application Insights (05)
STORAGE_HOST=    # cuenta del runtime (06)
STORAGE_ADJ=     # cuenta de adjuntos (07)
KV_NAME=         # Key Vault (09)
PLAN_NAME=       # plan Flex Consumption (10)
FUNCTION_NAME=
FUNCTION_HOST=   # <function>.azurewebsites.net

# Power Platform (guías 18 a 21)
DATAVERSE_URL=   # https://<org>.crm.dynamics.com
ENV_ID=          # id del entorno (pac env list)
```

### 1.3 Orden y dependencias

```
01 broker ──┐
02 conector ┤
            ├─► 03 RG ─► 04 identidad ─► 05 observabilidad ─► 06 host ─► 07 adjuntos ─► 08 roles ─► 09 Key Vault ─► 10 Function
            │                                                                                                          │
            │                        14 credencial federada ◄─────────────────────────────────────────────────────────┤
            │                        15 Easy Auth ◄───────────────────────────────────────────────────────────────────┤
            │                        16 código ─► 11 Event Grid (la función destino debe existir) ◄───────────────────┘
            │                        17 secretos en Key Vault
            └─► 18 Dataverse ─► 19 PCF ─► 20 conector (+ DLP) ─► 21 app canvas ─► 22 humo ─► 23 operación
                12 Defender (opcional) · 13 presupuesto (en cualquier momento después de 03)
```

En palabras: primero Entra (01, 02), porque la Function necesita el app id del broker. Después toda la infraestructura (03 a 10): con Bicep es un solo despliegue; a mano es en ese orden. Con la Function creada, la credencial federada (14) y Easy Auth (15) ANTES de publicar código (16), para que nunca haya una ventana con la Function abierta. Event Grid (11) después del código, porque la suscripción apunta a una función que tiene que existir. Secretos (17) cuando exista el vault. Dataverse (18) necesita el client id de la identidad; el PCF (19) necesita el hostname de la Function y de la cuenta de adjuntos; el conector (20) necesita la Function con Easy Auth y las dos apps; la app (21) necesita todo lo anterior.

Cada guía termina con una verificación. Si la verificación falla, no seguir: el siguiente paso va a fallar peor y más lejos de la causa.

## 2. Dos caminos para la infraestructura

**Camino A, Bicep (recomendado).** `infra/main.bicep` crea los elementos 03 a 11 (y 12 si se habilita) en un solo despliegue idempotente, con todas las propiedades endurecidas y las asignaciones de rol. Se escribe `infra/banco-<ambiente>.bicepparam` con los valores de la tabla de abajo y se corre `bash infra/desplegar.sh $RG $REGION infra/banco-$AMBIENTE.bicepparam`. Las salidas quedan en `infra/ultimo-despliegue.outputs.json` (ignorado por git); de ahí se copian al cuaderno. Cada guía de 03 a 13 dice en su sección "Con Bicep" qué recurso del archivo la implementa y qué parámetro la gobierna.

| Parámetro | Valor en el Banco | Por qué |
| --- | --- | --- |
| `location` | `$REGION` | Debe soportar Flex Consumption con Java |
| `prefijo` | `cmv8` | Nombres de recursos |
| `brokerClientId` | `$BROKER_APP_ID` | Easy Auth y OBO |
| `dataverseUrl` | `$DATAVERSE_URL` | El broker verifica la solicitud en Dataverse |
| `autorizacionModo` | `dataverse` | Nunca `ninguna` fuera de un spike |
| `requerirSasLigadaAUsuario` | `true` | Defensa en profundidad; el broker firma con `sduoid` igual |
| `accionSasNoLigada` | `Block` | Rechazar SAS no ligadas |
| `origenesCors` | `['*']` o lo que decida el Banco | Power Apps corre desde hosts regionales variables; CORS no es frontera de seguridad (ADR-002) |
| `ipsPermitidas` | Según S0 | Firewall de IP de la cuenta de adjuntos; vacío = sin firewall |
| `keyVaultAccesoPublico` | `false` cuando exista VNet y private endpoint; `true` hasta entonces | El Bicep todavía no crea el private endpoint |
| `oidOperadorKeyVault` | oid de quien carga los secretos | Rol Key Vault Secrets Officer |
| `as400Host`, `as400Usuario`, `as400SqlCliente` | Los del IBM i del Banco | La contraseña NO va acá: Key Vault (17) |
| `cmisUrl`, `cmisUsuario`, `cmisRepositorio`, `cmisCarpeta`, `cmisTipo` | Los de CM V8 del Banco | Ídem |
| `habilitarDefender` | `false` (decisión actual) | Guía 12 |
| `crearSuscripcionEventGrid` | `false` en el primer despliegue, `true` después de publicar el código | Guía 11 |
| `diasRetencionAdjuntos` | `7` | Red de seguridad para huérfanos |
| `bloqueMb` | `4` | Tamaño de bloque que el broker recomienda al PCF |

**Camino B, a mano (CLI o portal).** Cuando el Banco no permite Bicep, o cuando hay que entender o auditar recurso por recurso. Se siguen las guías 03 a 13 en orden, usando la sección "Con Azure CLI" o "Con el portal" de cada una. Todas las propiedades que fija el Bicep están ahí, una por una; lo que no se puede fijar por CLI se muestra con `az rest`. Es más largo y más fácil de equivocar: al terminar cada guía, correr su "Verificación" antes de seguir.

Los dos caminos terminan en el mismo estado y las guías 14 a 23 son iguales para ambos.

## 3. Las guías

| Nº | Guía | Rol | ¿Imprescindible? |
| --- | --- | --- | --- |
| 01 | [App registration del broker](montaje/01-entra-broker.md) | Identidad | Imprescindible |
| 02 | [App registration del conector](montaje/02-entra-conector.md) | Identidad | Imprescindible (la credencial puede cambiar: secreto, certificado o identidad administrada del conector) |
| 03 | [Grupo de recursos](montaje/03-grupo-de-recursos.md) | Cloud | Imprescindible (puede ser uno existente) |
| 04 | [Identidad administrada](montaje/04-identidad-administrada.md) | Cloud | Imprescindible |
| 05 | [Observabilidad](montaje/05-observabilidad.md) | Cloud | Prescindible técnicamente, imprescindible para auditar |
| 06 | [Storage host](montaje/06-storage-host.md) | Cloud | Imprescindible en Flex Consumption |
| 07 | [Storage de adjuntos](montaje/07-storage-adjuntos.md) | Cloud | Imprescindible |
| 08 | [Asignaciones de rol](montaje/08-asignaciones-de-rol.md) | Cloud | Imprescindibles |
| 09 | [Key Vault](montaje/09-key-vault.md) | Cloud | Prescindible por decisión (hallazgo 6b) |
| 10 | [Function en Flex Consumption](montaje/10-function-flex.md) | Cloud | La Function sí; el plan es decisión |
| 11 | [Event Grid](montaje/11-event-grid.md) | Cloud | Imprescindible para el disparador elegido |
| 12 | [Defender for Storage](montaje/12-defender-for-storage.md) | Cloud | Prescindible por decisión (no habilitado) |
| 13 | [Presupuesto y alertas](montaje/13-presupuesto-y-alertas.md) | Cloud | Prescindible, recomendado |
| 14 | [Credencial federada del broker](montaje/14-credencial-federada.md) | Identidad | Imprescindible para cero secretos en el broker |
| 15 | [Easy Auth](montaje/15-easy-auth.md) | Cloud | Imprescindible |
| 16 | [Publicar el código](montaje/16-publicar-codigo.md) | Cloud | Imprescindible; el orden importa |
| 17 | [Secretos en Key Vault](montaje/17-secretos-key-vault.md) | Operador de secretos | Imprescindible si hay Key Vault |
| 18 | [Dataverse](montaje/18-dataverse.md) | Power Platform | Imprescindible |
| 19 | [Componente PCF](montaje/19-pcf.md) | Power Platform | Imprescindible |
| 20 | [Conector personalizado y DLP](montaje/20-conector-personalizado.md) | Power Platform | Imprescindible |
| 21 | [App canvas](montaje/21-app-canvas.md) | Power Platform | Es el producto |
| 22 | [Humo y entrega](montaje/22-humo-y-entrega.md) | Todos | Criterio de listo |
| 23 | [Operación](montaje/23-operacion.md) | Todos | Runbook de mantenimiento |

## 4. Lo que estas guías no cubren todavía

- **Red dentro del Banco**: VNet integration de la Function, NAT, private endpoints de Storage y Key Vault, y la ruta hacia IBM i y CM V8. Depende de S0 (`docs/guia-spikes-banco.md`); cuando se conozca, se agrega al Bicep (`virtualNetworkSubnetId`, private endpoint del vault, función `/api/diagnostico/red`) y una guía 24.
- **Conectividad real con Content Manager e IBM i**: certificados, puertos y perfil de servicio. Las guías dejan la configuración lista; la prueba es S4a y S6 adentro.
- **ALM**: exportación de la solución `cmv8ingesta` como paquete administrado entre entornos (dev, qa, prod) y pipelines. Hoy el montaje es por entorno con los scripts del repo.
