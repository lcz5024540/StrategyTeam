#!/usr/bin/env python3
"""
聚合 Polymarket data-api 的真实账户数据。

closed-positions 关键字段（单位）：
  totalBought   : shares 总量
  avgPrice      : 买入均价（USDC/share）
  realizedPnl   : USDC 净盈亏（含最终结算）
  curPrice      : 结算价（0/1）
  endDate       : 市场结束日 (YYYY-MM-DD 或 ISO)
  timestamp     : 关仓/兑现的 unix 秒（字符串）
  → 成本 USDC = totalBought * avgPrice
  → 判胜负用 realizedPnl 符号

positions 字段（USDC 原生字段齐全）：
  initialValue / currentValue / cashPnl / realizedPnl / redeemable / curPrice / size
"""
import json, os, sys, glob
from decimal import Decimal, getcontext
from datetime import datetime, timezone

getcontext().prec = 30
OUT = "/tmp/poly_api"
NOW = datetime.now(tz=timezone.utc)
NOW_MS = int(NOW.timestamp()*1000)

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

def load(path):
    if not os.path.exists(path): return None
    try:
        with open(path) as f: return json.load(f)
    except Exception as e:
        print(f"parse error {path}: {e}", file=sys.stderr)
        return None

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

rows = []
for a in ADDRS:
    # 合并所有 closed 分页
    closed = []
    files = sorted(glob.glob(f"{OUT}/{a}_closed_*.json"), key=lambda p: int(p.split("_closed_")[-1].rstrip(".json")))
    seen_keys = set()
    for fp in files:
        d = load(fp) or []
        if not isinstance(d, list): continue
        for p in d:
            k = (p.get("asset") or "", p.get("conditionId") or "", p.get("timestamp") or "", str(p.get("totalBought") or ""))
            if k in seen_keys: continue
            seen_keys.add(k)
            closed.append(p)

    positions = load(f"{OUT}/{a}_positions.json") or []
    if not isinstance(positions, list): positions = []

    # 闭仓聚合
    n = len(closed)
    wins = losses = flats = 0
    pnl = Decimal(0); cost = Decimal(0)
    min_ts = None; max_ts = None
    n7 = n30 = 0
    pnl7 = Decimal(0); pnl30 = Decimal(0)
    cost7 = Decimal(0); cost30 = Decimal(0)
    wins30 = losses30 = 0
    for p in closed:
        tb = dec(p.get("totalBought"))
        ap = dec(p.get("avgPrice"))
        rp = dec(p.get("realizedPnl"))
        c = tb * ap
        pnl += rp; cost += c
        if rp > 0: wins += 1
        elif rp < 0: losses += 1
        else: flats += 1
        ts = parse_ts(p.get("timestamp") or p.get("redeemedAt") or p.get("closedAt"))
        if ts:
            min_ts = ts if min_ts is None else min(min_ts, ts)
            max_ts = ts if max_ts is None else max(max_ts, ts)
            age = NOW_MS - ts
            if age <= 7*86400_000:
                n7 += 1; pnl7 += rp; cost7 += c
            if age <= 30*86400_000:
                n30 += 1; pnl30 += rp; cost30 += c
                if rp > 0: wins30 += 1
                elif rp < 0: losses30 += 1
    roi = (pnl / cost * 100) if cost > 0 else Decimal(0)
    roi30 = (pnl30 / cost30 * 100) if cost30 > 0 else Decimal(0)
    wr = (wins / n * 100) if n > 0 else Decimal(0)
    wr30 = (wins30 / n30 * 100) if n30 > 0 else Decimal(0)

    # 持仓聚合
    n_pos = len(positions)
    pos_cost = Decimal(0); pos_cur = Decimal(0); pos_cash = Decimal(0)
    redeem_n = 0; redeem_val = Decimal(0); redeem_loss_cost = Decimal(0)
    for p in positions:
        pos_cost += dec(p.get("initialValue"))
        pos_cur += dec(p.get("currentValue"))
        pos_cash += dec(p.get("cashPnl"))
        if p.get("redeemable"):
            redeem_n += 1
            redeem_val += dec(p.get("currentValue"))
            # 结算归零
            if dec(p.get("currentValue")) == 0:
                redeem_loss_cost += dec(p.get("initialValue"))

    rows.append(dict(
        addr=a, closed_n=n, wins=wins, losses=losses, flats=flats, win_rate=wr,
        cost=cost, pnl=pnl, roi=roi,
        n30=n30, wins30=wins30, losses30=losses30, pnl30=pnl30, cost30=cost30, wr30=wr30, roi30=roi30,
        n7=n7, pnl7=pnl7,
        min_ts=min_ts, max_ts=max_ts,
        pos_n=n_pos, pos_cost=pos_cost, pos_cur=pos_cur, pos_cash=pos_cash,
        redeem_n=redeem_n, redeem_val=redeem_val, redeem_loss_cost=redeem_loss_cost,
    ))

