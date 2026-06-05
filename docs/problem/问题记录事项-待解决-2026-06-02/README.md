# 诊断脚本 — 2026-06-02 巡检

配套报告：[../问题记录事项-待解决-2026-06-02.md](../问题记录事项-待解决-2026-06-02.md)

- `diagnose.py`：主巡检（开单/胜率/卡点/告警/freshness），`pymysql` 直连 `coin_strategy`，**只读**。
- `diag_downstream.py`：下游链路（OPEN 持仓时长、结算/记账 freshness、日报、挂起结算、order_stats）。

运行：`pip install pymysql --break-system-packages && python3 diagnose.py`
说明：沙箱 45s 超时下，`cash_ledger` 全表 `SUM(CASE...)` 聚合与 `strategy_runtime_config` 全表读取易超时，本期改用分批小查询/`LIMIT`/计数取证。
脚本沿用上一版（2026-06-01），未自定义。
