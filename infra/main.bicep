// Infraestructura de CMV8_to_PP para los spikes fuera del Banco (ADR-001 B1 + ADR-002).
// Alcance: resource group. Despliegue: infra/desplegar.sh
//
// Recursos:
//   - Log Analytics + Application Insights (auth local deshabilitada)
//   - Identidad administrada asignada por el usuario (una por solución)
//   - Storage HOST: lo usa el runtime de Functions y el paquete de despliegue
//   - Storage ADJUNTOS: cuenta endurecida con el contenedor cmv8-ingesta (Shared Key OFF,
//     sin acceso anónimo, TLS 1.2, soft delete, lifecycle, CORS para Power Apps, firewall IP opcional,
//     política de SAS con opción de exigir SAS ligada a usuario)
//   - Plan Flex Consumption + Function App Java 21
//   - Asignaciones de rol de mínimo privilegio
//   - Opcional: Defender for Storage con escaneo al subir y tópico de Event Grid con resultados
//   - Opcional: suscripción de Event Grid hacia la función OnScanResult (requiere código desplegado)

targetScope = 'resourceGroup'

@description('Región. Debe soportar Flex Consumption.')
param location string = resourceGroup().location

@description('Prefijo corto para nombrar recursos (minúsculas, sin guiones).')
@minLength(3)
@maxLength(8)
param prefijo string = 'cmv8'

@description('Versión de Java para la Function App.')
@allowed(['17', '21', '25'])
param javaVersion string = '21'

@description('Máximo de instancias del plan Flex Consumption.')
@minValue(40)
@maxValue(1000)
param maximumInstanceCount int = 100

@description('Memoria por instancia en MB.')
@allowed([2048, 4096])
param instanceMemoryMB int = 2048

@description('Nombre del contenedor de adjuntos de esta solución.')
param contenedorAdjuntos string = 'cmv8-ingesta'

@description('''Orígenes permitidos por CORS en Storage y en la Function. Power Apps ejecuta las apps desde hosts
regionales variables (p. ej. authoring.us-il301.gateway.prod.island.powerapps.com), por lo que no se pueden listar.
CORS no es un control de seguridad: la autorización la dan la SAS (Storage) y Entra ID (Function).''')
param origenesCors array = ['*']

@description('IPs o CIDR públicas permitidas en el firewall del Storage de adjuntos. Vacío = sin firewall (solo para spikes).')
param ipsPermitidas array = []

@description('Exigir que toda SAS de delegación de usuario esté ligada a un usuario (S1b). Acción: Log o Block.')
param requerirSasLigadaAUsuario bool = false

@description('Acción cuando una SAS no cumple la política de ligado a usuario.')
@allowed(['Log', 'Block', 'None'])
param accionSasNoLigada string = 'Log'

@description('Client id de la app registration del broker (Easy Auth + OBO hacia Storage, S1b).')
param brokerClientId string = ''

@description('Tenant Entra del broker (autoridad del OBO).')
param brokerTenantId string = tenant().tenantId


@description('Habilitar Defender for Storage con escaneo de malware al subir (S3). Tiene costo.')
param habilitarDefender bool = false

@description('Crear la suscripción de Event Grid hacia la función OnScanResult. Requiere que el código ya esté desplegado.')
param crearSuscripcionEventGrid bool = false

@description('URL del entorno de Dataverse que consumirá la Function (S2, S5, S7).')
param dataverseUrl string = ''

@description('Tamaño de bloque en MiB que el broker recomienda al PCF. Mayor = menos overhead por bloque, reintentos más caros.')
@minValue(1)
@maxValue(100)
param bloqueMb int = 4

@description('''Días que un blob puede quedar en el contenedor de ingesta antes del borrado automático. Es la última red:
la fecha de depuración de la Azure Table manda (ADR-003 §4.5), por eso 60 y no 7. En spikes sin la v2 de la Function, 7.''')
param diasRetencionAdjuntos int = 60

@description('Contenedor de lo que se queda en Blob: retenidos tras archivar, solo-blob y diferidos en espera (ADR-003 §7).')
param contenedorAlmacenados string = 'cmv8-almacenados'

