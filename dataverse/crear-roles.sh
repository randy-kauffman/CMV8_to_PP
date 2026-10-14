#!/usr/bin/env bash
# Crea los roles de seguridad de mínimo privilegio de la solución y registra la identidad
# de la Function como usuario de aplicación con el rol de worker. Idempotente.
#
# Uso: DATAVERSE_URL=https://org.crm.dynamics.com APP_CLIENT_ID=<client id de la MI> bash dataverse/crear-roles.sh
# Requiere: az login con un administrador del entorno.
#
# Roles:
#   CMV8 Worker      → la Function. Lee/escribe toda la tabla (Global), puede actuar en nombre de
#                      otro usuario (impersonación) y leer usuarios/unidades para RetrievePrincipalAccess.
#   CMV8 Solicitante → usuarios de la app. Crea/lee/escribe SUS propias solicitudes (Basic).
set -euo pipefail

DV="${DATAVERSE_URL:?DATAVERSE_URL}"
APPID="${APP_CLIENT_ID:-}"
API="$DV/api/data/v9.2"
SOLUCION="cmv8ingesta"
TABLA="cmv8_solicitudingesta"

TOKEN="$(az account get-access-token --resource "$DV" --query accessToken -o tsv)"
H=(-H "Authorization: Bearer $TOKEN" -H "OData-MaxVersion: 4.0" -H "OData-Version: 4.0" -H "Accept: application/json" -H "Content-Type: application/json; charset=utf-8")

json() { python3 -I -c "$1"; }

BU=$(curl -sS -G "${H[@]}" "$API/businessunits" --data-urlencode '$select=businessunitid' --data-urlencode '$filter=_parentbusinessunitid_value eq null' \
  | json 'import sys,json; print(json.load(sys.stdin)["value"][0]["businessunitid"])')
echo "Unidad de negocio raíz: $BU"

priv_id() { # nombre del privilegio → id
  curl -sS -G "${H[@]}" "$API/privileges" --data-urlencode '$select=privilegeid' --data-urlencode "\$filter=name eq '$1'" \
    | json 'import sys,json; v=json.load(sys.stdin)["value"]; print(v[0]["privilegeid"] if v else "")'
}

crear_rol() { # nombre → roleid (crea si no existe)
  local nombre="$1" id
  id=$(curl -sS -G "${H[@]}" "$API/roles" --data-urlencode '$select=roleid' --data-urlencode "\$filter=name eq '$nombre' and _businessunitid_value eq $BU" \
    | json 'import sys,json; v=json.load(sys.stdin)["value"]; print(v[0]["roleid"] if v else "")')
  if [[ -z "$id" ]]; then
    id=$(curl -sS -i -X POST "${H[@]}" -H "MSCRM.SolutionUniqueName: $SOLUCION" "$API/roles" \
      -d "{\"name\":\"$nombre\",\"businessunitid@odata.bind\":\"/businessunits($BU)\"}" \
      | rg -o 'roles\(([0-9a-f-]+)\)' -r '$1' | head -1)
    echo "Rol creado: $nombre ($id)" >&2
  else
    echo "Rol existe: $nombre ($id)" >&2
  fi
  echo "$id"
}

agregar_privilegios() { # roleid  "priv:Depth" ...
  local rol="$1"; shift
  local items=() p nombre depth id
  for p in "$@"; do
    nombre="${p%%:*}"; depth="${p##*:}"
    id=$(priv_id "$nombre")
    if [[ -z "$id" ]]; then echo "  AVISO: privilegio $nombre no existe, se omite" >&2; continue; fi
    items+=("{\"PrivilegeId\":\"$id\",\"Depth\":\"$depth\"}")
  done
  local cuerpo; cuerpo="{\"Privileges\":[$(IFS=,; echo "${items[*]}")]}"
  # AddPrivilegesRole falla si el privilegio ya está en el rol; ReplacePrivilegesRole deja el set exacto.
  curl -sS -o /dev/null -w "  ReplacePrivilegesRole -> %{http_code}\n" -X POST "${H[@]}" \
    "$API/roles($rol)/Microsoft.Dynamics.CRM.ReplacePrivilegesRole" -d "$cuerpo"
}

echo "== CMV8 Worker"
ROL_WORKER=$(crear_rol "CMV8 Worker")
agregar_privilegios "$ROL_WORKER" \
  "prvRead${TABLA}:Global" "prvWrite${TABLA}:Global" "prvAppend${TABLA}:Global" "prvAppendTo${TABLA}:Global" \
  "prvActOnBehalfOfAnotherUser:Global" \
  "prvReadUser:Global" "prvReadBusinessUnit:Global" "prvReadOrganization:Global" "prvReadRole:Global" \
  "prvReadTeam:Global" "prvReadUserSettings:Basic" "prvWriteUserSettings:Basic"

echo "== CMV8 Solicitante"
ROL_SOL=$(crear_rol "CMV8 Solicitante")
agregar_privilegios "$ROL_SOL" \
  "prvCreate${TABLA}:Basic" "prvRead${TABLA}:Basic" "prvWrite${TABLA}:Basic" "prvAppend${TABLA}:Basic" "prvAppendTo${TABLA}:Basic" \
  "prvReadUser:Global" "prvReadBusinessUnit:Global" "prvReadOrganization:Global" \
  "prvReadUserSettings:Basic" "prvWriteUserSettings:Basic"

if [[ -n "$APPID" ]]; then
  echo "== Usuario de aplicación $APPID"
  USR=$(curl -sS -G "${H[@]}" "$API/systemusers" --data-urlencode '$select=systemuserid' --data-urlencode "\$filter=applicationid eq $APPID" \
    | json 'import sys,json; v=json.load(sys.stdin)["value"]; print(v[0]["systemuserid"] if v else "")')
  if [[ -z "$USR" ]]; then
    USR=$(curl -sS -i -X POST "${H[@]}" "$API/systemusers" \
      -d "{\"applicationid\":\"$APPID\",\"businessunitid@odata.bind\":\"/businessunits($BU)\",\"firstname\":\"CMV8\",\"lastname\":\"Function\"}" \
      | rg -o 'systemusers\(([0-9a-f-]+)\)' -r '$1' | head -1)
    echo "  creado: $USR"
  else
    echo "  existe: $USR"
  fi
  curl -sS -o /dev/null -w "  asignar CMV8 Worker -> %{http_code}\n" -X POST "${H[@]}" \
    "$API/systemusers($USR)/systemuserroles_association/\$ref" -d "{\"@odata.id\":\"$API/roles($ROL_WORKER)\"}"
fi

echo "Listo."
