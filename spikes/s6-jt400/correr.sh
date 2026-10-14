#!/usr/bin/env bash
# Spike S6: jt400 contra PUB400. Lee credenciales de function/local.settings.json (nunca las imprime).
set -eo pipefail  # sin -u: sdkman-init.sh usa variables no definidas
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SETTINGS="${1:-$DIR/../../function/local.settings.json}"
FASE="${2:-}"   # "pool": solo la fase del pool HikariCP (hallazgo 5)
# shellcheck disable=SC1091
source "$HOME/.sdkman/bin/sdkman-init.sh"
echo "== Red: puertos de host servers IBM i =="
HOST="$(jq -r '.Values.AS400_HOST' "$SETTINGS")"
for p in 446 449 8470 8471 8476 9470 9471 9476; do
  nc -zv -w 5 "$HOST" "$p" 2>&1 | tail -1 || true
done
echo "== Java =="
cd "$DIR"
mvn -q compile exec:java -Dexec.args="$(realpath "$SETTINGS") $FASE"
