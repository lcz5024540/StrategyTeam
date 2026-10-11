---
title: HTX 趋势退出 v5 M1 输入与账本审计
type: implementation-evidence
status: insufficient-evidence
created: 2026-10-10
---

# HTX 趋势退出 v5 M1 输入与账本审计

本轮只使用仓库源码和 2026-10-09 已冻结的只读事故证据，没有重新查询生产数据库、交易所或 TradeX。结论是：L0 规则实现可以开始，L2/L3 经济实验所需的执行级证据仍不完整，结果固定为 `INSUFFICIENT_EVIDENCE`。

## 已确认事实

| 项目 | 结论 | 证据/处置 |
|---|---|---|
| 事故样本 | LTC309、DOGE307、ETH305、BTC303、LTC299 有 position/order/exit_decision 汇总；时间为系统成交确认代理 | `HTX_TREND_SHORT_HOLDING_20261009.md` |
| Java 冻结回放 | 完整证据合同要求 final gate、preflight、entry/exit fills、fee/book/margin/leverage、v4 exit inputs；缺项返回 insufficient | `HtxTrendFrozenExecutionEvidence` / `HtxTrendFrozenExecutionReplay` |
| Java 合约数量 | 名义数量使用 `contract_size × contracts`，毛收益和估算手续费沿用同一数量 | `HtxTrendExitReplayEngine.quantity/fee` |
| Python Paper 入场费 | 原公式遗漏 `contract_size` | 已改为 `entry_price × contract_size × contracts × fee_rate` |
| Python Paper 平仓费 | 原公式遗漏 `contract_size` | 已改为 `reference_price × contract_size × remaining_contracts × fee_rate`，并加回归测试 |
| v5 数值合同 | 金额使用 Decimal/BigDecimal；policy hash 必须与入场合同一致 | Java/Python v5 合同与共享 golden fixture |

## 仍缺失的执行级事实

1. 五个事故仓位的交易所逐笔 entry/exit fill：trade_id、order_id、成交时刻、方向、价格、张数和逐笔费用。
2. 退出后完整行情、资金费归属及当时可执行盘口；现有数据不能计算“若继续持有”的成交级反事实。
3. TradeX 38 条 `ACCEPTED` 对应的订单查询、fills、真实剩余仓位和 ledger 收敛证据；不得按 38 笔零收益处理。
4. 分段退出时 entry fee、exit fee、funding 的逐量分摊及最后一笔尾差吸收样本。
5. 线上 JAR SHA-256 与精确源码 commit 的 manifest 映射。

## 阶段边界

- 允许：L0 纯决策核、模型适配器、跨语言 golden、离线故障单测。
- 暂不允许：L2/L3 净收益结论、参数优选、OBSERVE/PAPER 晋级结论、LIVE 路由或账户执行权切换。
- M7 前必须补齐上述执行事实并冻结 experiment manifest；无法补齐的样本只能进入 L1 事故重放或带明确假设的 OHLC 敏感性分析。

## 结果

M1 的代码口径审计和缺口登记已完成；数据完整性门 G0 未通过。该结果不阻塞 M2-M4 的 L0 实现，但继续阻塞 M7 经济实验与后续上线晋级。
