#!/usr/bin/env python3
"""
扫描 /tmp/poly_api/<addr>_closed_*.json，检测是否有"卡住重复"的分页。
判据：相邻两个分页，其 d[0].asset + d[0].timestamp 完全相同则视为重复，打印出来。
"""
import json, glob, re
from pathlib import Path

ADDRS = [
    "0x2005d16a84ceefa912d4e380cd32e7ff827875ea",
    "0xe8dd7741ccb12350957ec71e9ee332e0d1e6ec86",
    "0xee00ba338c59557141789b127927a55f5cc5cea1",
]
for a in ADDRS:
    files = sorted(glob.glob(f"/tmp/poly_api/{a}_closed_*.json"),
                   key=lambda p: int(re.search(r"_closed_(\d+)\.json$", p).group(1)))
    print(f"\n=== {a}  pages={len(files)} ===")
    prev_key = None
    stuck_start = None
    for fp in files:
        off = int(re.search(r"_closed_(\d+)\.json$", fp).group(1))
        try: d = json.load(open(fp))
        except Exception: continue
        if not isinstance(d, list) or not d: continue
        p = d[0]
        key = (p.get("asset"), p.get("timestamp"), str(p.get("realizedPnl")))
        if key == prev_key:
            if stuck_start is None:
                stuck_start = off
        else:
            if stuck_start is not None:
                print(f"  stuck range: offset {stuck_start} .. (prev) ending before {off}")
                stuck_start = None
        prev_key = key
    if stuck_start is not None:
        print(f"  stuck range: offset {stuck_start} .. end")
    # 汇总 W/L
    wins = losses = flats = n_total = 0
    seen = set()
    for fp in files:
        try: d = json.load(open(fp))
        except Exception: continue
        if not isinstance(d, list): continue
        for p in d:
            k = (p.get("asset"), p.get("conditionId"), p.get("timestamp"), str(p.get("totalBought") or ""))
            if k in seen: continue
            seen.add(k)
            rp = float(p.get("realizedPnl") or 0)
            if rp > 0: wins += 1
            elif rp < 0: losses += 1
            else: flats += 1
            n_total += 1
    print(f"  dedup: total={n_total} W={wins} L={losses} F={flats} win_rate={wins/n_total*100:.2f}%")