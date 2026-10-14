using './main.bicep'

// Parámetros para el sandbox de spikes fuera del Banco.
// Completar dataverseUrl con el entorno de desarrollo propio.
param prefijo = 'cmv8'
param javaVersion = '21'
param contenedorAdjuntos = 'cmv8-ingesta'
param dataverseUrl = 'https://org36e60d9d.crm.dynamics.com'

// Power Apps corre desde hosts regionales variables (*.island.powerapps.com): no se pueden listar. Ver main.bicep.
param origenesCors = ['*']

// S1 corrió con 'ninguna'; desde S2 el broker verifica acceso en Dataverse con impersonación.
param autorizacionModo = 'dataverse'

// Sin firewall IP en el spike S1/S2. Para S1b/S3 agregar la IP pública propia.
param ipsPermitidas = []

// S1b: SAS ligada al usuario (sduoid) + token de Storage por OBO que el PCF manda como Bearer.
// Rollout: primero 'Log' (se registran las SAS no ligadas), después 'Block' (2026-10-14, S1b verificado).
param requerirSasLigadaAUsuario = true
param accionSasNoLigada = 'Block'

// App del broker (Easy Auth + OBO). Sin secreto: la app registration confía en la identidad administrada (credencial federada).
param brokerClientId = 'fe2f40d6-cac3-451b-b24d-ab9e93c80482'
param brokerTenantId = '7bcfee03-896e-4ad3-947b-8a2d9ce17974'

// S3: pasar a true. Requiere el plan Defender for Storage disponible en la suscripción.
param habilitarDefender = false

// Pasar a true SOLO después de desplegar el código de la Function (la suscripción valida el endpoint).
param crearSuscripcionEventGrid = false