@description('Días en Hot antes de bajar a Cool en el contenedor de almacenados. Nunca Archive: la SAS de lectura no serviría.')
param diasACool int = 30

@description('Días en Cool antes de bajar a Cold en el contenedor de almacenados.')
param diasACold int = 180

@description('''Inmutabilidad WORM por tipo de documento (ADR-003 §4.5): habilita versionado en la cuenta e inmutabilidad a nivel de versión
en el contenedor de almacenados, y da Storage Blob Data Owner a la identidad SOLO sobre ese contenedor (Set Blob Immutability Policy
exige blobs/immutableStorage/runAsSuperUser/action; Owner es el rol integrado mínimo que lo incluye, Learn). Una vez habilitada
la inmutabilidad en el contenedor no se puede deshabilitar. Decisión del Banco.''')
param wormHabilitado bool = false

@description('Con WORM: la Function bloquea la política al ponerla (solo bloqueada cumple regulación). false solo en pruebas.')
param wormBloquear bool = true

@description('Días que un adjunto en Observada espera corrección antes de pasar a Rechazado (ADR-003 §4.5). Decisión del Banco.')
param observadaDias int = 30

@description('Minutos que la Function cachea el catálogo de tipos de documento en memoria (ADR-003 §6).')
param catalogoCacheMinutos int = 10

@description('''Secretos en Key Vault (hallazgo 6b). Las contraseñas de AS400 y CMIS NUNCA van en parámetros ni en app settings:
se cargan fuera de banda con infra/cargar-secretos.sh (secretos as400-password y cmis-password) y la Function las lee
como referencias @Microsoft.KeyVault(...) con su identidad administrada. true en el spike (sin VNet); en el Banco false + private endpoint.''')
param keyVaultAccesoPublico bool = true

@description('Object id de la persona que carga los secretos (rol Key Vault Secrets Officer). Vacío: nadie fuera de la Function.')
param oidOperadorKeyVault string = ''

@description('AS400 (jt400). Vacío: la Function no recibe configuración de AS400. La contraseña viene de Key Vault.')
param as400Host string = ''
param as400Usuario string = ''
@description('SQL con un solo ? que devuelve IDCLIENTE, IDCLIENTE2, NOMBRE, AFILIADOPADRE, IDDUENO.')
param as400SqlCliente string = ''

@description('''v2: consultas con nombre al AS400 como JSON {"nombre": "SELECT ... WHERE X = :parametro"} (ADR-003 §6). El SQL vive
solo acá, nunca en Dataverse. Si está vacío, la Function usa as400SqlCliente como consulta "cliente".''')
param as400ConsultasJson string = ''

@description('v2: prefijo de wire que se quita del id de propiedad para el nombre canónico (vacío = último punto o dos puntos).')
param cmisPrefijoPropiedades string = ''

@description('v2: nombre canónico de la propiedad que trae el ID corto del tipo (regla de CMCourier). Vacío = prefijo del nombre visible.')
param cmisPropiedadIdCorto string = ''

@description('v2: plantilla de la carpeta derivada de un tipo descubierto; {localName} se reemplaza.')
param cmisCarpetaPlantilla string = '/{localName}'

@description('Correo para la alerta de conflictos de catálogo (M10). Vacío = sin alerta ni grupo de acción.')
param correoAlertas string = ''

@description('Object id (usuario o grupo) que puede LEER las Azure Tables de estado operativo para diagnóstico. Vacío = nadie además de la Function.')
param oidOperadorTablas string = ''

@description('CMIS (Content Manager). Vacío: la Function no recibe configuración CMIS. La contraseña viene de Key Vault.')
param cmisUrl string = ''
param cmisUsuario string = ''
param cmisRepositorio string = ''

// ---------------------------------------------------------------------------
// Nombres
// ---------------------------------------------------------------------------
var token = toLower(uniqueString(subscription().id, resourceGroup().id, location))
var nombreHost = '${prefijo}host${take(token, 8)}'
var nombreAdjuntos = '${prefijo}adj${take(token, 8)}'
var nombreFunction = '${prefijo}-func-${take(token, 8)}'
var nombrePlan = '${prefijo}-plan-${take(token, 8)}'
var nombreIdentidad = '${prefijo}-mi-${take(token, 8)}'
var nombreLog = '${prefijo}-log-${take(token, 8)}'
var nombreAppi = '${prefijo}-appi-${take(token, 8)}'
var nombreTopico = '${prefijo}-egt-scan-${take(token, 8)}'
var contenedorDespliegue = 'app-package-${take(token, 8)}'
var nombreKeyVault = '${prefijo}-kv-${take(token, 8)}'

