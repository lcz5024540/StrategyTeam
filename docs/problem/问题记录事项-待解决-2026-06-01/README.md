# 巡检诊断脚本 — 2026-06-01

配套报告：[../问题记录事项-待解决-2026-06-01.md](../问题记录事项-待解决-2026-06-01.md)

只读直连 `coin_strategy`@`49.51.250.174`（MySQL 8.0.45），**不执行任何写操作**。

- `diagnose.py`：开单 / 胜率 / 卡点 / 告警主查询（沿用 05-31 脚本）。
- `diag_downstream.py`：下游链路新鲜度（OPEN 账龄、结算/记账末次时间、日报、pending-oracle、聪明钱快照）。

运行：

```bash
pip install pymysql --break-system-packages
python3 diagnose.py
python3 diag_downstream.py
```

> 注：本期 `strategy_runtime_config` 全表读取超时，关键参数沿用上版巡检值（见报告第二节）。
