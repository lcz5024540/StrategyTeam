#!/usr/bin/env bash
# 用二分法找 closed-positions 真实末尾 offset：
#   条件：offset >= end 时 API 开始重复返回固定的 stuck_first_asset
set -u
ADDR=${1:-0x2005d16a84ceefa912d4e380cd32e7ff827875ea}
STUCK_FP=/tmp/poly_api/_probe_total_200000.json
# STUCK_FP 文件里 d[0] 的 asset + realizedPnl 就是"卡住"时的标识
STUCK_ASSET=$(python3 -c "import json,sys; d=json.load(open(sys.argv[1])); print(d[0].get('asset'))" "$STUCK_FP")
STUCK_PNL=$(python3 -c "import json,sys; d=json.load(open(sys.argv[1])); print(d[0].get('realizedPnl'))" "$STUCK_FP")
echo "stuck_asset=$STUCK_ASSET stuck_pnl=$STUCK_PNL"

probe_off() {
  local off=$1
  f=/tmp/poly_api/_endprobe_${ADDR}_${off}.json
  curl -sS -m 20 --resolve data-api.polymarket.com:443:172.64.153.51 -A "Mozilla/5.0" \
    -o $f "https://data-api.polymarket.com/closed-positions?user=${ADDR}&limit=50&offset=${off}"
  python3 -c "
import json,sys
d=json.load(open(sys.argv[1]))
if not isinstance(d,list) or not d: print('EMPTY'); sys.exit()
p=d[0]
st = (p.get('asset')==sys.argv[2] and str(p.get('realizedPnl'))==sys.argv[3])
print('STUCK' if st else 'NORMAL', 'n=',len(d), 'pnl=',p.get('realizedPnl'), 'ts=',p.get('timestamp'))
" $f "$STUCK_ASSET" "$STUCK_PNL"
}

lo=95000
hi=100000
echo "=== bisect between $lo and $hi ==="
probe_off $lo
probe_off $hi
while [ $((hi-lo)) -gt 50 ]; do
  mid=$(( ((lo+hi)/2/50)*50 ))
  [ $mid -le $lo ] && mid=$((lo+50))
  r=$(probe_off $mid)
  echo "mid=$mid -> $r"
  if echo "$r" | grep -q '^STUCK'; then hi=$mid; else lo=$mid; fi
done
echo "-> true end near offset=$hi (lo=$lo still normal)"