---
title: "ADR — 趋势退出系统全面重设计"
type: adr
status: accepted-direction
created: 2026-10-09
author: Codex / Ray
tags: [htx, exit, design]
---

# 趋势退出系统全面重设计

ADR 子类型：2c，跨信号、持仓、执行、账本与 Java/Python 的设计。用户于 2026-10-09 选择方案 B：“需要全部重新设计，整理提交到仓库中”。本记录接受全面重设计方向；具体参数为待验证的研究配置，不表示已经批准上线。

## 背景

线上 `fib-exit-engine-v4` 的主账户 LTC 309、DOGE 307 在成交确认后约 3 分 39 秒、4 分 21 秒结束。两笔退出决策均记录 `signal=FLAT`、`trendState=WEAKENING`、`completedBarProtectionBreaches=0`、`reversalEvidence=0`，执行 `FULL_CLOSE`。持仓首次评估已是 WEAKENING，不代表入场之后出现了新的反转。

原因是组合结构允许入场，基础趋势信号负责持仓；小额净浮盈激活成本保护后，实时触线分支在收盘确认分支之前返回全平。硬止损、利润保护、基础信号变弱三种语义发生耦合。

诊断来源、时间边界与数值见 [事故证据](../architecture-refactor/evidence/HTX_TREND_SHORT_HOLDING_20261009.md)。历史结果不证明新设计会盈利。

## 备选方案

| 方案 | 内容 | 取舍 |
|---|---|---|
| 0 保持现状 | 继续 v4 | 保留已观察到的提前退出路径，未满足用户目标 |
| A 局部调参 | 增大利润阈值、等待两根收盘 | 改动较少，但不能统一入场合同，也不能修正成交状态和再入场循环 |
| B 全面重设计 | 冻结入场理由、双时钟管理、分离硬风险与软退出、执行确认及结构再入场闭环 | 用户已选择；成本更高，必须承认等待确认可能扩大回吐和亏损 |
| C 强制最短持仓 | 所有仓位至少持有固定时间 | 会阻挡必要的硬风险退出，拒绝采用 |

## 决策

采用版本化合同 `trend-exit-v5`，共用输入、状态、动作与回放规则，Java 与 Python 实现同一纯决策语义。

1. 入场前冻结 `EntryThesis`，含主模型、结构身份、方向、周期、初始风险、费用与策略配置哈希。
2. 盘中只执行已定义的硬风险控制；软退出、利润保护激活及收紧只由入场后的完整 K 线事件推进。
3. 单独的 FLAT 不算模型失效，也不提供独立反转证据。
4. 显著净盈利和波动尺度同时满足后，才能进入利润管理；成本线是报告指标，不自动成为退出边界。
5. 软保护首次激活不得在同一事件触发退出；保护线采用上一完整事件生效的值判断，避免同根行情自触发。
6. 减仓与全平由明确规则选择，张数不足不得把普通部分止盈自动升级为全平。
7. 决策状态与订单执行状态分离；账本只接受实际成交，UNKNOWN/ACCEPTED 不伪装已完成。
8. 结构失效后设置再入场约束，重启、改策略版本或换 Java/Python 进程不能清掉约束。
9. 单账户、单策略交易权限只能由一个执行者持有。OBSERVE 没有真实订单权限。

## 影响范围与事实核验

本会话未提供 codegraph MCP，主机无 `codegraph` CLI，未生成或声称拥有图谱输出。采用精确符号与调用点检查；未来实现前仍需复核实际目标分支及新增调用方。

| 已有组件 | 已核验的调用/职责 | v5 变化 |
|---|---|---|
| HtxTrendFollowingService | 持仓路径 `loadSignal → advance → monitorActive → exitEngineDecision → closePosition` | 组装统一输入，调用版本路由，持久化决策与意图 |
| HtxTrendStateEvaluator | `evaluateHolding` 将 FLAT 判为 WEAKENING | v5 单独评估结构有效性和独立证据，不能复用该简化判断 |
| HtxTrendExitDecisionEngine | `evaluate` 在实时 WEAKENING 分支返回全平 | 保留 v4 给历史回放与既有仓位，新增 v5 单一动作仲裁 |
| HtxTrendProfitProtectionPlanner | 费用、ATR、MFE 推导保护价 | v5 成本、激活、软线、灾难保护分别建模 |
| HtxTrendRecorder / mapper | position、order、exit_decision | 原子状态推进、幂等意图及逐笔成交确认 |
| Python `exits.py` | `decide_v4`、`evaluate_holding` | 新增 v5，不以复制旧 FLAT 行为实现“对齐” |
| Python execution_service / mirror / mysql_store | 执行、镜像、存储边界 | 执行权围栏、成交预算与独立镜像账本 |
| Web / frontend | 趋势仪表盘 | 区分软警戒、硬止损、决策/执行、账户级收益来源 |

基线是 `support_htx_python@0f07a492`；线上 JAR SHA-256 见事故证据。源码基线与线上二进制不声称逐字一致，线上行为结论以数据库决策为准。

## 后果

- 正面：同一开仓假设贯穿持仓；软退出具备可审计的时间与证据门槛；减少规则互相覆盖的可能。
- 负面：等待收盘可能增加回吐、资金占用与尾部亏损；原生止损、费用数据及可靠对账增加实施工作。
- 中性：持仓变长只是诊断指标，不是优化目标；可能发现入场本身没有正期望，新退出不能弥补全部问题。

## 交付与上线边界

本提交只交付 ADR、技术设计、实验与验收合同、图示和历史证据。应用实现、数据库迁移、实盘参数、服务部署分别处于未执行状态。

技术设计：[CoinStrategy-Trend-Exit-Design-v5](../../architecture/CoinStrategy-Trend-Exit-Design-v5.md)。

## 回滚预案

回滚图见 [图 11-4 · 发布与所有权切换](diagrams/trend-exit-v5-rollout.drawio)。

触发条件为：任何重复订单/非 reduce-only 退出/执行权冲突，或本轮验收合同的硬风险与对账断言失败一次。立即冻结新入场，保持仍有有效执行权的持仓管理者；先查询在途订单和真实仓位，保留有效硬保护。已有 v5 仓位不热切到不识别其状态的 v4。空仓并确认无在途订单后才可回退新入场版本；非空仓需要另行批准接管合同。仅恢复旧 JAR 不构成安全回滚。

收益/回撤熔断数值由预注册实验与小仓预算确定，不以本 ADR 捏造实盘阈值。

## 配套图

- [图 11-1 · 架构与事实边界](diagrams/trend-exit-v5-architecture.drawio)
- [图 11-2 · 决策流程](diagrams/trend-exit-v5-decision.drawio)
- [图 11-3 · 执行与确认泳道](diagrams/trend-exit-v5-execution.drawio)
- [图 11-4 · 发布与所有权切换](diagrams/trend-exit-v5-rollout.drawio)
