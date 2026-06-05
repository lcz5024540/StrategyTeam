# 巡检诊断脚本 — 2026-05-31

配合 [`问题记录事项-待解决-2026-05-31.md`](../问题记录事项-待解决-2026-05-31.md) 使用，用于复跑当期数据口径。

- `diagnose.py`：开单 / 胜率 / 卡点主诊断（follow_task、smart_wallet_order、trade_order、signal_shadow、cash_ledger、daily_report、strategy_alert、trade_event 等）。
- `diag2.py`：补充诊断（领头钱包胜率快照、下游作业冻结证据、OPEN 仓位龄期、runtime_config 关键参数）。

## 运行

需在可访问 `49.51.250.174:3306` 的环境（服务器本机或网络可达机器）运行：

```bash
pip install pymysql
python3 diagnose.py
python3 diag2.py
```

库连接：`coin_strategy`@`49.51.250.174:3306`（凭据见脚本内 `DB`）。脚本**只读、不写库**。

## 本期关键发现（口径锚点）

- `NOW() = 2026-05-31 09:11`，MySQL 8.0.45。
- 最后一次真实开仓 `smart_wallet_order` = 2026-05-29 10:50:37 → ~46h 零开单。
- 下游写入在 2026-05-29 12:25 后停止：`cash_ledger` 末条 12:25:13、`trade_order` 末次结算 07:35 / 末次更新 12:25、`daily_report` 05-30 与 05-31 缺失。
- 结算胜率 13.33%（2W/13L），net -84.01 USDC，avg ROI -62.19%。
- 49 笔 OPEN 仓位锁定 415.09 USDC，平均持有 50.6h；paper-book 余额 -574.95 USDC。
- 近 24h `signal_shadow` 被拒主因 `G4_CAPITAL_DEPLETED` 537 条。
