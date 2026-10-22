# G3-ALIGN 交审短笺

分支 `feature/g3-align-20261022`，基线 `720809c160b541bd1ce82c8fd58f3c210a8952aa`（含 PR#48 D2-C2C5，以及已合入的 PR#42 G3-EXIT）。只改离线 replay。

## 逐腿分数

- 完整逐腿 **9/12**，未达到 ≥11/12。
- 入场 **12/12**，多发 **0**，漏发 **0**，相对 PR#42 没有回退。
- `summary`：live_count=12，full_matches=9，entry_matches=12，extra_count=0，missing_count=0，exit_mismatch_count=2，outside_coverage_count=1。
- 本轮可达上界 **9/12**。没有真实 book/tick，不能把两笔 `EXIT_LEGS_MISMATCH` 收成通过；没有更长 K 线，不能把 `ETH 23:00` 收成通过。即便以后补上两笔真实盘口，ETH 23:00 仍在现有 K 线之外，那时候的理论上界才是 11/12。本轮两个条件都不具备。

## 三笔边界

- BTC 02:15 / `36733c1f`：仍是 `EXIT_LEGS_MISMATCH`。参考 09:00:21 减 1 张 profit-at-risk、09:15:54 减 1 张 trend-weakened；重放 05:00:30 与 09:00:30 各减 1 张 profit-at-risk。缺 2026-10-21 02:00–09:30 CST 的真实 bid/ask/mid 或 tick。forming-open 入场约 82610，现网 82633.5。未回滚 A，未注入参考退出。
- ETH `f56d3d17`：仍是 `EXIT_LEGS_MISMATCH`。参考 19:30:46 减 5 张 trend-weakened-partial、19:45:32 减 2 张 trend-weakened；重放 19:30:30 减 3 张 profit-at-risk、19:45:30 减 4 张 trend-weakened。缺 2026-10-20 19:00–20:00 CST 的真实管理盘口。
- ETH 23:00 / `93cef710`：维持 `EXIT_OUTSIDE_KLINE_COVERAGE`。参考平仓 2026-10-21 13:45:33，重放无退出腿。重放 end=10:30:59，15m 最后 open=12:00。不外推、不强平。

## 夹具

- `MANIFEST.optional_book` 为 `null`。未加载 book/tick/mid，管理盘口仍是 forming-bar-open。
- 可选路径已接上：声明 file、sha256、kind（book|tick|mid）、provenance（synthetic|evidence）后按哈希加载；未声明的旁路 CSV 不读；夹具未覆盖的时刻拒绝，不回退虚构盘口。
- schema 与时间窗：`htx-trend-python/replay_inputs/20261021/schema/BOOK_TICK_MID.md`。列：`ts_ms,ts_cst,contract,bid,ask,mid,last,source`。
- `schema/SYNTHETIC_NOT_LIVE_book_mid.example.csv` 是 2099-01-01、价格 1.00/2.00 的合成样例，SHA256 `4be998d4f5540f02bcf816df2c0bb211c9718a35c1d4bf9274c05d01fd1a08a7`，没有挂到 `optional_book`，完整 scan 不读它。不是现网盘口。

## 验证

- 解释器：`/Users/lichangzheng.ray/build/msa-p1-orderpx-20261022/.venv311/bin/python`
- `PYTHONPATH=src pytest -q tests/test_scan_replay.py`：**22 passed**
- `PYTHONPATH=src pytest -q`：**503 passed**，77 条既有 sqlite datetime DeprecationWarning，0 failed
- 同一输入连续两次 `python -m htx_trend.scan_replay` 写出的 `report.json` 与 `report.md` 逐字节一致

## 硬禁

未改 `runtime.py` / `paper_worker.py` / `exits.py` / `execution.py`，未碰开仓、lob_prob、ORDERPX。未回滚 A forming-bar-open。未装机、未热更、未出运行时包、未 SSH、未连接交易机。未虚构盘口凑 ≥11/12。未 push、未开 PR。
