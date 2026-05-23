---
title: Polymarket 知识地图
type: index
status: active
tags: [polymarket]
updated: 2026-05-23
---

# Polymarket 知识地图 (MOC)

Polymarket 预测市场策略是本项目当前最活跃的方向，文档分散在 `polymarket/`、`docs/polymarket/` 等处。本地图按主题汇总全部 52 篇文档。

返回 [知识库首页](知识库首页.md) ｜ 相关：[交易策略 MOC](MOC-交易策略.md)、[架构与平台 MOC](MOC-架构与平台.md)

## 一、总览与背景研究

- [README-polymarket](../polymarket/README-polymarket.md) — Polymarket 模块总览入口
- [polymarket 内幕分析](../polymarket/polymarket内幕分析.md) — 市场机制与玩法的深度研究
- [Polymarket 策略深度分析报告](../Polymarket策略深度分析报告.md) — 策略可行性深度分析

## 二、架构与系统设计

- [MVP_ARCHITECTURE](../polymarket/MVP_ARCHITECTURE.md) — 最小可行架构
- [POLYMARKET_ARCHITECTURE_ANALYSIS](../polymarket/POLYMARKET_ARCHITECTURE_ANALYSIS.md) — 架构分析
- [README-polymarket-architecture-comparison](../polymarket/README-polymarket-architecture-comparison.md) — 架构方案对比
- [polymarket_arbitrage_system_design](../polymarket/polymarket_arbitrage_system_design.md) — 套利系统设计
- [polymarket_quant_system](../polymarket/polymarket_quant_system.md) — 量化系统设计
- [multi-strategy-architecture-design](polymarket/multi-strategy-architecture-design.md) — 多策略架构设计
- [cross-strategy-reusable-infrastructure](polymarket/cross-strategy-reusable-infrastructure.md) — 跨策略可复用基础设施

## 三、策略设计

- [follow-trade-entry-exit-strategy](polymarket/follow-trade-entry-exit-strategy.md) — 跟单进出场策略
- [capital-recycle-exit-design](polymarket/capital-recycle-exit-design.md) — 资金回收与退出设计
- [smart-wallet-follow-strategy-analysis](polymarket/smart-wallet-follow-strategy-analysis.md) — 智能钱包跟随策略分析
- [follow-trade-optimization-design](superpowers/specs/2026-04-22-follow-trade-optimization-design.md) — 跟单优化设计
- [polymarket-weather-arbitrage-design](superpowers/specs/2026-04-22-polymarket-weather-arbitrage-design.md) — 天气套利策略设计
- [weather-strategy-v2-design](weather-strategy-v2-design.md) — 天气策略 v2 设计
- [策略错误分析](../polymarket/策略错误分析.md) — 策略错误案例分析
- [跟单开单检查流程分析](../polymarket/跟单开单检查流程分析.md) — 跟单开单流程检查
- [每日自动白名单-2026-05-19](../polymarket/每日自动白名单-2026-05-19.md) — 每日自动白名单机制

## 四、计划与规格

- [PLAN](../polymarket/PLAN.md) — 总体计划
- [POLYMARKET_SPEC_PLAN](../polymarket/POLYMARKET_SPEC_PLAN.md) — 规格计划
- [market_concentration_plan](../polymarket/market_concentration_plan.md) — 市场集中度计划
- [minimal-implementations-fix-plan](polymarket/minimal-implementations-fix-plan.md) — 最小实现修复计划
- [POLYMARKET_INTELLIGENCE_PROGRESS_SUMMARY](../polymarket/POLYMARKET_INTELLIGENCE_PROGRESS_SUMMARY.md) — 情报能力进度总结

## 五、风控、安全与审计

- [ORDER_SECURITY_FIX_PLAN](../polymarket/ORDER_SECURITY_FIX_PLAN.md) — 订单安全修复方案
- [README-polymarket-risk-upgrade](../polymarket/README-polymarket-risk-upgrade.md) — 风控升级
- [README-polymarket-main-loop-gap-audit](../polymarket/README-polymarket-main-loop-gap-audit.md) — 主循环缺口审计
- [README-polymarket-private-key-workflow](../polymarket/README-polymarket-private-key-workflow.md) — 私钥工作流（安全关键）
- [audit-observability-index](polymarket/audit-observability-index.md) — 审计与可观测性总索引
  - [w1-data-closure](polymarket/audit-observability/w1-data-closure.md) — W1 数据闭环
  - [w2-risk-gates](polymarket/audit-observability/w2-risk-gates.md) — W2 风控闸门
  - [w2-t2-grayscale-sop](polymarket/audit-observability/w2-t2-grayscale-sop.md) — W2 灰度发布 SOP
  - [tier-generalization](polymarket/audit-observability/tier-generalization.md) — 分层泛化
  - [audit-alert-onboarding](polymarket/audit-observability/audit-alert-onboarding.md) — 审计告警接入
  - [web-bff-interfaces](polymarket/audit-observability/web-bff-interfaces.md) — Web BFF 接口
  - [web-frontend-integration](polymarket/audit-observability/web-frontend-integration.md) — Web 前端集成
  - [maintenance](polymarket/audit-observability/maintenance.md) — 维护说明

## 六、部署与运行

- [deployment-runbook-2026-05-17](polymarket/deployment-runbook-2026-05-17.md) — 部署运行手册
- [prod-deployment-checklist-w2-t6](polymarket/prod-deployment-checklist-w2-t6.md) — 生产部署检查清单
- [部署完成报告-2026-05-17](../polymarket/部署完成报告-2026-05-17.md) — 部署完成报告

## 七、排查报告与复盘

- [排查报告-2026-05-17](../polymarket/排查报告-2026-05-17.md)
- [排查报告-2026-05-17-v2](../polymarket/排查报告-2026-05-17-v2.md)
- [操作结果-2026-05-17](../polymarket/操作结果-2026-05-17.md)
- [copy-trading-improvement-decision-2026-05-17](polymarket/copy-trading-improvement-decision-2026-05-17.md) — 跟单改进决策记录
- [为什么还没开单-2026-05-18](../polymarket/为什么还没开单-2026-05-18.md)
- [三个追问答案-2026-05-18](../polymarket/三个追问答案-2026-05-18.md)
- [三个追问代码修复方案-2026-05-18](../polymarket/三个追问代码修复方案-2026-05-18.md)
- [Q3 修复结果与新发现-2026-05-18](<../polymarket/Q3 修复结果与新发现-2026-05-18.md>)
- [market_end_at-修复方案](../polymarket/market_end_at-修复方案.md)
- [LONG_HORIZON双重缩减修复-2026-05-19](../polymarket/LONG_HORIZON双重缩减修复-2026-05-19.md)
- [1小时观察报告-2026-05-19](../polymarket/1小时观察报告-2026-05-19.md)
- [活跃高PnL地址查找结果-2026-05-19](../polymarket/活跃高PnL地址查找结果-2026-05-19.md)