// Roles integrados
var rolBlobDataOwner = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', 'b7e6dc6d-f1e8-4753-8033-0f276bb0955b')
var rolBlobDataContributor = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', 'ba92f5b4-2d11-453d-a403-e96b0029c9fe')
var rolBlobDelegator = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', 'db58b8e5-c6ad-4a2a-8342-4190687cbf4a')
var rolQueueDataContributor = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '974c5e8b-45b9-4653-ba55-5f855dd0fb88')
var rolTableDataContributor = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '0a9a7e1f-b9d0-4cc4-a60d-0319b160aaa3')
var rolMonitoringMetricsPublisher = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '3913510d-42f4-4e42-8a64-420c390055eb')
var rolKeyVaultSecretsUser = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '4633458b-17de-408a-b874-0445c86b69e6')
var rolKeyVaultSecretsOfficer = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', 'b86a8fe4-44ce-4948-aee5-eccb2c155cd7')

// Referencias de Key Vault sin versión: la rotación del secreto no requiere redeploy (se refresca en 24 h o al reiniciar).
var ajustesAs400 = empty(as400Host) ? {} : {
  AS400_HOST: as400Host
  AS400_USER: as400Usuario
  AS400_SECURE: 'true'
  AS400_SQL_CLIENTE: as400SqlCliente
  AS400_CONSULTAS_JSON: as400ConsultasJson
  AS400_PASSWORD: '@Microsoft.KeyVault(VaultName=${nombreKeyVault};SecretName=as400-password)'
}
var ajustesCmis = empty(cmisUrl) ? {} : {
  CMIS_URL: cmisUrl
  CMIS_USER: cmisUsuario
  CMIS_REPOSITORY_ID: cmisRepositorio
  CMIS_PREFIJO_PROPIEDADES: cmisPrefijoPropiedades
  CMIS_PROPIEDAD_ID_CORTO: cmisPropiedadIdCorto
  CMIS_CARPETA_PLANTILLA: cmisCarpetaPlantilla
  CMIS_PASSWORD: '@Microsoft.KeyVault(VaultName=${nombreKeyVault};SecretName=cmis-password)'
}

var reglasIp = [for ip in ipsPermitidas: { value: ip, action: 'Allow' }]

// ---------------------------------------------------------------------------
// Observabilidad
// ---------------------------------------------------------------------------
resource logAnalytics 'Microsoft.OperationalInsights/workspaces@2023-09-01' = {
  name: nombreLog
  location: location
  properties: {
    retentionInDays: 30
    sku: { name: 'PerGB2018' }
  }
}

resource appInsights 'Microsoft.Insights/components@2020-02-02' = {
  name: nombreAppi
  location: location
  kind: 'web'
  properties: {
    Application_Type: 'web'
    WorkspaceResourceId: logAnalytics.id
    DisableLocalAuth: true
  }
}

// ---------------------------------------------------------------------------
// Identidad de la solución
// ---------------------------------------------------------------------------
resource identidad 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: nombreIdentidad
  location: location
}

// ---------------------------------------------------------------------------
// Storage HOST (runtime de Functions + paquete de despliegue)
// ---------------------------------------------------------------------------
resource storageHost 'Microsoft.Storage/storageAccounts@2023-05-01' = {
  name: nombreHost
  location: location
  kind: 'StorageV2'
  sku: { name: 'Standard_LRS' }
  properties: {
    accessTier: 'Hot'
    minimumTlsVersion: 'TLS1_2'
    supportsHttpsTrafficOnly: true
    allowBlobPublicAccess: false
    allowSharedKeyAccess: false
    defaultToOAuthAuthentication: true
    publicNetworkAccess: 'Enabled'
    networkAcls: {
      defaultAction: 'Allow'
      bypass: 'AzureServices'
    }
  }

  resource blobs 'blobServices' = {
    name: 'default'
    resource despliegue 'containers' = {
      name: contenedorDespliegue
      properties: { publicAccess: 'None' }
    }
  }
}

