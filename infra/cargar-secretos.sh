#!/usr/bin/env bash
# Carga en Key Vault las contraseñas que la Function lee como referencias @Microsoft.KeyVault (hallazgo 6b).
# Uso: bash infra/cargar-secretos.sh <resource-group> [function/local.settings.json]
# Lee AS400_PASSWORD y CMIS_PASSWORD del archivo (gitignored) y NUNCA los imprime. Requiere Key Vault Secrets Officer.
set -euo pipefail
RG="${1:?resource group}"
SETTINGS="${2:-$(dirname "${BASH_SOURCE[0]}")/../function/local.settings.json}"
KV="$(az keyvault list -g "$RG" --query "[?starts_with(name, 'cmv8-kv-')].name | [0]" -o tsv)"
[ -n "$KV" ] || { echo "No hay Key Vault cmv8-kv-* en $RG" >&2; exit 1; }

cargar() {
  local nombre="$1" clave="$2"
  local valor
  valor="$(jq -r --arg k "$clave" '.Values[$k] // empty' "$SETTINGS")"
  if [ -z "$valor" ]; then
    echo "  $nombre: sin valor en $SETTINGS, no se carga"
    return
  fi
  az keyvault secret set --vault-name "$KV" --name "$nombre" --value "$valor" --query 'attributes.updated' -o tsv >/dev/null
  echo "  $nombre: cargado en $KV"
  unset valor
}

echo "Key Vault: $KV"
cargar as400-password AS400_PASSWORD
cargar cmis-password CMIS_PASSWORD
echo "La Function toma el valor nuevo al reiniciar (o en 24 h): az functionapp restart -g $RG -n <function>"
