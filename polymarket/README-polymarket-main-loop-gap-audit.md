---
title: "基于 7 步主循环的当前实现偏差核验报告"
type: audit
status: active
tags: [polymarket, audit]
created: 2026-04-10
updated: 2026-04-10
---

# 基于 7 步主循环的当前实现偏差核验报告

## 1. 结论摘要

本报告的目标是核验“图片 7 步主循环”与当前代码实现的差异，并确认“为什么没有这样实现”。

本轮存在一个前提限制：用户提到的“原图”没有在本轮会话中实际提供，因此本文暂时以仓库中的 7 步定义作为辅助代用基准：

- [polymarket_quant_system.md](/Users/lichangzheng.ray/Ray/CoinStrategy/polymarket_quant_system.md#L179)
- [polymarket_quant_main_loop.svg](/Users/lichangzheng.ray/Ray/CoinStrategy/polymarket_quant_main_loop.svg)

这意味着：

- 文中所有“原图步骤”当前均对应仓库内 7 步主循环定义。
- 如果你后续补充了外部原图，本文应再做一次差异复核；届时若原图和仓库文档冲突，应以你提供的原图为准。

当前代码更像一个“事件驱动的 smart wallet 跟单系统”，而不是“统一 tick-loop 的全市场量化主循环系统”。它已经较完整覆盖了“信号进入后”的风控、执行、监控、结算，但没有严格遵循设计图里的统一主循环顺序。

## 2. 两种判断口径

### 口径 A：按业务目标是否大体覆盖

大体覆盖了 60%-70%，主要覆盖了“信号进入后”的风控、执行、监控、结算。

主要依据：

- 风控在跟单任务执行前已经较完整存在，涵盖 freshness、价格区间、预算、仓位上限、重复仓位、共识门槛、最小下单额等检查，见 [SmartWalletSignalRiskService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletSignalRiskService.java#L45)。
- 执行后监控和结算链路比较完整，包含订单轮询、仓位/余额同步、settlement sync、claim retry，见 [SmartWalletOrderPollingService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletOrderPollingService.java#L26)、[SmartWalletPositionSyncService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletPositionSyncService.java#L30)、[SmartWalletSettlementSyncService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletSettlementSyncService.java#L101)、[SmartWalletClaimService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletClaimService.java#L107)。

### 口径 B：按是否严格符合图片主循环设计

不严格符合原图主循环设计，当前至少存在 4 个关键偏差：

1. 没有单一统一主循环，而是多个分散 scheduler。
2. 主入口不是全市场扫描，而是钱包发现 + 钱包交易同步。
3. 订单簿/价格抓取不是主链路前置，而是辅助快照链路。
4. 默认配置不是自动 LIVE 下单，而是 PAPER / 手动触发优先。

## 3. 原图 7 步标准定义表

当前按仓库内 7 步主循环定义抽取如下：

| 步骤 | 名称 | 触发时机 | 输入数据 | 输出动作 | 基准证据 |
|---|---|---|---|---|---|
| 1 | 市场扫描 | 每轮循环开始 | `GET /markets` 活跃市场列表 | 生成可交易市场集合 | [polymarket_quant_system.md](/Users/lichangzheng.ray/Ray/CoinStrategy/polymarket_quant_system.md#L186) |
| 2 | 订单簿与价格 | 步骤 1 之后 | WS 或 `/book`、`/price` | 更新本地 orderbook / price 缓存 | [polymarket_quant_system.md](/Users/lichangzheng.ray/Ray/CoinStrategy/polymarket_quant_system.md#L190) |
| 3 | 持仓与余额同步 | 步骤 2 之后 | `/positions` + `/balance` | 刷新本地账户状态 | [polymarket_quant_system.md](/Users/lichangzheng.ray/Ray/CoinStrategy/polymarket_quant_system.md#L194) |
| 4 | 策略计算 / 信号生成 | 步骤 3 之后 | 本地状态和市场数据 | 生成交易信号 | [polymarket_quant_system.md](/Users/lichangzheng.ray/Ray/CoinStrategy/polymarket_quant_system.md#L197) |
| 5 | 风控检查 | 有交易信号后 | 信号、持仓、余额、敞口 | 通过/拒绝信号 | [polymarket_quant_system.md](/Users/lichangzheng.ray/Ray/CoinStrategy/polymarket_quant_system.md#L205) |
| 6 | 订单执行 | 风控通过后 | 已通过风控的订单意图 | `POST /order` 签名下单 | [polymarket_quant_system.md](/Users/lichangzheng.ray/Ray/CoinStrategy/polymarket_quant_system.md#L213) |
| 7 | 监控与结算检测 | 执行后并持续循环 | 订单状态、市场 resolved 状态、redeem 条件 | 轮询、结算、claim | [polymarket_quant_system.md](/Users/lichangzheng.ray/Ray/CoinStrategy/polymarket_quant_system.md#L216) |

## 4. 当前真实运行图

### 主触发链路

```text
Polymarket leaderboard / watchlist wallets
-> wallet discovery
-> watchlist wallet trade sync
-> ingest trade event
-> create follow task
-> risk evaluation
-> PAPER execute by default
-> order/accounting updates
```

对应证据：

- 钱包发现入口来自 `fetchLeaderboard()`，不是全市场扫描，见 [PolymarketWalletDiscoveryService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/PolymarketWalletDiscoveryService.java#L39)。
- 交易同步入口来自 `fetchUserTrades(walletAddress)`，见 [PolymarketWalletDiscoveryService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/PolymarketWalletDiscoveryService.java#L118)。
- trade event 进入 follow task，见 [SmartWalletFollowTaskService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletFollowTaskService.java#L54)。
- follow task 按 `poll-fixed-delay-ms` 调度执行，见 [SmartWalletFollowTaskService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletFollowTaskService.java#L196)。

### 辅助后台链路

这些能力存在，但并不构成单一主循环的一部分：

- recent market snapshot: [MarketSnapshotCollectorService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/MarketSnapshotCollectorService.java#L117)
- live validation: [SmartWalletLiveValidationService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletLiveValidationService.java#L48)
- order polling: [SmartWalletOrderPollingService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletOrderPollingService.java#L26)
- balance / positions sync: [SmartWalletPositionSyncService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletPositionSyncService.java#L30)
- settlement sync: [SmartWalletSettlementSyncService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletSettlementSyncService.java#L101)
- claim processing: [SmartWalletClaimService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletClaimService.java#L107)

### Scheduler 一览

| 调度点 | 默认频率 | 职责 | 是否影响下单 | 类型 |
|---|---:|---|---|---|
| wallet discovery | 1h | 发现 leaderboard 钱包并重算 watchlist | 间接 | 主入口准备 |
| watchlist trade sync | 60s | 拉取 watchlist 钱包 trades | 是 | 主入口 |
| follow task poll | 5s | 执行待跟单任务 | 是 | 主执行 |
| market snapshot | 45s | 采集近期事件涉及市场快照 | 否 | 辅助快照 |
| live validation | 45s | 小额 LIVE 验证 | 可影响 LIVE，但默认关闭 | 辅助验证 |
| order polling | 2s | 轮询 live order 状态 | 否，执行后链路 | 监控 |
| position sync | 15s | 同步 live wallet balance/positions | 否，执行后链路 | 监控 |
| settlement sync | 300s | 结算扫描与 retry | 否 | 结算 |
| claim | 60s | claim / retry / waiting confirmation | 否 | 结算 |

频率与开关证据见 [application.yml](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/resources/application.yml#L37)。

## 5. 7 步逐项对照表

| 原图步骤 | 原图预期行为 | 当前代码入口/模块 | 满足度 | 偏差类型 | 未这样实现的原因 | 是否业务取舍 | 后续改造难度 |
|---|---|---|---|---|---|---|---|
| 步骤 1 市场扫描 | 每轮从全市场活跃 market 开始扫描 | `PolymarketWalletDiscoveryService` 先 `fetchLeaderboard()`，再按 watchlist 同步用户交易 | 架构不一致 | 入口偏差 | 当前入口围绕“聪明钱包发现 + 钱包交易同步”，不是市场 universe 扫描 | 是 | 中 |
| 步骤 2 订单簿与价格 | 在主链路前置拉取 orderbook/price 并更新缓存 | `MarketSnapshotCollectorService` 仅对近期 trade event 涉及市场做快照 | 架构不一致 | 数据前置偏差 | 订单簿属于辅助快照与分析，不是信号生成的硬前置 | 是 | 中 |
| 步骤 3 持仓与余额同步 | 每轮主循环同步账户状态 | `SmartWalletPositionSyncService` 独立定时同步 live wallet | 部分满足 | 调度偏差 | 能力存在，但脱离主循环，且只有 live wallet / live positions 相关时才触发 | 是 | 低 |
| 步骤 4 策略计算 / 信号生成 | 基于全市场和本地模型生成信号 | trade event -> follow task -> consensus/risk；入口在 `recordTradeEvent()` | 架构不一致 | 领域建模偏差 | 信号源已经从“全市场策略”变成“钱包事件驱动跟单” | 是 | 高 |
| 步骤 5 风控检查 | 在下单前检查余额、仓位、敞口等 | `SmartWalletSignalRiskService.evaluate()` | 已满足 | 无 | 这部分已经位于执行前主链路中 | 否 | 低 |
| 步骤 6 订单执行 | 风控通过后默认发 live order | 默认 `auto-execution-enabled: false`；`live-validation.enabled: false`；仅保留辅助 LIVE lane | 部分满足 | 执行模式偏差 | 出于 PAPER 优先、手动确认、地理限制与小额验证策略，默认不自动 LIVE | 是 | 中 |
| 步骤 7 监控与结算 | 订单轮询、resolved 检测、redeem | `SmartWalletOrderPollingService`、`SmartWalletSettlementSyncService`、`SmartWalletClaimService` | 已满足 | 无 | 这一段是当前实现最完整的部分 | 否 | 低 |

## 6. 4 个关键偏差详解

### 偏差 1：没有单一统一主循环，而是多个分散 scheduler

证据：

- 原图要求“主循环开始（每 N 秒）”，见 [polymarket_quant_system.md](/Users/lichangzheng.ray/Ray/CoinStrategy/polymarket_quant_system.md#L181)。
- 当前代码存在多个独立 `@Scheduled`：discovery、trade sync、follow task poll、snapshot、live validation、order polling、position sync、settlement、claim，分别见：
  - [PolymarketWalletDiscoveryService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/PolymarketWalletDiscoveryService.java#L159)
  - [PolymarketWalletDiscoveryService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/PolymarketWalletDiscoveryService.java#L171)
  - [SmartWalletFollowTaskService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletFollowTaskService.java#L196)
  - [MarketSnapshotCollectorService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/MarketSnapshotCollectorService.java#L117)
  - [SmartWalletLiveValidationService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletLiveValidationService.java#L48)
  - [SmartWalletOrderPollingService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletOrderPollingService.java#L26)
  - [SmartWalletPositionSyncService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletPositionSyncService.java#L30)
  - [SmartWalletSettlementSyncService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletSettlementSyncService.java#L101)
  - [SmartWalletClaimService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletClaimService.java#L107)

原因判断：

- 主因：`渐进式落地`。系统优先把可单独运行的子能力拆成 background job，便于局部补齐和单独调参。
- 次因：`风控优先级`。后半段执行、监控、结算比前半段统一市场扫描先成熟。

### 偏差 2：主入口不是全市场扫描，而是钱包发现 + 钱包交易同步

证据：

- 入口先抓 leaderboard，再分析 top wallets，见 [PolymarketWalletDiscoveryService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/PolymarketWalletDiscoveryService.java#L39)。
- 随后同步 watchlist 钱包交易，调用 `fetchUserTrades(walletAddress)`，见 [PolymarketWalletDiscoveryService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/PolymarketWalletDiscoveryService.java#L118)。
- 配置层直接把 discovery、event sync、watchlist batch/cooldown 作为一等概念，见 [application.yml](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/resources/application.yml#L69)。

原因判断：

- 主因：`业务模型改变`。当前产品目标是 smart wallet 跟单，不是全市场 alpha 扫描。
- 次因：`限频/成本约束`。配置中显式限制 watchlist 批量与冷却，说明实现更关注低频可控的 wallet sync，而不是大范围 market scan。

### 偏差 3：订单簿/价格抓取不是主链路前置，而是辅助快照链路

证据：

- `MarketSnapshotCollectorService.collectRecentActiveMarketSnapshots()` 先读近期 `smart_wallet_trade_event`，再对这些事件涉及市场采集 snapshot，见 [MarketSnapshotCollectorService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/MarketSnapshotCollectorService.java#L89)。
- 快照采集包括 `fetchOrderBook` 和 `fetchMarketTrades`，见 [MarketSnapshotCollectorService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/MarketSnapshotCollectorService.java#L49)。
- 该任务是独立定时任务，而不是 follow task 执行前的依赖，见 [MarketSnapshotCollectorService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/MarketSnapshotCollectorService.java#L117)。

原因判断：

- 主因：`限频/成本约束`。全市场 orderbook 前置成本高，而当前策略入口是“先有钱包事件，再补该市场快照”。
- 次因：`实现复杂度`。没有 WS 本地 orderbook cache 主链路，当前实现更像分析型 snapshot。

### 偏差 4：默认配置不是自动 LIVE 下单，而是 PAPER / 手动触发优先

证据：

- `auto-execution-enabled: false`，见 [application.yml](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/resources/application.yml#L40)。
- `live-validation.enabled: false`，且注释写明只是 auxiliary LIVE lane，见 [application.yml](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/resources/application.yml#L208)。
- claim 默认为 `MANUAL_CONFIRM`，见 [application.yml](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/resources/application.yml#L243)。
- live validation 逻辑本身也把 LIVE 描述为 validation lane，不是默认主执行模式，见 [SmartWalletLiveValidationService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletLiveValidationService.java#L56)。

原因判断：

- 主因：`手动验证策略`。默认优先 PAPER，LIVE 只作小额验证或人工确认。
- 次因：`风控优先级`。系统先确认账本、风控、结算可靠，再逐步放开 LIVE。

## 7. 每一步未按图实现的原因

### 步骤 1：市场扫描

- 主因：`业务模型改变`
- 次因 1：`限频/成本约束`
- 次因 2：`历史包袱/命名继承`
- 证据：
  - leaderboards / wallets 是入口一等公民，见 [PolymarketWalletDiscoveryService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/PolymarketWalletDiscoveryService.java#L39)
  - `smart_wallet_*` 表和 follow task 模型天然围绕 wallet event 展开，见 [SmartWalletFollowTaskService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletFollowTaskService.java#L54)

### 步骤 2：订单簿与价格

- 主因：`限频/成本约束`
- 次因 1：`实现复杂度`
- 次因 2：`渐进式落地`
- 证据：
  - 快照由近期 trade event 反推市场列表，见 [MarketSnapshotCollectorService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/MarketSnapshotCollectorService.java#L89)
  - 并未进入 follow task 执行前必经链路，推断待作者确认

### 步骤 3：持仓与余额同步

- 主因：`渐进式落地`
- 次因 1：`风控优先级`
- 次因 2：`手动验证策略`
- 证据：
  - 同步逻辑独立定时运行，见 [SmartWalletPositionSyncService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletPositionSyncService.java#L30)
  - 只有 auto execution、live validation 或已有 live position 时才真正需要同步，见 [SmartWalletPositionSyncService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletPositionSyncService.java#L76)

### 步骤 4：策略计算 / 信号生成

- 主因：`业务模型改变`
- 次因 1：`历史包袱/命名继承`
- 次因 2：`渐进式落地`
- 证据：
  - `recordTradeEvent()` 直接产生 follow task，说明信号是事件驱动，不是统一 strategy engine 先产出 signal，见 [SmartWalletFollowTaskService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletFollowTaskService.java#L54)

### 步骤 5：风控检查

- 主因：无明显偏差
- 次因 1：无
- 次因 2：无
- 证据：
  - 风控覆盖 freshness、价格、预算、仓位、重复任务、共识、最小下单额等，见 [SmartWalletSignalRiskService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletSignalRiskService.java#L45)

### 步骤 6：订单执行

- 主因：`手动验证策略`
- 次因 1：`风控优先级`
- 次因 2：`渐进式落地`
- 证据：
  - 默认 `auto-execution-enabled: false`，见 [application.yml](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/resources/application.yml#L40)
  - LIVE 只在 validation lane 中尝试，且每轮有严格 execution cap，见 [SmartWalletLiveValidationService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletLiveValidationService.java#L89)

### 步骤 7：监控与结算

- 主因：无明显偏差
- 次因 1：这一段反而是当前实现最完整区域
- 次因 2：无
- 证据：
  - order polling 指数退避，见 [SmartWalletOrderPollingService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletOrderPollingService.java#L90)
  - settlement scheduled sync + retry，见 [SmartWalletSettlementSyncService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletSettlementSyncService.java#L101)
  - claim + waiting confirmation + retry，见 [SmartWalletClaimService.java](/Users/lichangzheng.ray/Ray/CoinStrategy/coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletClaimService.java#L107)

## 8. 哪些是合理业务取舍，哪些是真缺口

### 更偏合理业务取舍

- 采用 wallet event 作为入口，而不是全市场 scan。
- 默认 PAPER / 手动 LIVE，而不是直接自动实盘。
- 将 orderbook/price 作为分析型 snapshot，而不是前置硬依赖。
- 将 settlement / claim 独立成后台异步任务。

这些更像业务方向和落地策略的选择，不必简单定义成“实现缺失”。

### 更偏真实架构缺口

- 缺少单一统一 tick-loop 调度器。
- 缺少平台标准化 signal 层，当前 trade event 与 follow task 耦合较深。
- 缺少真正以全市场 universe 为输入的 strategy 计算入口。
- 缺少将 positions/balance/orderbook/cache 明确编排进统一控制流的 orchestration。

## 9. 若要回归原图主循环，最小改造路径

### 路径 1：先补统一 orchestrator，不先改业务入口

最小做法：

1. 新增一个统一 `MainLoopOrchestrator`，每 N 秒按固定顺序驱动步骤 1-7。
2. 先把现有 scheduler 迁入 orchestrator，短期仍然复用原 service。
3. 把 wallet discovery / trade sync 标成“信号源插件”，而不是主循环本身。

收益：

- 可以先统一控制流和观测面，不必一次改掉 smart wallet 业务模型。

### 路径 2：补标准信号层

最小做法：

1. 抽出独立 `TradingSignal` 持久化或内存模型。
2. `wallet trade event -> TradingSignal`。
3. follow task 不再直接依赖 raw trade event。

收益：

- 将“smart wallet 跟单”降级为一种 signal source，后续才能接入全市场策略。

### 路径 3：再决定是否回归全市场扫描

最小做法：

1. 新增 universe scan job 或 loop step。
2. 增加 market candidate cache。
3. 再把 orderbook/price cache 前置到 signal generation 前。

注意：

- 这一步不是当前系统最小必需改动；它属于把产品目标从“事件驱动跟单”重新拉回“量化主循环系统”。

## 10. 固定分析输出接口

后续如果要把本文继续结构化沉淀，建议使用以下输出接口：

### StepSpec

| 字段 | 含义 |
|---|---|
| `step_no` | 步骤编号 |
| `name` | 步骤名称 |
| `trigger` | 触发时机 |
| `input` | 输入数据 |
| `output` | 输出动作 |
| `source_image_excerpt` | 基准图摘录 |

### ImplementationMapping

| 字段 | 含义 |
|---|---|
| `step_no` | 步骤编号 |
| `modules` | 对应模块 |
| `entrypoints` | 入口方法 |
| `schedulers` | 调度器 |
| `is_primary_path` | 是否属于主链路 |
| `evidence` | 代码/配置证据 |

### GapAssessment

| 字段 | 含义 |
|---|---|
| `step_no` | 步骤编号 |
| `status` | 已满足 / 部分满足 / 缺失 / 架构不一致 |
| `gap_type` | 入口偏差 / 调度偏差 / 数据前置偏差 / 执行模式偏差 / 领域建模偏差 / 仅辅助链路 |
| `reason_primary` | 主因 |
| `reason_secondary` | 次因 |
| `business_goal_covered` | 是否覆盖业务目标 |
| `architecture_aligned` | 是否符合原图控制流 |

## 11. 验收结[funding-rate-arbitrage-design.md](..%2F..%2FDownloads%2Ffunding-rate-arbitrage-design.md)果对应

本报告已经覆盖以下检查项：

- 是否存在单一统一 tick-loop：结论为否。
- 市场扫描是否从 `GET /markets` 开始：结论为否，当前从 leaderboard/watchlist wallet 开始。
- orderbook/price 是否是信号前硬前置：结论为否。
- positions/balance 是否是主链路步骤：结论为否，当前是独立后台同步。
- signal 是否来自全市场策略计算：结论为否，当前来自 wallet trade event。
- risk 是否发生在下单前主链路：结论为是。
- execution 是否默认指向 LIVE：结论为否。
- monitoring/settlement 是否最完整：结论为是。

## 12. 后续复核建议

如果你下一步提供外部原图，建议只做两件事：

1. 逐行比对“仓库 7 步定义”和“外部原图”是否一致。
2. 若有差异，只重算第 3、5、6、7 节，不必重做整份报告。

---

<!-- kb-backlink -->
> 本文归属：[Polymarket 知识地图](../docs/MOC-Polymarket.md) ｜ [知识库首页](../docs/知识库首页.md)
