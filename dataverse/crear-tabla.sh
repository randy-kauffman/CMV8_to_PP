#!/usr/bin/env bash
# Crea publisher, solución y la tabla cmv8_SolicitudIngesta con sus columnas vía Dataverse Web API.
# Idempotente: si algo ya existe, lo salta.
# Uso: DATAVERSE_URL=https://org36e60d9d.crm.dynamics.com bash dataverse/crear-tabla.sh
# Requiere: az login con un usuario que sea administrador del entorno (misma cuenta Entra).
set -euo pipefail

DV="${DATAVERSE_URL:?DATAVERSE_URL, ej. https://org36e60d9d.crm.dynamics.com}"
API="$DV/api/data/v9.2"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOLUCION="cmv8ingesta"
PREFIJO="cmv8"

TOKEN="$(az account get-access-token --resource "$DV" --query accessToken -o tsv)"
H=(-H "Authorization: Bearer $TOKEN" -H "OData-MaxVersion: 4.0" -H "OData-Version: 4.0" -H "Accept: application/json" -H "Content-Type: application/json; charset=utf-8")

api() { # método ruta [archivo-json]
  local m="$1" ruta="$2" cuerpo="${3:-}"
  if [[ -n "$cuerpo" ]]; then
    curl -sS -w '\n%{http_code}' -X "$m" "${H[@]}" -H "MSCRM.SolutionUniqueName: $SOLUCION" --data-binary "@$cuerpo" "$API/$ruta"
  else
    curl -sS -w '\n%{http_code}' -X "$m" "${H[@]}" "$API/$ruta"
  fi
}

echo "== Quién soy"
curl -sS "${H[@]}" "$API/WhoAmI" | python3 -I -c 'import sys,json; d=json.load(sys.stdin); print("  user:", d["UserId"], "org:", d["OrganizationId"])'

echo "== Publisher $PREFIJO"
PUB=$(curl -sS "${H[@]}" "$API/publishers?\$select=publisherid&\$filter=customizationprefix eq '$PREFIJO'" | python3 -I -c 'import sys,json; v=json.load(sys.stdin)["value"]; print(v[0]["publisherid"] if v else "")')
if [[ -z "$PUB" ]]; then
  PUB=$(curl -sS -i -X POST "${H[@]}" "$API/publishers" --data-binary @- <<EOF | rg -o 'publishers\(([0-9a-f-]+)\)' -r '$1'
{"uniquename":"banco_cmv8","friendlyname":"Banco CMV8","customizationprefix":"$PREFIJO","customizationoptionvalueprefix":10000}
EOF
)
  echo "  creado: $PUB"
else
  echo "  existe: $PUB"
fi

echo "== Solución $SOLUCION"
SOL=$(curl -sS "${H[@]}" "$API/solutions?\$select=solutionid&\$filter=uniquename eq '$SOLUCION'" | python3 -I -c 'import sys,json; v=json.load(sys.stdin)["value"]; print(v[0]["solutionid"] if v else "")')
if [[ -z "$SOL" ]]; then
  curl -sS -o /dev/null -w '  POST solutions -> %{http_code}\n' -X POST "${H[@]}" "$API/solutions" --data-binary @- <<EOF
{"uniquename":"$SOLUCION","friendlyname":"CMV8 Ingesta","version":"0.1.0.0","publisherid@odata.bind":"/publishers($PUB)"}
EOF
else
  echo "  existe: $SOL"
fi

echo "== Tabla cmv8_solicitudingesta"
EXISTE=$(curl -sS -o /dev/null -w '%{http_code}' "${H[@]}" "$API/EntityDefinitions(LogicalName='cmv8_solicitudingesta')?\$select=LogicalName")
if [[ "$EXISTE" == "200" ]]; then
  echo "  existe"
else
  api POST "EntityDefinitions" "$DIR/metadata/tabla-solicitudingesta.json" | tail -1 | xargs -I{} echo "  POST EntityDefinitions -> {}"
fi

echo "== Columnas"
EXISTENTES=$(curl -sS "${H[@]}" "$API/EntityDefinitions(LogicalName='cmv8_solicitudingesta')/Attributes?\$select=SchemaName" | python3 -I -c 'import sys,json; print("\n".join(a["SchemaName"] for a in json.load(sys.stdin)["value"]))')
TMP="$(mktemp -d)"
python3 -I - "$DIR/metadata/columnas-solicitudingesta.json" "$TMP" <<'PY'
import json, sys, pathlib
cols = json.load(open(sys.argv[1]))
out = pathlib.Path(sys.argv[2])
for c in cols:
    (out / f"{c['SchemaName']}.json").write_text(json.dumps(c))
PY
for f in "$TMP"/*.json; do
  nombre="$(basename "$f" .json)"
  if rg -qx "$nombre" <<<"$EXISTENTES"; then
    echo "  existe: $nombre"
  else
    code=$(api POST "EntityDefinitions(LogicalName='cmv8_solicitudingesta')/Attributes" "$f" | tail -1)
    echo "  POST $nombre -> $code"
  fi
done
rm -rf "$TMP"

echo "== Publicar"
curl -sS -o /dev/null -w '  PublishAllXml -> %{http_code}\n' -X POST "${H[@]}" "$API/PublishAllXml"

echo "Listo. Tabla cmv8_solicitudingesta en $DV dentro de la solución $SOLUCION."
