#!/usr/bin/env bash
# 仅补抓 3 个在 offset=3000 处被截断的地址，从 offset=3000 开始直到分页返回空
set -u
OUT=/tmp/poly_api
mkdir -p "$OUT"
RES="--resolve data-api.polymarket.com:443:172.64.153.51"
UA="-A Mozilla/5.0"
PAGE=50

ADDRS=(
  0x2005d16a84ceefa912d4e380cd32e7ff827875ea
  0xe8dd7741ccb12350957ec71e9ee332e0d1e6ec86
  0xee00ba338c59557141789b127927a55f5cc5cea1
)

fetch() {
  local addr=$1
  echo "==> $addr"
  local offset=3000
  local hard_cap=30000   # 安全上限，防止无限循环
  while [ $offset -lt $hard_cap ]; do
    local f="$OUT/${addr}_closed_${offset}.json"
    curl -sS -m 40 $RES $UA -o "$f" \
      "https://data-api.polymarket.com/closed-positions?user=${addr}&limit=${PAGE}&offset=${offset}"
    local rc=$?
    if [ $rc -ne 0 ]; then echo "  curl failed rc=$rc at offset=$offset, retry once"; sleep 2
      curl -sS -m 40 $RES $UA -o "$f" \
        "https://data-api.polymarket.com/closed-positions?user=${addr}&limit=${PAGE}&offset=${offset}"
    fi
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