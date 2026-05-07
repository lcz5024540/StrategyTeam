#!/usr/bin/env python3
"""
复核 docs/polymarket/smart-wallet-follow-address-api-vs-db-2026-05-07.md
针对的两点质疑：
  Q1. 分析是否仅基于 100-200 条样本？实际样本规模多少？
  Q2. API 返回是否按时间排序？会不会只返回盈利单导致胜率被高估？

输出每个地址：
  - closed 总记录数
  - realizedPnl 的 >0 / <0 / =0 分布
  - timestamp 的最早 / 最晚 / 单调性（是否按 ts 降序/升序/乱序）
  - 各分页 offset 内部胜/负 分布（若全部盈利集中在前段，则存在排序偏差）
  - 单独确认 0x2005…75ea 是否真的 0 负单
"""
import json, glob, os, re
from decimal import Decimal, getcontext
from datetime import datetime, timezone
getcontext().prec = 30

OUT = "/tmp/poly_api"
ADDRS = [
    "0x2005d16a84ceefa912d4e380cd32e7ff827875ea",
    "0x9f2fe025f84839ca81dd8e0338892605702d2ca8",
    "0xe8dd7741ccb12350957ec71e9ee332e0d1e6ec86",
    "0xfbfd14dd4bb607373119de95f1d4b21c3b6c0029",
    "0x51fd8f0358cc9e8a1ee5f87a0c7e3b07ed634272",
    "0xa61ef8773ec2e821962306ca87d4b57e39ff0abd",
    "0x14964aefa2cd7caff7878b3820a690a03c5aa429",
    "0xdb27bf2ac5d428a9c63dbc914611036855a6c56e",
    "0xee00ba338c59557141789b127927a55f5cc5cea1",
    "0x03e8a544e97eeff5753bc1e90d46e5ef22af1697",
    "0x9495425feeb0c250accb89275c97587011b19a27",
]

def dec(x):
    if x in (None, ""): return Decimal(0)
    try: return Decimal(str(x))
    except Exception: return Decimal(0)

def parse_ts(ts):
    if ts in (None, ""): return None
    try:
        v = int(float(ts))
        if v < 10_000_000_000: v *= 1000
        return v
    except Exception:
        try:
            dt = datetime.fromisoformat(str(ts).replace("Z","+00:00"))
            return int(dt.timestamp()*1000)
        except Exception: return None

def ts_str(ms):
    if not ms: return "-"
    return datetime.fromtimestamp(ms/1000, tz=timezone.utc).strftime("%Y-%m-%d")

print("=" * 140)
print("Q1/Q2 复核：每个地址每个分页的胜/负分布 + 时间单调性")
print("=" * 140)
print(f"{'addr':22s} {'total':>6s} {'wins':>6s} {'losses':>7s} {'flats':>6s} {'wr%':>6s} {'first_ts':>12s} {'last_ts':>12s} {'ts_order':>12s}")
print("-" * 140)

for a in ADDRS:
    files = sorted(
        glob.glob(f"{OUT}/{a}_closed_*.json"),
        key=lambda p: int(re.search(r"_closed_(\d+)\.json$", p).group(1))
    )
    closed = []
    seen = set()
    for fp in files:
        off = int(re.search(r"_closed_(\d+)\.json$", fp).group(1))
        try: d = json.load(open(fp))
        except Exception: continue
        if not isinstance(d, list): continue
        for p in d:
            k = (p.get("asset"), p.get("conditionId"), p.get("timestamp"), str(p.get("totalBought") or ""))
            if k in seen: continue
            seen.add(k)
            p["_page_offset"] = off
            closed.append(p)

    n = len(closed)
    if n == 0:
        print(f"{a[:10]+'…'+a[-4:]:22s} {0:>6d} (no data)")
        continue

    wins = sum(1 for p in closed if dec(p.get("realizedPnl")) > 0)
    losses = sum(1 for p in closed if dec(p.get("realizedPnl")) < 0)
    flats = sum(1 for p in closed if dec(p.get("realizedPnl")) == 0)
    wr = wins / n * 100

    ts_list = [parse_ts(p.get("timestamp")) for p in closed]
    ts_list_valid = [t for t in ts_list if t]
    first_ts = min(ts_list_valid) if ts_list_valid else None
    last_ts = max(ts_list_valid) if ts_list_valid else None
    # 判断原始顺序是否单调（按抓取顺序，即 page offset 从小到大）
    non_null = [t for t in ts_list if t]
    mono_desc = all(non_null[i] >= non_null[i+1] for i in range(len(non_null)-1))
    mono_asc = all(non_null[i] <= non_null[i+1] for i in range(len(non_null)-1))
    if mono_desc and not mono_asc: order = "desc(新→旧)"
    elif mono_asc and not mono_desc: order = "asc(旧→新)"
    elif mono_asc and mono_desc: order = "equal"
    else: order = "乱序"

    print(f"{a[:10]+'…'+a[-4:]:22s} {n:>6d} {wins:>6d} {losses:>7d} {flats:>6d} {wr:>5.1f}% {ts_str(first_ts):>12s} {ts_str(last_ts):>12s} {order:>12s}")

print()
print("=" * 140)
print("Q2 深入：按分页 offset 看胜/负分布（如果 losses 只出现在尾段，则接口按 pnl 排序而非时间）")
print("=" * 140)

for a in ADDRS:
    files = sorted(
        glob.glob(f"{OUT}/{a}_closed_*.json"),
        key=lambda p: int(re.search(r"_closed_(\d+)\.json$", p).group(1))
    )
    page_stats = []
    for fp in files:
        off = int(re.search(r"_closed_(\d+)\.json$", fp).group(1))
        try: d = json.load(open(fp))
        except Exception: continue
        if not isinstance(d, list) or not d: continue
        w = sum(1 for p in d if dec(p.get("realizedPnl")) > 0)
        l = sum(1 for p in d if dec(p.get("realizedPnl")) < 0)
        f = sum(1 for p in d if dec(p.get("realizedPnl")) == 0)
        ts_list = [parse_ts(p.get("timestamp")) for p in d if parse_ts(p.get("timestamp"))]
        first_ts = ts_list[0] if ts_list else None
        last_ts = ts_list[-1] if ts_list else None
        page_stats.append((off, len(d), w, l, f, first_ts, last_ts))
    if not page_stats: continue
    total_losses = sum(s[3] for s in page_stats)
    if total_losses == 0:
        print(f"\n### {a}  [n={sum(s[1] for s in page_stats)}, losses=0 —— 疑似只返回盈利单！]")
    else:
        print(f"\n### {a}  [n={sum(s[1] for s in page_stats)}, losses={total_losses}]")
    print(f"  {'offset':>6s} {'n':>4s} {'W':>4s} {'L':>4s} {'F':>4s} {'first_ts':>12s} {'last_ts':>12s}")
    if len(page_stats) > 10:
        show = page_stats[:6] + [None] + page_stats[-4:]
    else:
        show = page_stats
    for item in show:
        if item is None:
            print("  ...")
            continue
        off, ln, w, l, f, ft, lt = item
        print(f"  {off:>6d} {ln:>4d} {w:>4d} {l:>4d} {f:>4d} {ts_str(ft):>12s} {ts_str(lt):>12s}")