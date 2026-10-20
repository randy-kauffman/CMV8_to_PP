using './main.bicep'

// Parámetros para el sandbox de spikes fuera del Banco.
// Completar dataverseUrl con el entorno de desarrollo propio.
param prefijo = 'cmv8'
param javaVersion = '21'
param contenedorAdjuntos = 'cmv8-ingesta'
param dataverseUrl = 'https://org36e60d9d.crm.dynamics.com'

// Power Apps corre desde hosts regionales variables (*.island.powerapps.com): no se pueden listar. Ver main.bicep.
param origenesCors = ['*']

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
param crearSuscripcionEventGrid = true // 2026-10-19: código v2 desplegado; la suscripción valida el endpoint OnBlobCreated

// Hallazgo 6b: contraseñas en Key Vault. El usuario de AS400 se toma del entorno (desplegar.sh lo exporta desde
// function/local.settings.json) para no dejarlo en el repo; la contraseña NUNCA pasa por acá: infra/cargar-secretos.sh.
param keyVaultAccesoPublico = true
param oidOperadorKeyVault = 'eab4619b-d1f6-475c-a392-69aa8cf30e4f'
param as400Host = 'PUB400.COM'
param as400Usuario = readEnvironmentVariable('AS400_USER', '')
// Consulta de prueba contra el catálogo de PUB400 (la real la define el Banco): resuelve el nombre de una tabla de QSYS2.
param as400SqlCliente = 'SELECT TABLE_NAME AS IDCLIENTE, TABLE_SCHEMA AS IDCLIENTE2, TABLE_TEXT AS NOMBRE, TABLE_TYPE AS AFILIADOPADRE, SYSTEM_TABLE_NAME AS IDDUENO FROM QSYS2.SYSTABLES WHERE TABLE_SCHEMA = \'QSYS2\' AND TABLE_NAME = ? FETCH FIRST 1 ROWS ONLY'

// v2 (ADR-003). Hasta desplegar la Function v2 (que trae el timer Depurar), la red de ingesta queda en 7 días como en v1.
param diasRetencionAdjuntos = 7
// WORM es decisión del Banco y no se puede deshabilitar una vez puesto en el contenedor: en spikes, apagado.
param wormHabilitado = false
param observadaDias = 30
