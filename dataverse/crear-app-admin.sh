#!/usr/bin/env bash
# Crea la app model-driven "CMV8 Administración documental" (ADR-003 §1.2) con su mapa del sitio:
# catálogo (tipos de documento, propiedades de Content Manager), operación (conflictos, adjuntos).
# Idempotente: si la app existe, solo revalida, publica y asocia el rol.
# Uso: DATAVERSE_URL=https://org.crm.dynamics.com bash dataverse/crear-app-admin.sh
# Requiere: az login con un administrador del entorno; el esquema v2 (esquema-v2.py) y los roles (crear-roles.sh) ya aplicados.
set -euo pipefail

DV="${DATAVERSE_URL:?DATAVERSE_URL}"
API="$DV/api/data/v9.2"
SOLUCION="cmv8ingesta"
UNICO="AdministracionDocumental"   # Dataverse le antepone el prefijo del publisher: cmv8_AdministracionDocumental
NOMBRE="CMV8 Administración documental"
ROL="CMV8 Administrador documental"
ICONO="953b9fac-1e5e-e611-80d6-00155ded156f"   # web resource por defecto que ofrece Microsoft para el ícono

TOKEN="$(az account get-access-token --resource "$DV" --query accessToken -o tsv)"
H=(-H "Authorization: Bearer $TOKEN" -H "OData-MaxVersion: 4.0" -H "OData-Version: 4.0" -H "Accept: application/json" -H "Content-Type: application/json; charset=utf-8")
json() { python3 -I -c "$1"; }

echo "== App $NOMBRE"
APP=$(curl -sS -G "${H[@]}" "$API/appmodules" --data-urlencode '$select=appmoduleid' --data-urlencode "\$filter=uniquename eq 'cmv8_$UNICO'" \
  | json 'import sys,json; v=json.load(sys.stdin)["value"]; print(v[0]["appmoduleid"] if v else "")')
if [[ -z "$APP" ]]; then
  APP=$(curl -sS -i -X POST "${H[@]}" -H "MSCRM.SolutionUniqueName: $SOLUCION" "$API/appmodules" --data-binary @- <<JSON | rg -o 'appmodules\(([0-9a-f-]+)\)' -r '$1' | head -1
{"name":"$NOMBRE","uniquename":"$UNICO","description":"Catálogo de tipos de documento descubierto de Content Manager, decisiones del administrador, conflictos y adjuntos detenidos (ADR-003).","webresourceid":"$ICONO","clienttype":4,"navigationtype":0}
JSON
)
  echo "  creada: $APP"
else
  echo "  existe: $APP"
fi
[[ -n "$APP" ]] || { echo "No se pudo crear la app"; exit 1; }

echo "== Mapa del sitio"
MAPA=$(curl -sS -G "${H[@]}" "$API/sitemaps" --data-urlencode '$select=sitemapid' --data-urlencode "\$filter=sitemapnameunique eq 'cmv8_$UNICO'" \
  | json 'import sys,json; v=json.load(sys.stdin)["value"]; print(v[0]["sitemapid"] if v else "")')
if [[ -z "$MAPA" ]]; then
  XML='<SiteMap><Area Id="CMV8Admin" ShowGroups="true"><Titles><Title LCID="3082" Title="Administración documental"/><Title LCID="1033" Title="Document administration"/></Titles><Group Id="Catalogo"><Titles><Title LCID="3082" Title="Catálogo"/><Title LCID="1033" Title="Catalog"/></Titles><SubArea Id="TiposDocumento" Entity="cmv8_tipodocumento"/><SubArea Id="PropiedadesCm" Entity="cmv8_propiedadcm"/></Group><Group Id="Operacion"><Titles><Title LCID="3082" Title="Operación"/><Title LCID="1033" Title="Operations"/></Titles><SubArea Id="Conflictos" Entity="cmv8_conflicto"/><SubArea Id="Adjuntos" Entity="cmv8_solicitudingesta"/></Group></Area></SiteMap>'
  MAPA=$(python3 -I - "$XML" "$NOMBRE" "cmv8_$UNICO" <<'PY' | curl -sS -i -X POST "${H[@]}" -H "MSCRM.SolutionUniqueName: $SOLUCION" "$API/sitemaps" --data-binary @- | rg -o 'sitemaps\(([0-9a-f-]+)\)' -r '$1' | head -1
import json,sys
print(json.dumps({"sitemapname": sys.argv[2], "sitemapnameunique": sys.argv[3], "sitemapxml": sys.argv[1], "isappaware": True}))
PY
)
  echo "  creado: $MAPA"
else
  echo "  existe: $MAPA"
fi

echo "== Componentes"
ids_entidades() { # logicalname... → JSON de componentes tipo entity
  for e in "$@"; do
    curl -sS -G "${H[@]}" "$API/entities" --data-urlencode '$select=entityid' --data-urlencode "\$filter=logicalname eq '$e'" \
      | json 'import sys,json; v=json.load(sys.stdin)["value"]; print(json.dumps({"@odata.type":"Microsoft.Dynamics.CRM.entity","entityid":v[0]["entityid"]}) if v else "")'
  done
}
COMPONENTES=$( { echo "{\"@odata.type\":\"Microsoft.Dynamics.CRM.sitemap\",\"sitemapid\":\"$MAPA\"}"; ids_entidades cmv8_tipodocumento cmv8_propiedadcm cmv8_conflicto cmv8_solicitudingesta; } | rg -v '^$' | paste -sd, -)
curl -sS -o /dev/null -w "  AddAppComponents -> %{http_code}\n" -X POST "${H[@]}" "$API/AddAppComponents" -d "{\"AppId\":\"$APP\",\"Components\":[$COMPONENTES]}"

echo "== Validar"
curl -sS "${H[@]}" "$API/ValidateApp(AppModuleId=$APP)" | json 'import sys,json
d=json.load(sys.stdin).get("AppValidationResponse",{})
print("  válida:", d.get("ValidationSuccess"))
for i in d.get("ValidationIssueList",[]): print("   ", i.get("ErrorType"), "-", i.get("Message"))'

echo "== Publicar"
curl -sS -o /dev/null -w "  PublishXml -> %{http_code}\n" -X POST "${H[@]}" "$API/PublishXml" \
  -d "{\"ParameterXml\":\"<importexportxml><appmodules><appmodule>$APP</appmodule></appmodules></importexportxml>\"}"

echo "== Rol $ROL"
BU=$(curl -sS -G "${H[@]}" "$API/businessunits" --data-urlencode '$select=businessunitid' --data-urlencode '$filter=_parentbusinessunitid_value eq null' | json 'import sys,json; print(json.load(sys.stdin)["value"][0]["businessunitid"])')
ROLID=$(curl -sS -G "${H[@]}" "$API/roles" --data-urlencode '$select=roleid' --data-urlencode "\$filter=name eq '$ROL' and _businessunitid_value eq $BU" | json 'import sys,json; v=json.load(sys.stdin)["value"]; print(v[0]["roleid"] if v else "")')
if [[ -n "$ROLID" ]]; then
  curl -sS -o /dev/null -w "  asociar rol a la app -> %{http_code}\n" -X POST "${H[@]}" "$API/appmodules($APP)/appmoduleroles_association/\$ref" -d "{\"@odata.id\":\"$API/roles($ROLID)\"}"
else
  echo "  AVISO: el rol no existe; corré crear-roles.sh primero"
fi

echo "Listo. App: $DV/main.aspx?appid=$APP"
