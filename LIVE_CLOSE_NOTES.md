# LIVE-CLOSE Notes（LIVE 平仓账本残缺告警/补写）

- 分支：`feat/live-close-ledger-hardening`
- 基线 tip：`f530b7342a14ad9b0eea42bdcdffc9d037f6d488`（G3-EXIT #42）
- 编码：Codex CLI `codex exec -s workspace-write`；日志 `/Users/jenniferni/jenifer/build/codex-logs/live-close-20261022/`
- Codex：主体代码写完后触发 usage_limit（提示 try again at **1:45 PM**，按 Asia/Shanghai CST 记为 **2026-10-22 13:45 CST**）；NOTES/pytest/commit/PR 由执行器落盘完成。未改判单/exits 热路径；未改 Java。

## 根因路径（对照仓 286）

仓 286（PEPE-USDT SHORT）：订单 ENTRY+CLOSE 均 FILLED，ledger 仅有 POSITION_INTENT / OPEN_ORDER / OPEN_FILL / FEE(entry) / **CLOSE_ORDER**，缺 **CLOSE_FILL / FEE(close) / REALIZED_PNL**。

现网 LIVE 证据事件键全为 Java 风格 `trend-*`（`CoinStrategyLedgerRecorder.recordTrendCloseFill`）。`HtxTrendRecorder.recordLedger` 吞掉投影异常，可在订单已 FILLED / CLOSE_ORDER 已写入后中断拆事件投影——与 286 形态一致。

Python LIVE 路径 `order_accounting.apply_exchange_order` 原先对 close 只写**单条** sha256 `CLOSE_FILL`（费/盈亏塞同行），与现网 LIVE「拆事件」口径不一致；且当订单行已是终态累计快照（delta=0）时不会补写缺失经济事件。

**本单修复（Python LIVE 写路径，不回写历史、不改 PAPER 公式）：**
1. CLOSE/PARTIAL_CLOSE 投影改为拆事件写全：`CLOSE_FILL` + `FEE` + `REALIZED_PNL`（稳定键 `trend-close-fill|trend-fee:CLOSE_FILL|trend-realized-pnl` + 累计 transition 后缀保证幂等）。
2. 投影后检测残缺并告警 `LIVE_CLOSE_LEDGER_INCOMPLETE`。
3. 离线幂等补写库函数 + dry-run CLI（禁止改已存在行；本令不对 286 现网执行）。

## 修复点（文件 + 函数）

| 文件 | 函数/内容 |
|---|---|
| `htx-trend-python/src/htx_trend/order_accounting.py` | `apply_exchange_order`：close 走 `split_close_events`；事后 `detect_incomplete_close_ledger` + `emit_incomplete_close_alert` |
| `htx-trend-python/src/htx_trend/live_close_ledger.py` | `split_close_events` / `detect_incomplete_close_ledger` / `emit_incomplete_close_alert` / `plan_close_ledger_backfill` / `apply_close_ledger_backfill` |
| `htx-trend-python/src/htx_trend/tools/live_close_ledger_backfill.py` | 本地 JSON → dry-run 计划（无 DB 写旗标） |
| `htx-trend-python/tests/test_order_accounting.py` | 拆事件累计/费更正幂等；任一条 insert 失败整单 rollback |
| `htx-trend-python/tests/test_live_close_ledger.py` | 286 形态检测告警；补写 insert-only；legacy combined 拒双记 |

未改：`exits.py` / `runtime.py` / `paper_worker.py` / `execution.py` / `paper_db_persist.py` / Java recorder。

## 告警

- 方式：标准 logging warning
- code：`LIVE_CLOSE_LEDGER_INCOMPLETE`
- 字段：`position_id` / `client_order_id` / `missing_types`
- 触发：CLOSE/PARTIAL_CLOSE 已 FILLED（或终态且 filled>0）但缺 CLOSE_FILL 或 FEE 或 REALIZED_PNL
- 可测：`tests/test_live_close_ledger.py::test_detection_and_warning` 等

## 补写工具

- 有：库函数 `plan_close_ledger_backfill` / `apply_close_ledger_backfill(dry_run=True|False)`；CLI `python -m htx_trend.tools.live_close_ledger_backfill <fixture.json>`（仅 dry-run 打印计划）
- **未**对仓 286 或任何现网 DB 执行补写
- 仅 INSERT IGNORE 缺事件；已存在 event_key 跳过；legacy 单行带经济量的 CLOSE_FILL 拒绝自动拆补（防双记）

## 声明

- **未**回写/UPDATE 历史 ledger 行
- **未**为对齐 headline −3.219 而改 PAPER 或 LIVE 盈亏公式
- **未**把 PAPER「CLOSE_FILL 净扣分摊开仓费」热搬 LIVE
- **未**出包、未装机、未热更、未 SSH 现网

## 测试

```text
PYTHONPATH=src pytest tests/test_order_accounting.py tests/test_live_close_ledger.py -q
→ 20 passed

PYTHONPATH=src pytest -q --continue-on-collection-errors
→ 475 passed
```

日志：`/Users/jenniferni/jenifer/build/codex-logs/live-close-20261022/pytest-related.log` / `pytest-full.log`
