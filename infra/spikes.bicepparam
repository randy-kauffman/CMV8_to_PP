using './main.bicep'

// Parámetros para el sandbox de spikes fuera del Banco.
// Completar dataverseUrl con el entorno de desarrollo propio.
param prefijo = 'cmv8'
param javaVersion = '21'
param contenedorAdjuntos = 'cmv8-ingesta'
param dataverseUrl = 'https://org36e60d9d.crm.dynamics.com'

// El test harness del PCF corre en localhost:8181; en el Banco se quita.
param origenesCors = [
  'https://apps.powerapps.com'
  'https://make.powerapps.com'
  'http://localhost:8181'
]

// S1 corre sin Dataverse; para S2 en adelante pasar a 'dataverse'.
param autorizacionModo = 'ninguna'

// Sin firewall IP en el spike S1/S2. Para S1b/S3 agregar la IP pública propia.
param ipsPermitidas = []

// S1b: pasar a true con accionSasNoLigada = 'Block' una vez que el PCF envíe el bearer del usuario.
param requerirSasLigadaAUsuario = false
param accionSasNoLigada = 'Log'

// S3: pasar a true. Requiere el plan Defender for Storage disponible en la suscripción.
param habilitarDefender = false

// Pasar a true SOLO después de desplegar el código de la Function (la suscripción valida el endpoint).
param crearSuscripcionEventGrid = false