// ---------------------------------------------------------------------------
// Storage ADJUNTOS (cuenta endurecida, estándar de adjuntos del Banco)
// ---------------------------------------------------------------------------
resource storageAdjuntos 'Microsoft.Storage/storageAccounts@2026-06-01' = {
  name: nombreAdjuntos
  location: location
  kind: 'StorageV2'
  sku: { name: 'Standard_LRS' }
  properties: {
    accessTier: 'Hot'
    minimumTlsVersion: 'TLS1_2'
    supportsHttpsTrafficOnly: true
    allowBlobPublicAccess: false
    allowSharedKeyAccess: false
    defaultToOAuthAuthentication: true
    allowCrossTenantReplication: false
    publicNetworkAccess: 'Enabled'
    networkAcls: {
      defaultAction: empty(ipsPermitidas) ? 'Allow' : 'Deny'
      bypass: 'AzureServices'
      ipRules: reglasIp
    }
    sasPolicy: {
      sasExpirationPeriod: '00.01:00:00'
      expirationAction: 'Block'
      requireUserBoundUserDelegationSas: requerirSasLigadaAUsuario
      requireUserBoundUserDelegationSasAction: accionSasNoLigada
    }
  }

  resource blobs 'blobServices' = {
    name: 'default'
    properties: {
      deleteRetentionPolicy: { enabled: true, days: 7 }
      containerDeleteRetentionPolicy: { enabled: true, days: 7 }
      // WORM a nivel de versión exige versionado (Learn). Sin WORM no se paga por versiones.
      isVersioningEnabled: wormHabilitado
      cors: {
        corsRules: [
          {
            allowedOrigins: origenesCors
            allowedMethods: ['GET', 'HEAD', 'PUT', 'OPTIONS']
            allowedHeaders: ['*']
            exposedHeaders: ['*']
            maxAgeInSeconds: 3600
          }
        ]
      }
    }
    resource ingesta 'containers' = {
      name: contenedorAdjuntos
      properties: { publicAccess: 'None' }
    }
    resource almacenados 'containers' = {
      name: contenedorAlmacenados
      properties: {
        publicAccess: 'None'
        immutableStorageWithVersioning: { enabled: wormHabilitado }
      }
    }
  }

  // Estado operativo de la ingesta (ADR-003 §3): la verdad operativa vive acá, Dataverse guarda la de negocio.
  resource tablas 'tableServices' = {
    name: 'default'
    resource ingesta 'tables' = {
      name: 'ingesta'
    }
    resource depuracion 'tables' = {
      name: 'depuracion'
    }
  }

  resource lifecycle 'managementPolicies' = {
    name: 'default'
    properties: {
      policy: {
        rules: concat([
          {
            // Última red del contenedor de ingesta: lo que está en vuelo lo gobierna la fecha de depuración (timer Depurar).
            name: 'borrar-adjuntos-huerfanos'
            enabled: true
            type: 'Lifecycle'
            definition: {
              filters: {
                blobTypes: ['blockBlob']
                prefixMatch: ['${contenedorAdjuntos}/']
              }
              actions: {
                baseBlob: {
                  delete: { daysAfterModificationGreaterThan: diasRetencionAdjuntos }
                }
              }
            }
          }
          {
            // Almacenados: sin borrado por ciclo de vida (lo hace el timer Depurar respetando estados); solo niveles de acceso.
            name: 'niveles-almacenados'
            enabled: true
            type: 'Lifecycle'
            definition: {
              filters: {
                blobTypes: ['blockBlob']
                prefixMatch: ['${contenedorAlmacenados}/']
              }
              actions: {
                baseBlob: {
                  tierToCool: { daysAfterModificationGreaterThan: diasACool }
                  tierToCold: { daysAfterModificationGreaterThan: diasACold }
                }
              }
            }
          }
        ], wormHabilitado ? [
          {
            // Con versionado, una resubida al mismo nombre en ingesta deja versiones viejas: se limpian.
            name: 'borrar-versiones-viejas-ingesta'
            enabled: true
            type: 'Lifecycle'
            definition: {
              filters: {
                blobTypes: ['blockBlob']
                prefixMatch: ['${contenedorAdjuntos}/']
              }
              actions: {
                version: {
                  delete: { daysAfterCreationGreaterThan: 7 }
                }
              }
            }
          }
        ] : [])
      }
    }
  }
}

