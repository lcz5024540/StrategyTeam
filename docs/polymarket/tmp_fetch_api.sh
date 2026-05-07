#!/usr/bin/env bash
# Pull Polymarket data-api real account data into /tmp/poly_api/
set -u
OUT=/tmp/poly_api
mkdir -p "$OUT"
RES="--resolve data-api.polymarket.com:443:172.64.153.51"
UA="-A Mozilla/5.0"
PAGE=50

ADDRS=(
  0x2005d16a84ceefa912d4e380cd32e7ff827875ea
  0x9f2fe025f84839ca81dd8e0338892605702d2ca8
  0xe8dd7741ccb12350957ec71e9ee332e0d1e6ec86
  0xfbfd14dd4bb607373119de95f1d4b21c3b6c0029
  0x51fd8f0358cc9e8a1ee5f87a0c7e3b07ed634272
  0xa61ef8773ec2e821962306ca87d4b57e39ff0abd
  0x14964aefa2cd7caff7878b3820a690a03c5aa429
  0xdb27bf2ac5d428a9c63dbc914611036855a6c56e
  0xee00ba338c59557141789b127927a55f5cc5cea1
  0x03e8a544e97eeff5753bc1e90d46e5ef22af1697
  0x9495425feeb0c250accb89275c97587011b19a27
)

fetch() {
  local addr=$1
  echo "==> $addr"
  curl -sS -m 30 $RES $UA -o "$OUT/${addr}_positions.json" \
    "https://data-api.polymarket.com/positions?user=${addr}&sizeThreshold=0.01" || true
  local offset=0
  while [ $offset -lt 3000 ]; do
    local f="$OUT/${addr}_closed_${offset}.json"
    curl -sS -m 40 $RES $UA -o "$f" \
      "https://data-api.polymarket.com/closed-positions?user=${addr}&limit=${PAGE}&offset=${offset}" || break
    local cnt
    cnt=$(python3 -c "import json,sys
try:
    d=json.load(open(sys.argv[1]))
    print(len(d) if isinstance(d,list) else 0)
except Exception: print(0)" "$f" 2>/dev/null || echo 0)
    if [ "$cnt" = "0" ]; then rm -f "$f"; break; fi
    offset=$((offset+PAGE))
    if [ "$cnt" -lt "$PAGE" ]; then break; fi
  done
}

for a in "${ADDRS[@]}"; do fetch "$a"; done
echo "DONE"
ls "$OUT" | wc -l