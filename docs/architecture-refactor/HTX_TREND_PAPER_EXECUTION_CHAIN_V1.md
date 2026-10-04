---
title: "HTX 趋势纸面执行链路 v1"
type: implementation-contract
status: active
created: 2026-09-23
updated: 2026-10-04
tags: [htx, trend, paper-execution, run-id, contract-test]
---

# HTX 趋势纸面执行链路 v1

> **结论先行**：`SIG-01 → PLAN-01 → EXT-01 → EXT-02` 已通过同一个稳定 `run_id` 连成一条只读、纸面执行链路。新增端到端合同测试会从真实候选选择生成的 `run_id` 开始，写入价格计划并消费真实 `ENTRY_READY` 和价格计划快照，最后验证纸面退出计划与纸面账本；任一段改掉主键、快照类型或回传边界，测试都会失败。

## 1. 当前链路

[流程图 · SIG-01 到 EXT-02 纸面执行链路](diagrams/htx-trend-paper-execution-chain-v1-flowchart.drawio)

| 阶段 | 当前输入 | 当前输出 | 关联不变量 |
|---|---|---|---|
| `SIG-01` | 合约、入场周期、已完成 K 线时间 | 候选证据快照、候选账本 | `run_id = trend-entry:<合约>:<周期>:<K线时间>`。 |
| `PLAN-01` | 已通过最终执行预检的候选与该 `run_id` | `trend-price-plan-v1` | 冻结方向、计划张数、最差成交价、TTL、初始止损、目标和预计往返成本。 |
| `EXT-01` | 同一价格计划 | `signal-outbox-v1:ENTRY_READY` | outbox 与价格计划使用完全相同的合约、周期、K 线、policy 和 `run_id`；事件明确 `NO_EXCHANGE_ORDER`。 |
| `EXT-02` | 外部纸面订单事件与成交回传 | `htx_trend_paper_execution_event`、`paper-exit-plan-v1`、`htx_trend_paper_execution` | 事件表追加保存 `INTENT → SUBMITTED → ACCEPTED → PARTIALLY_FILLED/FILLED`、拒绝、过期或撤单；旧回传接口兼容地写出同一组事件。成交回传仍须先找到同一 `run_id` 的 `ENTRY_READY` 和价格计划，再按实际成交均价与成交张数重算风险、1R、2R、结构目标与预计成本。 |

## 2. 自动合同测试

测试文件：[HtxTrendPaperExecutionFlowTest.java](../../coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/service/HtxTrendPaperExecutionFlowTest.java)。

它使用真实的 `HtxTrendCombinationService` 和 `HtxTrendCombinationRecorder`，以内存化快照 mapper 代替数据库，验证下列不可替代的连续性：

1. 真实组合候选选择产生稳定 `run_id`；该值被原样传入 `LONG` 的 `trend-price-plan-v1`。
2. 写入价格计划后，自动出现同一 `run_id` 的 `ENTRY_READY`。
3. 以同一身份回传一笔部分成交后，服务从上述两个真实快照读取约束；没有手工伪造价格计划或 outbox。
4. 退出计划的 `source.runId`、`source.executionKey`、成交方向、成交价与成交张数均与回传一致；1R、2R、风险、预计成本与净 R 使用实际部分成交重新计算。
5. 原始候选证据、价格计划和 outbox 在回传后仍保留；纸面执行服务没有 HTX 合约下单客户端依赖。
6. 生命周期事件只能从上一个已记录状态迁移；累计成交张数不得倒退，终态后不得继续成交。部分成交后撤单与“撤单后迟到成交”均有合同测试。

## 3. 边界与未完成项

这条链路只负责冻结、发布和验证模拟成交。它不会触发或拦截现有的 HTX 真实下单链路，也不改变真实 Gate、仓位、环境开关或账户配置。

`ENTRY_CANCELLED`、`PLAN_EXPIRED`、`EXIT_REDUCE`、`EXIT_CLOSE` 的 outbox 事件、订阅鉴权，以及基于有效期内真实价格计划样本的 `BT-03` 正式比较仍未完成，继续以 [策略未完成任务清单](COINSTRATEGY_STRATEGY_BACKLOG_V1.md) 为唯一排期入口。
