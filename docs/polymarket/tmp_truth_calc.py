#!/usr/bin/env python3
"""
口径修正：Polymarket /closed-positions 端点的"已关闭"实际仅包含 redeemed=true 的盈利单；
亏损归零的持仓会留在 /positions 端点（redeemable=true，currentValue=0）。
真实历史 = closed-positions（已 claim 盈利）+ positions 中 redeemable=true 且 currentValue=0 的归零亏损。
"""
import json, glob, os
from decimal import Decimal, getcontext
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
    if x in (None,""): return Decimal(0)
    try: return Decimal(str(x))
    except Exception: return Decimal(0)

def fmt(v):
    v = Decimal(v)
    if abs(v) >= 1_000_000: return f"{v/1_000_000:.2f}M"
    if abs(v) >= 1_000: return f"{v/1_000:.2f}K"
    return f"{v:.2f}"

print("addr\tcl_n\tcl_w\tcl_l\tcl_pnl\tcl_cost\tunclaim_loss_n\tunclaim_loss_cost\ttrue_n\ttrue_w\ttrue_l\ttrue_pnl\ttrue_cost\ttrue_wr%\ttrue_roi%\topen_live_n\topen_live_cost\topen_live_pnl")
for a in ADDRS:
    files = sorted(glob.glob(f"{OUT}/{a}_closed_*.json"), key=lambda p: int(p.split("_closed_")[-1].rstrip(".json")))
    seen = set()
    closed = []
    for fp in files:
        try: d = json.load(open(fp))
        except Exception: continue
        if not isinstance(d, list): continue
        for p in d:
            k = (p.get("asset"), p.get("conditionId"), p.get("timestamp"), str(p.get("totalBought") or ""))
            if k in seen: continue
            seen.add(k); closed.append(p)
    cl_n = len(closed)
    cl_w = sum(1 for p in closed if dec(p.get("realizedPnl")) > 0)
    cl_l = sum(1 for p in closed if dec(p.get("realizedPnl")) < 0)
    cl_pnl = sum((dec(p.get("realizedPnl")) for p in closed), Decimal(0))
    cl_cost = sum((dec(p.get("totalBought")) * dec(p.get("avgPrice")) for p in closed), Decimal(0))

    pos_path = f"{OUT}/{a}_positions.json"
    positions = []
    if os.path.exists(pos_path):
        try: positions = json.load(open(pos_path))
        except Exception: positions = []
    # 区分 live / unclaim_loss / unclaim_win
    unclaim_loss_n = 0; unclaim_loss_cost = Decimal(0); unclaim_loss_pnl = Decimal(0)
    unclaim_win_n = 0; unclaim_win_cost = Decimal(0); unclaim_win_pnl = Decimal(0)
    live_n = 0; live_cost = Decimal(0); live_pnl = Decimal(0)
    for p in (positions if isinstance(positions, list) else []):
        ic = dec(p.get("initialValue")); cv = dec(p.get("currentValue")); cp = dec(p.get("cashPnl"))
        if p.get("redeemable"):
            if cv == 0:  # 归零的亏损
                unclaim_loss_n += 1; unclaim_loss_cost += ic; unclaim_loss_pnl += cp  # cp ≈ -ic
            else:  # 有 claim 价值的赢单尚未 claim
                unclaim_win_n += 1; unclaim_win_cost += ic; unclaim_win_pnl += cp
        else:
            live_n += 1; live_cost += ic; live_pnl += cp

    # 真实历史 = closed + unclaim_loss + unclaim_win（这些都已结算）
    true_n = cl_n + unclaim_loss_n + unclaim_win_n
    true_w = cl_w + unclaim_win_n  # 假设 unclaim_win 都是赢
    true_l = cl_l + unclaim_loss_n
    true_pnl = cl_pnl + unclaim_loss_pnl + unclaim_win_pnl
    true_cost = cl_cost + unclaim_loss_cost + unclaim_win_cost
    true_wr = (true_w / true_n * 100) if true_n > 0 else Decimal(0)
    true_roi = (true_pnl / true_cost * 100) if true_cost > 0 else Decimal(0)

    print("\t".join([
        a[:10]+"…"+a[-4:],
        str(cl_n), str(cl_w), str(cl_l),
        fmt(cl_pnl), fmt(cl_cost),
        str(unclaim_loss_n), fmt(unclaim_loss_cost),
        str(true_n), str(true_w), str(true_l),
        fmt(true_pnl), fmt(true_cost),
        f"{true_wr:.1f}", f"{true_roi:.1f}",
        str(live_n), fmt(live_cost), fmt(live_pnl),
    ]))