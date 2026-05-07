"""
验证 /closed-positions 接口是否支持 sortBy + sortDirection 参数。
尝试 sortBy = TIMESTAMP / CLOSED_AT / timestamp / closedAt / REDEEMED_AT
方向 = ASC / DESC
打印前 5 条的 closedAt / realizedPnl 观察排序规则。
"""
import json
import subprocess
import sys
import time

ADDR = "0xe8dd7741ccb12350957ec71e9ee332e0d1e6ec86"
BASE = "https://data-api.polymarket.com/closed-positions"


def fetch(params):
    q = "&".join(f"{k}={v}" for k, v in params.items())
    url = f"{BASE}?{q}"
    last_err = None
    for _ in range(3):
        try:
            body = subprocess.check_output(
                ["curl", "-sS", "--max-time", "20", "--retry", "2", "--retry-delay", "2", url],
                text=True,
                stderr=subprocess.STDOUT,
            )
            time.sleep(1.2)
            return json.loads(body)
        except Exception as e:
            last_err = str(e)[:200]
            time.sleep(2.5)
    return {"__error__": last_err}


def summarize(tag, data):
    if isinstance(data, dict) and "__error__" in data:
        print(f"[{tag}] ERROR: {data['__error__']}")
        return
    if not isinstance(data, list):
        print(f"[{tag}] non-list body: {str(data)[:200]}")
        return
    print(f"[{tag}] n={len(data)}")
    for i, x in enumerate(data[:5]):
        print(
            f"  #{i} closedAt={x.get('closedAt')} "
            f"realizedPnl={x.get('realizedPnl')} "
            f"cond={str(x.get('conditionId'))[:12]}"
        )


def main():
    base_params = {"user": ADDR, "limit": "10", "offset": "0"}

    # 1) 不传 sortBy：看默认
    summarize("default", fetch(base_params))

    # 2) 尝试 sortBy 的各种大小写
    for sort_by in ["TIMESTAMP", "timestamp", "CLOSED_AT", "closedAt", "REDEEMED_AT", "redeemedAt", "TIME"]:
        for direction in ["DESC", "ASC"]:
            p = dict(base_params, sortBy=sort_by, sortDirection=direction)
            summarize(f"sortBy={sort_by}/{direction}", fetch(p))


if __name__ == "__main__":
    main()