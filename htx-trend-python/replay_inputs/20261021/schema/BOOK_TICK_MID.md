# 可选 book / tick / mid 夹具

本目录的 `SYNTHETIC_NOT_LIVE_book_mid.example.csv` 是空窗说明用的合成样例：价格是 1.00/2.00，时间是 2099-01-01，`source=SYNTHETIC_NOT_LIVE`。它**不是** 2026-10-20/21 的现网盘口，也**没有**写入 `MANIFEST.json` 的 `optional_book`（该字段为 `null`）。完整 scan 不会读取它。样例字节 SHA256：`4be998d4f5540f02bcf816df2c0bb211c9718a35c1d4bf9274c05d01fd1a08a7`（只用于辨认文件，不是重放锁）。

## 何时启用

`MANIFEST.json`：

```json
"optional_book": {
  "file": "book_mid.csv",
  "sha256": "<文件字节 SHA256>",
  "kind": "book",
  "provenance": "synthetic"
}
```

- `optional_book` 为 `null` 或省略：管理盘口仍用形成中 15m bar 的 open 作 mid；无形成中 bar 时回退上一根 close。不得因为旁边放了一个 CSV 就静默改用它。
- 声明了 `optional_book`：文件必须落在输入目录内，SHA256 与 MANIFEST 一致，否则拒绝重放。
- `provenance=synthetic` 时，每一行 `source` 必须包含 `SYNTHETIC`，避免合成文件被当成现网导出。
- `provenance=evidence` 只留给事后只读导出的真实行情。本轮没有这种文件。

## 列

`ts_ms,ts_cst,contract,bid,ask,mid,last,source`

- 时间：`ts_ms`（Unix 毫秒）与 `ts_cst`（`YYYY-MM-DD HH:MM:SS`，东八区、秒级）至少填一个。两个都填时必须落在同一秒。
- `contract`：只接受 `BTC-USDT`、`ETH-USDT`。
- `kind=book`：必须有 `bid` 与 `ask`，且 `bid<=ask`。若填写 `mid`，必须等于 `(bid+ask)/2`。
- `kind=mid`：必须有 `mid`。`bid` 与 `ask` 都空时，按显式 mid 路径记 `bid=ask=mid`（这是行内写明的价格，不是从 OHLC 造出来的）。若填写 bid/ask，同样必须满足 `mid=(bid+ask)/2`。
- `kind=tick`：有 `bid`+`ask` 时用法与 book 相同；否则用 `last`（或单独的 `mid`）记 `bid=ask=该价`。`last` 与 `mid` 同时出现时必须相等。
- 同一合约、同一 `ts_ms` 不能重复。空文件拒绝。

## 管理时刻怎么取

`top_of_book` 只取 `ts_ms <= now_epoch*1000+999` 的最后一条。更晚的行视为未来报价，不用。该合约在该时刻还没有报价时，重放直接失败，**不**回退 forming-open，也**不**用 `spread_rate` 去改写夹具里的 bid/ask。信号 K 线窗口仍只要已收盘线。

## 本轮缺口（现网夹具仍缺）

| 仓 | 时间窗（CST） | 缺什么 |
| --- | --- | --- |
| BTC `36733c1f`（02:15） | 2026-10-21 02:00–09:30 | 入场及管理时刻 bid/ask/mid 或 tick。参考腿 09:00:21 qty 1、09:15:54 qty 1 |
| ETH `f56d3d17` | 2026-10-20 19:00–20:00 | 同上。参考腿 19:30:46 qty 5、19:45:32 qty 2 |
| ETH `93cef710`（23:00） | 平仓 2026-10-21 13:45:33 | 这是 K 线覆盖缺口，不是盘口缺口。现有重放止于 10:30:59，15m 最后 open 12:00。无额外 K 线则维持 `EXIT_OUTSIDE_KLINE_COVERAGE`，不外推、不强平 |

无上述真实行情时，完整逐腿可达上界是 **9/12**。不要用合成价去填这两个窗。
