#!/usr/bin/env bash
# Despliega la infraestructura de spikes en un resource group.
# Uso: bash infra/desplegar.sh <resource-group> <region> [archivo.bicepparam]
# Requiere: az login hecho y suscripción seleccionada (az account set -s <id>).
set -euo pipefail

RG="${1:?resource group}"
LOCATION="${2:?region, ej. eastus2}"
PARAMS="${3:-$(dirname "${BASH_SOURCE[0]}")/spikes.bicepparam}"
TEMPLATE="$(dirname "${BASH_SOURCE[0]}")/main.bicep"

echo "Suscripción activa:"
az account show --query '{name:name, id:id, tenant:tenantId}' -o table

echo "Registrando providers necesarios (idempotente)..."
for ns in Microsoft.Web Microsoft.Storage Microsoft.EventGrid Microsoft.Insights Microsoft.OperationalInsights Microsoft.ManagedIdentity Microsoft.Security Microsoft.KeyVault Microsoft.App; do
  az provider register --namespace "$ns" --wait >/dev/null
done

echo "Verificando que $LOCATION soporte Flex Consumption con Java..."
az functionapp list-flexconsumption-runtimes --location "$LOCATION" --runtime java --query '[].version' -o tsv

az group create --name "$RG" --location "$LOCATION" --tags proyecto=CMV8_to_PP "ambiente=${AMBIENTE:-spike}" -o none

# config/appsettings de Bicep REEMPLAZA todos los app settings: ningún secreto se fija fuera de banda.
# El OBO del broker usa la identidad administrada como credencial federada (sin BROKER_CLIENT_SECRET).

# Hallazgo 6b: el usuario de AS400 viene del entorno (spikes.bicepparam usa readEnvironmentVariable) para no dejarlo en el repo.
LOCAL_SETTINGS="$(dirname "${BASH_SOURCE[0]}")/../function/local.settings.json"
if [ -z "${AS400_USER:-}" ] && [ -f "$LOCAL_SETTINGS" ]; then
  AS400_USER="$(jq -r '.Values.AS400_USER // empty' "$LOCAL_SETTINGS")"
  export AS400_USER
fi

echo "Validando plantilla..."
az deployment group validate --resource-group "$RG" --template-file "$TEMPLATE" --parameters "$PARAMS" -o none

echo "Desplegando..."
az deployment group create \
  --resource-group "$RG" \
  --name "cmv8-spikes-$(date +%Y%m%d%H%M%S)" \
  --template-file "$TEMPLATE" \
  --parameters "$PARAMS" \
  --query 'properties.outputs' -o json | tee "$(dirname "${BASH_SOURCE[0]}")/ultimo-despliegue.outputs.json"

echo "Listo. Salidas guardadas en infra/ultimo-despliegue.outputs.json"
