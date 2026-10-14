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
for ns in Microsoft.Web Microsoft.Storage Microsoft.EventGrid Microsoft.Insights Microsoft.OperationalInsights Microsoft.ManagedIdentity Microsoft.Security; do
  az provider register --namespace "$ns" --wait >/dev/null
done

echo "Verificando que $LOCATION soporte Flex Consumption con Java..."
az functionapp list-flexconsumption-runtimes --location "$LOCATION" --runtime java --query '[].version' -o tsv

az group create --name "$RG" --location "$LOCATION" --tags proyecto=CMV8_to_PP ambiente=spike -o none

# config/appsettings de Bicep REEMPLAZA todos los app settings. BROKER_CLIENT_SECRET (S1b) se fija fuera de banda:
# se lee antes (solo en memoria, nunca a disco ni a consola) y se vuelve a aplicar después del despliegue.
FUNC="$(az functionapp list -g "$RG" --query "[?starts_with(name, 'cmv8-func-')].name | [0]" -o tsv 2>/dev/null || true)"
SECRETO_BROKER=""
if [ -n "$FUNC" ]; then
  SECRETO_BROKER="$(az functionapp config appsettings list -g "$RG" -n "$FUNC" \
    --query "[?name=='BROKER_CLIENT_SECRET'].value | [0]" -o tsv 2>/dev/null || true)"
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

if [ -n "$FUNC" ] && [ -n "$SECRETO_BROKER" ]; then
  ACTUAL="$(az functionapp config appsettings list -g "$RG" -n "$FUNC" \
    --query "[?name=='BROKER_CLIENT_SECRET'].value | [0]" -o tsv 2>/dev/null || true)"
  if [ -z "$ACTUAL" ]; then
    echo "Restaurando BROKER_CLIENT_SECRET (fuera de banda) en $FUNC..."
    az functionapp config appsettings set -g "$RG" -n "$FUNC" --settings "BROKER_CLIENT_SECRET=$SECRETO_BROKER" -o none
  fi
fi
unset SECRETO_BROKER ACTUAL

echo "Listo. Salidas guardadas en infra/ultimo-despliegue.outputs.json"
