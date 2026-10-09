---
title: HTX 短持仓只读证据 2026-10-09
type: incident-evidence
status: verified-observation
created: 2026-10-09
---

# HTX 短持仓只读证据

来源：2026-10-09 本会话通过 SSH 只读查询 `49.51.250.174` 的 `htx_trade_strategy.htx_trend_position / htx_trend_order / htx_trend_exit_decision`；看板完整响应快照 `asOf=2026-10-09 19:49:04`。时区 Asia/Shanghai。

线上 JAR SHA-256：`33fc9c03c90e0d4cf28665d434c0ed1290d6725bc24e99b20efb4d7e0b15e744`。本次没有下载并逐字节映射该 JAR 对应源码提交。

## 样本与口径

| position_id | 主账户空单 | ENTRY FILLED updated_at | CLOSE FILLED updated_at | 确认间隔 | 最终净收益 U |
|---|---|---|---|---|---:|
| 309 | LTC | 10-09 00:31:24.234 | 10-09 00:35:03.431 | 3m39.197s | -0.546312 |
| 307 | DOGE | 10-08 23:46:22.753 | 10-08 23:50:43.390 | 4m20.637s | -0.28696408 |
| 305 | ETH | 10-08 22:01:13.673 | 10-08 23:13:36.517 | 72m22.844s | +0.152352 |
| 303 | BTC | 10-07 22:01:31.317 | 10-07 22:03:36.597 | 2m05.280s | -0.267418 |
| 299 | LTC | 10-07 20:46:45.159 | 10-07 20:54:00.266 | 7m15.107s | -0.3172688 |

updated_at 是系统成交确认时间，不能替代交易所逐笔成交时刻。position.created_at 到 updated_at 的间隔包含下单/确认延迟。本表不混入 TradeX 镜像仓位。

## v4 关键决策事实

| 项目 | LTC 309 | DOGE 307 |
|---|---|---|
| 首次持仓状态 | WEAKENING | WEAKENING |
| trailing_arm_reason | NET_POSITIVE_MFE_COST_FLOOR | NET_POSITIVE_MFE_COST_FLOOR |
| 武装时间 | 00:34:08.930 | 23:48:48.292 |
| 武装时当前净浮盈 | +0.3900624U | +0.1569134U |
| 观察到的最大净浮盈 | +0.3900624U | +0.32528072U |
| FULL_CLOSE 决策时间 | 00:34:30.094 | 23:50:10.138 |
| 决策参考价 | 62.52 | 0.083534 |
| 有效保护价 | 62.489976 | 0.083335160714285714 |
| 决策估计净收益 | -0.2341872U | -0.25295048U |
| 退出原因 | profit-protection-floor-missed-risk-exit | profit-protection-floor-missed-risk-exit |
| signal / trendState | FLAT / WEAKENING | FLAT / WEAKENING |
| completedBarProtectionBreaches / required | 0 / 2 | 0 / 2 |
| reversalEvidence | 0 | 0 |
| majorTimeframeOpposition / marketContextOpposes | 0 / false | 0 / false |
| flatStreak / required | 0 / 3 | 0 / 3 |
| flatIndependentFailureEvidence | false | false |
| maxFavorableR | 0.087719298245 | 0.108130762782 |

LTC 从武装到全平决策约 21 秒；DOGE 约 82 秒。退出当刻 `netPositiveMfeCostFloor=false` 不代表此前从未武装，持久化的 armed 状态仍存在。

LTC 初始止损 63.68，DOGE 初始止损 0.085838；退出参考价均未触及该初始止损。BTC 303 和 LTC 299 记录旧 `trend-weakened-protection-breach` 原因，同样先武装再实时触线。ZEC 297 的近 6 分钟退出记录 `MODEL_INVALIDATION`，属于不同风险路径，不据此主张延迟硬止损。

## 能证明与不能证明

- 能证明：上述仓位的实际退出来源、规则先后顺序及确认不足；短持仓不是只由前端显示造成。
- 不能证明：若继续持有就会盈利；没有完整反事实盘口/成交路径，不提供“改完能赚多少”。
- 不能证明：所有版本、所有模型均有相同问题；当前样本用于事故复现，不充当独立测试集。
- 看板主账户历史 298 笔净收益 -92.540323U 是跨版本累计；不能全部归因于本条规则。
- TradeX 38 条 ACCEPTED 缺成交事实、position 与 ledger 净收益存在差异，是实验数据完整性问题；不能作为零收益样本。

## 设计入口

[v5 技术设计](../../../architecture/CoinStrategy-Trend-Exit-Design-v5.md) · [v5 验收](../../../architecture/Trend-Exit-v5-Validation.md)。
