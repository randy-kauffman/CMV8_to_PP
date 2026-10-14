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

@description('Habilitar Defender for Storage con escaneo de malware al subir (S3). Tiene costo.')
param habilitarDefender bool = false

@description('Crear la suscripción de Event Grid hacia la función OnScanResult. Requiere que el código ya esté desplegado.')
param crearSuscripcionEventGrid bool = false

@description('URL del entorno de Dataverse que consumirá la Function (S2, S5, S7).')
param dataverseUrl string = ''

@description('Modo de autorización del broker de SAS: dataverse (verifica acceso a la fila) o ninguna (solo S1).')
@allowed(['dataverse', 'ninguna'])
param autorizacionModo string = 'dataverse'

@description('Tamaño de bloque en MiB que el broker recomienda al PCF. Mayor = menos overhead por bloque, reintentos más caros.')
@minValue(1)
@maxValue(100)
param bloqueMb int = 4

@description('Días de retención de los adjuntos en Storage antes del borrado automático (red de seguridad).')
param diasRetencionAdjuntos int = 7

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

// Roles integrados
var rolBlobDataOwner = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', 'b7e6dc6d-f1e8-4753-8033-0f276bb0955b')
var rolBlobDataContributor = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', 'ba92f5b4-2d11-453d-a403-e96b0029c9fe')
var rolBlobDelegator = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', 'db58b8e5-c6ad-4a2a-8342-4190687cbf4a')
var rolQueueDataContributor = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '974c5e8b-45b9-4653-ba55-5f855dd0fb88')
var rolTableDataContributor = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '0a9a7e1f-b9d0-4cc4-a60d-0319b160aaa3')
var rolMonitoringMetricsPublisher = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '3913510d-42f4-4e42-8a64-420c390055eb')

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
  }

  resource lifecycle 'managementPolicies' = {
    name: 'default'
    properties: {
      policy: {
        rules: [
          {
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
        ]
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
  ]

  resource configuracion 'config' = {
    name: 'appsettings'
    properties: {
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
      DATAVERSE_URL: dataverseUrl
      SAS_UPLOAD_MINUTOS: '15'
      SAS_READ_MINUTOS: '5'
      COLA_INGESTA: 'ingesta-pendiente'
      AUTORIZACION_MODO: autorizacionModo
      SAS_LIGADA_USUARIO: requerirSasLigadaAUsuario ? 'true' : 'false'
      ESCANEO_REQUERIDO: habilitarDefender ? 'true' : 'false'
      ESTANCADA_MINUTOS: '15'
      SAS_BLOQUE_MB: string(bloqueMb)
    }
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
output storageHostName string = storageHost.name
output appInsightsName string = appInsights.name
output topicoScanName string = habilitarDefender ? topicoScan.name : ''
