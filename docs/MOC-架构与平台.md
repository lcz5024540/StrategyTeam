---
title: 架构与平台知识地图
type: index
status: active
tags: [design, deploy]
updated: 2026-05-23
---

# 架构与平台知识地图 (MOC)

本地图汇总项目整体架构、产品设计、部署运维与各代码模块说明文档。

返回 [知识库首页](知识库首页.md)

## 一、产品与总体设计

- [项目 README](../README.md) — 开发内容定义、系统分层架构、前端启停说明
- [PRODUCT_DESIGN](../PRODUCT_DESIGN.md) — 产品设计文档
- [quant-trading-architecture](../quant-trading-architecture.md) — 量化交易平台架构
- [polymarket-现行架构](../polymarket/polymarket-现行架构.md) — Polymarket 跟单系统现行架构权威入口
- [java_architecture_design.svg](../java_architecture_design.svg) — Java 架构设计图

## 二、架构重构与部署拓扑

- [趋势退出系统 v5 技术设计](../architecture/CoinStrategy-Trend-Exit-Design-v5.md) — 完整持仓合同、双时钟、风险/利润分离、执行/账本及再入场；设计交付，尚未实现
- [趋势退出 v5 实验与验收](../architecture/Trend-Exit-v5-Validation.md) — 36 项规则/故障规格、分层回放、独立样本与晋级门槛
- [退出全面重设计 ADR](design-docs/adr-20261009-redesign-trend-exits.md) — 用户确认 B 方向与影响范围
- [2026-10-09 短持仓证据](architecture-refactor/evidence/HTX_TREND_SHORT_HOLDING_20261009.md) — 主账户 LTC/DOGE/BTC 的真实决策与成交确认口径
- [技术设计索引](../architecture/index.md) · [决策索引](design-docs/index.md)
- [NETWORK_HARDENING](architecture-refactor/NETWORK_HARDENING.md) — 网络加固方案
- [deploy-topology-refactor](deploy-topology-refactor.md) — 部署拓扑重构
- [README-smart-wallet-service](../deploy/linux/README-smart-wallet-service.md) — 智能钱包服务部署说明

## 三、代码模块说明

| 模块 | 文档 | 职责 |
| --- | --- | --- |
| coin-strategy-common | [README](../coin-strategy-common/README.md) | 通用能力 |
| common-client | [README](../coin-strategy-common/common-client/README.md) | 交易所 SDK 适配总览 |
| common-client / Binance | [BINANCE-README](../coin-strategy-common/common-client/BINANCE-README.md) | 币安对接 |
| common-client / OKX | [OKX-README](../coin-strategy-common/common-client/OKX-README.md) | OKX 对接 |
| common-client / Gate | [GATE-README](../coin-strategy-common/common-client/GATE-README.md) | Gate 对接 |

> 模块分层职责（core / job / web / common-api / common-client）详见项目 README 与 `coding-standards` 技能中的分层纪律表。

## 相关

- 数据库表结构变更规范见 `db-migration` 技能；SQL 脚本在 `sql/` 目录。
- 安全与风控相关文档散见各策略 MOC，安全审查方法见 `trading-security-audit` 技能。
