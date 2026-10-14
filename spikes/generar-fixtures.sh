#!/usr/bin/env bash
# Genera archivos de prueba pesados con contenido aleatorio para los spikes S1/S1b/S3.
# Uso: bash spikes/generar-fixtures.sh
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/fixtures"
mkdir -p "$DIR"

gen() {
  local name="$1" mb="$2"
  local out="$DIR/$name"
  if [[ -f "$out" ]]; then
    echo "ya existe: $out"
    return
  fi
  dd if=/dev/urandom of="$out" bs=1M count="$mb" status=none
  echo "generado: $out ($mb MB)"
}

gen video-100mb.bin 100
gen video-250mb.bin 250

# EICAR para S3 (Defender for Storage debe marcarlo como malicioso). Se arma por partes
# para que ningún antivirus local lo detecte en este repo.
EICAR_OUT="$DIR/eicar.com"
if [[ ! -f "$EICAR_OUT" ]]; then
  printf '%s%s' 'X5O!P%@AP[4\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE' '!$H+H*' > "$EICAR_OUT"
  echo "generado: $EICAR_OUT"
fi

sha256sum "$DIR"/*.bin "$EICAR_OUT" > "$DIR/SHA256SUMS"
echo "hashes en $DIR/SHA256SUMS"
