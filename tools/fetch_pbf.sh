#!/bin/bash
# Download a big OpenStreetMap extract reliably: 32 MB pieces, 8 at a time, each size-checked and
# retried, then checked against the server's .md5. (Single connections to openstreetmap.fr run at
# ~0.5 MB/s and sometimes drop mid-file.)
#   fetch_pbf.sh URL OUT.osm.pbf
set -euo pipefail
U=$1; OUT=$2; C=$((32*1024*1024))
S=$(curl -sSIL "$U" | grep -i '^content-length' | tail -1 | tr -dc 0-9)
[ -n "$S" ] || { echo "no size for $U"; exit 1; }
D=$(mktemp -d "${OUT}.parts.XXXX"); N=$(( (S + C - 1) / C ))
echo "$U: $S bytes, $N pieces"
piece() {
  local i=$1 a=$(( $1 * C )) b=$(( ($1 + 1) * C - 1 )); [ $b -ge $S ] && b=$((S - 1))
  for t in 1 2 3 4 5 6 7 8; do
    curl -sSL --max-time 900 -r $a-$b -o "$D/$i" "$U" || true
    [ "$(stat -c %s "$D/$i" 2>/dev/null || echo 0)" -eq $((b - a + 1)) ] && return 0
    sleep $((t * 5))
  done
  echo "piece $i failed"; return 1
}
export -f piece; export U C S D
seq 0 $((N - 1)) | xargs -P 8 -I{} bash -c 'piece {}'
for i in $(seq 0 $((N - 1))); do cat "$D/$i"; done > "$OUT.tmp"; rm -rf "$D"
# openstreetmap.fr has the .md5 next to the dated name, not "-latest"; only trust a real md5.
WANT=$( (curl -sSf "$U.md5" || curl -sSf "${U/-latest/}.md5") 2>/dev/null | cut -d' ' -f1 | grep -E '^[0-9a-f]{32}$' || true)
[ -n "$WANT" ] || echo "no md5 published for $U; pieces were size-checked only"
if [ -n "$WANT" ]; then
  if [ "$(md5sum "$OUT.tmp" | cut -d' ' -f1)" != "$WANT" ]; then
    rm -f "$OUT.tmp"; echo "md5 mismatch for $OUT (the file may have been replaced mid-download)"
    [ -z "${RETRIED:-}" ] && RETRIED=1 exec "$0" "$@"; exit 1
  fi
  echo "md5 OK $OUT"
fi
mv "$OUT.tmp" "$OUT"