resource diagAdjuntos 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = {
  name: 'a-log-analytics'
  scope: storageAdjuntos::blobs
  properties: {
    workspaceId: logAnalytics.id
    logs: [
      { category: 'StorageRead', enabled: true }
      { category: 'StorageWrite', enabled: true }
      { category: 'StorageDelete', enabled: true }
    ]
    metrics: [
      { category: 'Transaction', enabled: true }
    ]
  }
}

// ---------------------------------------------------------------------------
// Roles: identidad de la solución
// ---------------------------------------------------------------------------
resource rolHostOwner 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(storageHost.id, identidad.id, rolBlobDataOwner)
  scope: storageHost
  properties: {
    roleDefinitionId: rolBlobDataOwner
    principalId: identidad.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

resource rolHostQueue 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(storageHost.id, identidad.id, rolQueueDataContributor)
  scope: storageHost
  properties: {
    roleDefinitionId: rolQueueDataContributor
    principalId: identidad.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

resource rolHostTable 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(storageHost.id, identidad.id, rolTableDataContributor)
  scope: storageHost
  properties: {
    roleDefinitionId: rolTableDataContributor
    principalId: identidad.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

// Mínimo privilegio sobre adjuntos: Contributor SOLO en el contenedor de esta solución...
resource rolAdjuntosContenedor 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(storageAdjuntos::blobs::ingesta.id, identidad.id, rolBlobDataContributor)
  scope: storageAdjuntos::blobs::ingesta
  properties: {
    roleDefinitionId: rolBlobDataContributor
    principalId: identidad.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

// Almacenados: Contributor, o Data Owner acotado al contenedor cuando hay WORM (Set Blob Immutability Policy lo exige).
resource rolAlmacenadosContenedor 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(storageAdjuntos::blobs::almacenados.id, identidad.id, wormHabilitado ? rolBlobDataOwner : rolBlobDataContributor)
  scope: storageAdjuntos::blobs::almacenados
  properties: {
    roleDefinitionId: wormHabilitado ? rolBlobDataOwner : rolBlobDataContributor
    principalId: identidad.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

// Tablas de estado operativo: Table Data Contributor acotado a cada tabla, no a la cuenta.
resource rolTablaIngesta 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(storageAdjuntos::tablas::ingesta.id, identidad.id, rolTableDataContributor)
  scope: storageAdjuntos::tablas::ingesta
  properties: {
    roleDefinitionId: rolTableDataContributor
    principalId: identidad.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

resource rolTablaDepuracion 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(storageAdjuntos::tablas::depuracion.id, identidad.id, rolTableDataContributor)
  scope: storageAdjuntos::tablas::depuracion
  properties: {
    roleDefinitionId: rolTableDataContributor
    principalId: identidad.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

// Lectura de las tablas de estado para un operador (diagnóstico), acotada a cada tabla.
var rolTableDataReader = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '76199698-9eea-4c19-bc75-cec21354c6b6')

resource rolOperadorTablaIngesta 'Microsoft.Authorization/roleAssignments@2022-04-01' = if (!empty(oidOperadorTablas)) {
  name: guid(storageAdjuntos::tablas::ingesta.id, oidOperadorTablas, rolTableDataReader)
  scope: storageAdjuntos::tablas::ingesta
  properties: {
    roleDefinitionId: rolTableDataReader
    principalId: oidOperadorTablas
  }
}

resource rolOperadorTablaDepuracion 'Microsoft.Authorization/roleAssignments@2022-04-01' = if (!empty(oidOperadorTablas)) {
  name: guid(storageAdjuntos::tablas::depuracion.id, oidOperadorTablas, rolTableDataReader)
  scope: storageAdjuntos::tablas::depuracion
  properties: {
    roleDefinitionId: rolTableDataReader
    principalId: oidOperadorTablas
  }
}

// ...y Delegator a nivel de cuenta, porque la clave de delegación de usuario es de cuenta.
resource rolAdjuntosDelegator 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(storageAdjuntos.id, identidad.id, rolBlobDelegator)
  scope: storageAdjuntos
  properties: {
    roleDefinitionId: rolBlobDelegator
    principalId: identidad.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

resource rolAppInsights 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(appInsights.id, identidad.id, rolMonitoringMetricsPublisher)
  scope: appInsights
  properties: {
    roleDefinitionId: rolMonitoringMetricsPublisher
    principalId: identidad.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

// ---------------------------------------------------------------------------
// Key Vault: contraseñas de AS400 y CMIS (hallazgo 6b). RBAC, sin access policies.
// ---------------------------------------------------------------------------
resource keyVault 'Microsoft.KeyVault/vaults@2024-11-01' = {
  name: nombreKeyVault
  location: location
  tags: { proyecto: 'CMV8_to_PP' }
  properties: {
    tenantId: tenant().tenantId
    sku: { family: 'A', name: 'standard' }
    enableRbacAuthorization: true
    enableSoftDelete: true
    softDeleteRetentionInDays: 7
    publicNetworkAccess: keyVaultAccesoPublico ? 'Enabled' : 'Disabled'
    networkAcls: {
      bypass: 'AzureServices'
      defaultAction: keyVaultAccesoPublico ? 'Allow' : 'Deny'
    }
  }
}

// La Function solo LEE secretos (resuelve las referencias @Microsoft.KeyVault con su identidad).
resource rolKeyVaultFunction 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(keyVault.id, identidad.id, rolKeyVaultSecretsUser)
  scope: keyVault
  properties: {
    roleDefinitionId: rolKeyVaultSecretsUser
    principalId: identidad.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

// Quien carga los secretos (persona o pipeline), fuera de banda.
resource rolKeyVaultAdministrador 'Microsoft.Authorization/roleAssignments@2022-04-01' = if (!empty(oidOperadorKeyVault)) {
  name: guid(keyVault.id, oidOperadorKeyVault, rolKeyVaultSecretsOfficer)
  scope: keyVault
  properties: {
    roleDefinitionId: rolKeyVaultSecretsOfficer
    principalId: oidOperadorKeyVault
    principalType: 'User'
  }
}

// ---------------------------------------------------------------------------
// Function App en Flex Consumption
// ---------------------------------------------------------------------------
resource plan 'Microsoft.Web/serverfarms@2024-04-01' = {
  name: nombrePlan
  location: location
  kind: 'functionapp'
  sku: {
    tier: 'FlexConsumption'
    name: 'FC1'
  }
  properties: {
    reserved: true
  }
}

resource functionApp 'Microsoft.Web/sites@2024-04-01' = {
  name: nombreFunction
  location: location
  kind: 'functionapp,linux'
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: {
      '${identidad.id}': {}
    }
  }
  properties: {
    serverFarmId: plan.id
    httpsOnly: true
    // Las referencias @Microsoft.KeyVault se resuelven con la identidad asignada por el usuario (no hay identidad del sistema).
    keyVaultReferenceIdentity: identidad.id
    siteConfig: {
      minTlsVersion: '1.2'
      http20Enabled: true
      // El PCF llama al broker desde el navegador: la Function también necesita CORS.
      cors: {
        allowedOrigins: origenesCors
        supportCredentials: false
      }
    }
    functionAppConfig: {
      deployment: {
        storage: {
          type: 'blobContainer'
          value: '${storageHost.properties.primaryEndpoints.blob}${contenedorDespliegue}'
          authentication: {
            type: 'UserAssignedIdentity'
            userAssignedIdentityResourceId: identidad.id
          }
        }
      }
      scaleAndConcurrency: {
        maximumInstanceCount: maximumInstanceCount
        instanceMemoryMB: instanceMemoryMB
      }
      runtime: {
        name: 'java'
        version: javaVersion
      }
    }
  }
  dependsOn: [
    rolHostOwner
    rolHostQueue
    rolHostTable
    rolKeyVaultFunction
  ]

  resource configuracion 'config' = {
    name: 'appsettings'
    // Sin BROKER_CLIENT_SECRET: el OBO del broker usa la identidad administrada (AZURE_CLIENT_ID) como
    // credencial federada de la app registration. El secreto solo existe en desarrollo local.
    // AS400_PASSWORD y CMIS_PASSWORD: referencias a Key Vault, nunca valores.
    properties: union({
      AzureWebJobsStorage__accountName: storageHost.name
      AzureWebJobsStorage__credential: 'managedidentity'
      AzureWebJobsStorage__clientId: identidad.properties.clientId
      APPLICATIONINSIGHTS_CONNECTION_STRING: appInsights.properties.ConnectionString
      APPLICATIONINSIGHTS_AUTHENTICATION_STRING: 'ClientId=${identidad.properties.clientId};Authorization=AAD'
      // azure-identity: DefaultAzureCredential / ManagedIdentityCredential toman este client id
      AZURE_CLIENT_ID: identidad.properties.clientId
      ADJUNTOS_STORAGE_ACCOUNT: storageAdjuntos.name
      ADJUNTOS_BLOB_ENDPOINT: storageAdjuntos.properties.primaryEndpoints.blob
      ADJUNTOS_CONTAINER: contenedorAdjuntos
      ADJUNTOS_CONTAINER_ALMACENADOS: contenedorAlmacenados
      ADJUNTOS_TABLE_ENDPOINT: storageAdjuntos.properties.primaryEndpoints.table
      TABLA_INGESTA: 'ingesta'
      TABLA_DEPURACION: 'depuracion'
      OBSERVADA_DIAS: string(observadaDias)
      WORM_HABILITADO: wormHabilitado ? 'true' : 'false'
      WORM_BLOQUEAR: wormBloquear ? 'true' : 'false'
      CATALOGO_CACHE_MINUTOS: string(catalogoCacheMinutos)
      DATAVERSE_URL: dataverseUrl
      SAS_UPLOAD_MINUTOS: '15'
      SAS_READ_MINUTOS: '5'
      COLA_INGESTA: 'ingesta-pendiente'
      SAS_LIGADA_USUARIO: requerirSasLigadaAUsuario ? 'true' : 'false'
      ESCANEO_REQUERIDO: habilitarDefender ? 'true' : 'false'
      ESTANCADA_MINUTOS: '15'
      SAS_BLOQUE_MB: string(bloqueMb)
      BROKER_CLIENT_ID: brokerClientId
      BROKER_TENANT_ID: brokerTenantId
    }, ajustesAs400, ajustesCmis)
  }
}

// M10: los rechazos de Easy Auth y los logs de la Function a Log Analytics (hoy no quedaban en ningún lado nuestro).
resource diagFunction 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = {
  name: 'a-log-analytics'
  scope: functionApp
  properties: {
    workspaceId: logAnalytics.id
    logs: [
      { category: 'FunctionAppLogs', enabled: true }
      { category: 'AppServiceAuthenticationLogs', enabled: true }
    ]
  }
}

// Alerta de conflictos de catálogo (ADR-003 §4.4): el timer VigilarCatalogo y el worker registran cada conflicto nuevo
// con un WARNING que empieza igual; la regla lo busca cada 15 minutos.
resource grupoAlertas 'Microsoft.Insights/actionGroups@2023-01-01' = if (!empty(correoAlertas)) {
  name: '${prefijo}-ag-${take(token, 8)}'
  location: 'global'
  properties: {
    groupShortName: 'cmv8'
    enabled: true
    emailReceivers: [ { name: 'administrador-documental', emailAddress: correoAlertas, useCommonAlertSchema: true } ]
  }
}

resource alertaConflictos 'Microsoft.Insights/scheduledQueryRules@2023-03-15-preview' = if (!empty(correoAlertas)) {
  name: '${prefijo}-alerta-conflictos-${take(token, 8)}'
  location: location
  properties: {
    displayName: 'CMV8: conflicto de catálogo nuevo'
    severity: 2
    enabled: true
    evaluationFrequency: 'PT15M'
    windowSize: 'PT15M'
    scopes: [ logAnalytics.id ]
    criteria: {
      allOf: [
        {
          query: 'AppTraces | where Message startswith "Conflicto de catálogo registrado"'
          timeAggregation: 'Count'
          operator: 'GreaterThan'
          threshold: 0
        }
      ]
    }
    actions: { actionGroups: [ grupoAlertas.id ] }
  }
}

// ---------------------------------------------------------------------------
// Opcional: Defender for Storage + tópico de resultados de escaneo (S3)
// ---------------------------------------------------------------------------
resource topicoScan 'Microsoft.EventGrid/topics@2025-02-15' = if (habilitarDefender) {
  name: nombreTopico
  location: location
  identity: { type: 'SystemAssigned' }
  properties: {
    inputSchema: 'EventGridSchema'
    publicNetworkAccess: 'Enabled'
    disableLocalAuth: false
  }
}

resource defender 'Microsoft.Security/defenderForStorageSettings@2025-07-01-preview' = if (habilitarDefender) {
  name: 'current'
  scope: storageAdjuntos
  properties: {
    isEnabled: true
    overrideSubscriptionLevelSettings: true
    malwareScanning: {
      onUpload: {
        isEnabled: true
        capGBPerMonth: 100
      }
      scanResultsEventGridTopicResourceId: topicoScan.id
      blobScanResultsOptions: 'BlobIndexTags'
      automatedResponse: 'None'
    }
    sensitiveDataDiscovery: { isEnabled: false }
  }
}

resource suscripcionScan 'Microsoft.EventGrid/topics/eventSubscriptions@2025-02-15' = if (habilitarDefender && crearSuscripcionEventGrid) {
  parent: topicoScan
  name: 'a-function-onscanresult'
  properties: {
    eventDeliverySchema: 'EventGridSchema'
    destination: {
      endpointType: 'AzureFunction'
      properties: {
        resourceId: '${functionApp.id}/functions/OnScanResult'
        maxEventsPerBatch: 1
        preferredBatchSizeInKilobytes: 64
      }
    }
    retryPolicy: {
      maxDeliveryAttempts: 10
      eventTimeToLiveInMinutes: 1440
    }
  }
}

// Fallback si Defender no está aprobado: BlobCreated directo desde la cuenta de adjuntos
resource topicoSistemaAdjuntos 'Microsoft.EventGrid/systemTopics@2025-02-15' = if (!habilitarDefender && crearSuscripcionEventGrid) {
  name: '${prefijo}-egst-adj-${take(token, 8)}'
  location: location
  properties: {
    source: storageAdjuntos.id
    topicType: 'Microsoft.Storage.StorageAccounts'
  }
}

resource suscripcionBlobCreated 'Microsoft.EventGrid/systemTopics/eventSubscriptions@2025-02-15' = if (!habilitarDefender && crearSuscripcionEventGrid) {
  parent: topicoSistemaAdjuntos
  name: 'a-function-onblobcreated'
  properties: {
    eventDeliverySchema: 'EventGridSchema'
    filter: {
      includedEventTypes: ['Microsoft.Storage.BlobCreated']
      subjectBeginsWith: '/blobServices/default/containers/${contenedorAdjuntos}/'
    }
    destination: {
      endpointType: 'AzureFunction'
      properties: {
        resourceId: '${functionApp.id}/functions/OnBlobCreated'
        maxEventsPerBatch: 1
        preferredBatchSizeInKilobytes: 64
      }
    }
  }
}

// ---------------------------------------------------------------------------
// Salidas
// ---------------------------------------------------------------------------
output functionAppName string = functionApp.name
output functionAppHostName string = functionApp.properties.defaultHostName
output identidadClientId string = identidad.properties.clientId
output identidadPrincipalId string = identidad.properties.principalId
output storageAdjuntosName string = storageAdjuntos.name
output storageAdjuntosBlobEndpoint string = storageAdjuntos.properties.primaryEndpoints.blob
output contenedorAdjuntosName string = contenedorAdjuntos
output contenedorAlmacenadosName string = contenedorAlmacenados
output storageAdjuntosTableEndpoint string = storageAdjuntos.properties.primaryEndpoints.table
output storageHostName string = storageHost.name
output appInsightsName string = appInsights.name
output keyVaultName string = keyVault.name
output topicoScanName string = habilitarDefender ? topicoScan.name : ''