def fmt_usdc(v):
    v = Decimal(v)
    if abs(v) >= 1_000_000: return f"{v/1_000_000:.2f}M"
    if abs(v) >= 1_000: return f"{v/1_000:.2f}K"
    return f"{v:.2f}"

def ts_str(ms):
    if not ms: return "-"
    return datetime.fromtimestamp(ms/1000, tz=timezone.utc).strftime("%Y-%m-%d")

# 打印：分两段表
print("# 1) 真实账户历史已结算（closed-positions，所有历史）")
hdr = ["addr","n","W/L/F","win%","cost","realized_pnl","ROI%","first","last"]
print("\t".join(hdr))
for r in sorted(rows, key=lambda r: -r["pnl"]):
    print("\t".join([
        r["addr"][:10]+"…"+r["addr"][-4:],
        str(r["closed_n"]), f"{r['wins']}/{r['losses']}/{r['flats']}",
        f"{r['win_rate']:.1f}",
        fmt_usdc(r["cost"]), fmt_usdc(r["pnl"]), f"{r['roi']:.1f}",
        ts_str(r["min_ts"]), ts_str(r["max_ts"]),
    ]))

print("\n# 2) 真实账户近 30 天已结算")
hdr = ["addr","n30","W30/L30","wr30%","cost30","pnl30","ROI30%","n7","pnl7"]
print("\t".join(hdr))
for r in sorted(rows, key=lambda r: -r["pnl30"]):
    print("\t".join([
        r["addr"][:10]+"…"+r["addr"][-4:],
        str(r["n30"]), f"{r['wins30']}/{r['losses30']}",
        f"{r['wr30']:.1f}",
        fmt_usdc(r["cost30"]), fmt_usdc(r["pnl30"]), f"{r['roi30']:.1f}",
        str(r["n7"]), fmt_usdc(r["pnl7"]),
    ]))

print("\n# 3) 真实账户当前持仓（positions 实时）")
hdr = ["addr","open_n","init_cost","cur_val","cash_pnl","redeem_n","redeem_val","redeem_loss_cost"]
print("\t".join(hdr))
for r in sorted(rows, key=lambda r: -r["pos_cost"]):
    print("\t".join([
        r["addr"][:10]+"…"+r["addr"][-4:],
        str(r["pos_n"]),
        fmt_usdc(r["pos_cost"]), fmt_usdc(r["pos_cur"]), fmt_usdc(r["pos_cash"]),
        str(r["redeem_n"]), fmt_usdc(r["redeem_val"]),
        fmt_usdc(r["redeem_loss_cost"]),
    ]))

with open(f"{OUT}/summary.json","w") as f:
    json.dump([{k:(str(v) if isinstance(v,Decimal) else v) for k,v in r.items()} for r in rows], f, indent=2, default=str)
print(f"\nsaved: {OUT}/summary.json", file=sys.stderr)