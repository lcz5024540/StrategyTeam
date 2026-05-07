#!/usr/bin/env python3
"""
基于"closed-positions 接口按 pnl 分组（先全 W 再全 L）"的事实，
用二分法找到 W→L 的拐点 offset，再从拐点开始密集抓 L 段，避免抓完 几万条 wins。

策略：
  1. 用 curl 探针，每次抓 limit=50 看返回的 50 条全部 realizedPnl 的符号
  2. 二分查找：低端 lo=已知最后一页全 W 的 offset，高端 hi=未知（先指数扩大直到出现 L 或返回 < 50）
  3. 找到拐点后，从拐点 offset 起线性抓到末尾（终止条件 cnt < 50）
"""
import json, subprocess, time, os, sys
from pathlib import Path

OUT = Path("/tmp/poly_api")
RESOLVE = "data-api.polymarket.com:443:172.64.153.51"

def fetch(addr, offset, limit=50):
    fp = OUT / f"{addr}_closed_{offset}.json"
    if fp.exists():
        try:
            d = json.load(open(fp))
            if isinstance(d, list): return d
        except Exception: pass
    cmd = ["curl", "-sS", "-m", "40",
           "--resolve", RESOLVE,
           "-A", "Mozilla/5.0",
           "-o", str(fp),
           f"https://data-api.polymarket.com/closed-positions?user={addr}&limit={limit}&offset={offset}"]
    subprocess.run(cmd, check=False)
    try:
        d = json.load(open(fp))
        if not isinstance(d, list): return []
        return d
    except Exception: return []

def page_summary(d):
    n = len(d)
    w = sum(1 for p in d if float(p.get("realizedPnl") or 0) > 0)
    l = sum(1 for p in d if float(p.get("realizedPnl") or 0) < 0)
    return n, w, l

def probe(addr, start_lo):
    # 1) 指数扩大找到一个 hi，使 hi 处出现 L 或 cnt<50
    print(f"\n=== {addr} ===")
    lo = start_lo
    hi = start_lo + 50
    while True:
        d = fetch(addr, hi)
        n, w, l = page_summary(d)
        print(f"  probe offset={hi} n={n} W={w} L={l}")
        if n < 50 or l > 0:
            break
        lo = hi
        hi = hi * 2 if hi > 0 else 50
        if hi > 500000:
            print("  hi exceeded 500000, abort"); return None
    # 2) 二分：lo 处全 W、hi 处出现 L 或 短页
    while hi - lo > 50:
        mid = ((lo + hi) // 2 // 50) * 50
        if mid <= lo: mid = lo + 50
        d = fetch(addr, mid)
        n, w, l = page_summary(d)
        print(f"  bisect offset={mid} n={n} W={w} L={l}")
        if n < 50 or l > 0:
            hi = mid
        else:
            lo = mid
    print(f"  -> boundary near offset={hi} (lo={lo})")
    return hi

def crawl_to_end(addr, start_offset):
    print(f"  crawling {addr} from offset={start_offset} to end ...")
    offset = start_offset
    prev_sig = None
    stuck_count = 0
    while offset < 500000:
        d = fetch(addr, offset)
        n, w, l = page_summary(d)
        # 重复检测
        sig = None
        if d:
            p = d[0]
            sig = (p.get("asset"), p.get("timestamp"), str(p.get("realizedPnl")))
        if sig is not None and sig == prev_sig:
            stuck_count += 1
            print(f"    offset={offset} n={n} W={w} L={l} [STUCK repeat#{stuck_count}]")
            if stuck_count >= 2:
                # 清理这页（本页数据重复，不可信）
                try: os.remove(OUT / f"{addr}_closed_{offset}.json")
                except Exception: pass
                print(f"    -> end reached at offset~={offset-50}, stopping")
                break
        else:
            stuck_count = 0
            print(f"    offset={offset} n={n} W={w} L={l}")
        prev_sig = sig
        if n == 0:
            try: os.remove(OUT / f"{addr}_closed_{offset}.json")
            except Exception: pass
            break
        offset += 50
        if n < 50: break

if __name__ == "__main__":
    targets = {
        # addr -> 已知最后一个全 W 页的 offset（来自第一/二轮抓数）
        "0xe8dd7741ccb12350957ec71e9ee332e0d1e6ec86": 29950,
    }
    for addr, start_lo in targets.items():
        boundary = probe(addr, start_lo)
        if boundary is not None:
            crawl_to_end(addr, boundary)