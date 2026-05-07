#!/usr/bin/env bash
# 第二轮：从 30000 起继续抓 0x2005 与 0xe8dd，自然终止（cnt<PAGE）
set -u
OUT=/tmp/poly_api
mkdir -p "$OUT"
RES="--resolve data-api.polymarket.com:443:172.64.153.51"
UA="-A Mozilla/5.0"
PAGE=50

ADDRS=(
  0x2005d16a84ceefa912d4e380cd32e7ff827875ea
  0xe8dd7741ccb12350957ec71e9ee332e0d1e6ec86
)

fetch() {
  local addr=$1
  echo "==> $addr"
  local offset=30000
  local hard_cap=200000
  while [ $offset -lt $hard_cap ]; do
    local f="$OUT/${addr}_closed_${offset}.json"
    curl -sS -m 40 $RES $UA -o "$f" \
      "https://data-api.polymarket.com/closed-positions?user=${addr}&limit=${PAGE}&offset=${offset}"
    local cnt
    cnt=$(python3 -c "import json,sys
try:
    d=json.load(open(sys.argv[1]))
    print(len(d) if isinstance(d,list) else 0)
except Exception: print(0)" "$f" 2>/dev/null || echo 0)
    echo "  offset=$offset cnt=$cnt"
    if [ "$cnt" = "0" ]; then rm -f "$f"; break; fi
    offset=$((offset+PAGE))
    if [ "$cnt" -lt "$PAGE" ]; then break; fi
  done
}

for a in "${ADDRS[@]}"; do fetch "$a"; done
echo "DONE"