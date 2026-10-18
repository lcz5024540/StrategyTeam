---
title: 交易策略知识地图
type: index
status: active
tags: [strategy, arbitrage]
updated: 2026-10-18
---

# 交易策略知识地图 (MOC)

本地图汇总 Polymarket 之外的全部交易策略文档（CEX 与链上）。Polymarket 策略见 [Polymarket MOC](MOC-Polymarket.md)。

总纲：[COINSTRATEGY_STRATEGY_MASTER_PLAN_V1](architecture-refactor/COINSTRATEGY_STRATEGY_MASTER_PLAN_V1.md) — CoinStrategy 趋势 + 套利双引擎策略总纲：资金分层、策略族、统一风控、统一账本与落地路线。

路线图：[COINSTRATEGY_STRATEGY_IMPLEMENTATION_ROADMAP_V1](architecture-refactor/COINSTRATEGY_STRATEGY_IMPLEMENTATION_ROADMAP_V1.md) — 策略体系实施顺序、验收标准、数据账本、回测、套利和页面落地计划。
待办：[COINSTRATEGY_STRATEGY_BACKLOG_V1](architecture-refactor/COINSTRATEGY_STRATEGY_BACKLOG_V1.md) — 所有未完成策略任务的统一入口，含最终执行可观测性、回测、限价探路、退出、驾驶舱与套利依赖。

策略模板：[COINSTRATEGY_STRATEGY_CLOSED_LOOP_TEMPLATE_V1](architecture-refactor/COINSTRATEGY_STRATEGY_CLOSED_LOOP_TEMPLATE_V1.md) — 定义任何策略上线前必须补齐的目标、市场、因子、信号、gate、sizing、执行、持仓、退出、账本、回测和实盘反馈闭环。

资金账本：[COINSTRATEGY_CAPITAL_RISK_LEDGER_V1](architecture-refactor/COINSTRATEGY_CAPITAL_RISK_LEDGER_V1.md) — 账户、资金池、策略维度、统一账本、净 PnL、手续费和风控口径。

返回 [知识库首页](知识库首页.md)

## 一、资金费率套利 (Funding Rate Arbitrage)

通过永续合约资金费率赚取收益，是项目优先实现的对冲类策略。

### 总览与设计

- [FUNDING_RATE_ARBITRAGE](../funding-rate/FUNDING_RATE_ARBITRAGE.md) — 资金费率套利总览
- [funding-rate-arbitrage-design](../funding-rate/funding-rate-arbitrage-design.md) — 套利策略设计文档

### 单交易所方案

- [FUNDING_RATE_ARBITRAGE_SINGLE_EXCHANGE](../funding-rate/FUNDING_RATE_ARBITRAGE_SINGLE_EXCHANGE.md) — 单交易所套利
- [SINGLE_EXCHANGE_..._EXECUTION_CHAIN](../funding-rate/SINGLE_EXCHANGE_FUNDING_RATE_ARBITRAGE_EXECUTION_CHAIN.md) — 单交易所执行链
- [SINGLE_EXCHANGE_..._FLOW](../funding-rate/SINGLE_EXCHANGE_FUNDING_RATE_ARBITRAGE_FLOW.md) — 单交易所流程
- [FUNDING_RATE_ARBITRAGE_SINGLE_EXCHANGE_SHARE](../funding-rate/FUNDING_RATE_ARBITRAGE_SINGLE_EXCHANGE_SHARE.md) — 单交易所方案（分享版）

### 多交易所方案

- [FUNDING_RATE_ARBITRAGE_MULTI_EXCHANGE](../funding-rate/FUNDING_RATE_ARBITRAGE_MULTI_EXCHANGE.md) — 多交易所套利
- [FUNDING_RATE_ARBITRAGE_MULTI_EXCHANGE_SHARE](../funding-rate/FUNDING_RATE_ARBITRAGE_MULTI_EXCHANGE_SHARE.md) — 多交易所方案（分享版）

## 二、链上借贷 + 稳定币 LP

- [CEX_LOAN_ONCHAIN_STABLE_LP_STRATEGY](../funding-rate/CEX_LOAN_ONCHAIN_STABLE_LP_STRATEGY.md) — CEX 借贷叠加链上稳定币 LP 策略

## 三、CEX 趋势与技术分析策略

- [HTX_PYTHON_STRATEGY_CODE_GUIDE_V1](architecture-refactor/HTX_PYTHON_STRATEGY_CODE_GUIDE_V1.md) — 当前 Python 双周期趋势策略源码指南：启动、K 线冻结、指标、候选、Gate、仓位、订单、持仓退出、证据与研究全链路
- [HTX_TREND_STRATEGY_SYSTEM_V1](architecture-refactor/HTX_TREND_STRATEGY_SYSTEM_V1.md) — HTX 合约趋势策略系统 v1：市场分型、多模型信号、因子评分、gate、sizing、退出、回测与实盘观察骨架
- [HTX_TREND_EXECUTION_REFACTOR_PLAN_V1](architecture-refactor/HTX_TREND_EXECUTION_REFACTOR_PLAN_V1.md) — HTX 趋势开仓与持仓管理重构计划：执行链路前置、退出引擎、冷却解冻、多周期和外部仓位处理分批计划
- [HTX_TREND_EXIT_SYSTEM_REFACTOR_PLAN_V1](architecture-refactor/HTX_TREND_EXIT_SYSTEM_REFACTOR_PLAN_V1.md) — HTX 趋势退出系统重构计划：止损、止盈、分批减仓、利润保护、趋势衰竭、时间退出、模型失效、MFE/MAE 与回测指标
- [HTX_TREND_MFE_PROFIT_PROTECTION_PLAN_V1](architecture-refactor/HTX_TREND_MFE_PROFIT_PROTECTION_PLAN_V1.md) — HTX 趋势 MFE 利润保护与可执行退出计划：峰值浮盈保护价、动态减仓、条件单/高频触发、账本证据与 LTC/ZEC/XRP/DOGE/SUI 回放验收
- [TOP50_STRATEGY](../TOP50_STRATEGY.md) — TOP50 趋势与波动率策略
- [CONVENTIONAL_TECHNICAL_STRATEGY](../CONVENTIONAL_TECHNICAL_STRATEGY.md) — 常规技术分析策略
- [RATE_MOMENTUM_STRATEGY](../RATE_MOMENTUM_STRATEGY.md) — 利率动量策略

## 研究新策略？

调研一个新策略并把它落地到项目时，使用 `learning-strategy` 技能：调研原理与风险 → 映射到分层架构 → 拆解开发任务。
