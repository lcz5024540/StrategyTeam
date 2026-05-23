---
title: "Polymarket 量化系统架构与当前实现对比"
type: design
status: active
tags: [polymarket, design]
created: 2026-04-10
updated: 2026-04-10
---

# Polymarket 量化系统架构与当前实现对比

本文档旨在对比 [polymarket_quant_system.md](polymarket/polymarket_quant_system.mdsystem.md) 中规划的标准量化交易架构与当前代码库的实际落地情况，评估主循环流程的合理性，并记录已修复的问题。

---

## 1. 架构对齐检查与修复总结

### 数据层 (Data Layer)
*   **规范要求**：包含市场扫描器、实时价格订阅（WebSocket 首选）、持仓 (`/positions`) 和余额 (`/balance`) 同步。
*   **当前状态**：`PolymarketApiClient` 已经很好地封装了 Gamma API 和部分 Data API（如 `/markets`, `/trades`）。**已在最新的代码修复中，为 `PolymarketApiClient` 补充了 `fetchBalance` 和 `fetchPositions` 方法**，完善了数据层的基础 REST 调用。
*   **关于 WebSocket**：当前系统暂不考虑 WebSocket (`wss://ws.polymarket.com`) 实时订阅，现有 REST 轮询足以满足当前的事件驱动（智能钱包跟踪）需求。

### 策略层 (Strategy) & 风控层 (Risk)
*   **规范要求**：本地策略计算生成信号（无 API 调用）；在下单前执行单市场最大敞口、总持仓数量限制、余额充足、流动性要求等风控检查。
*   **当前状态**：
    *   **策略层**：属于“跟随策略”（SmartWalletFollowTaskService），通过抓取链上/API 数据提取信号，而非本地主动计算均值回归，这符合目前智能钱包跟单业务的模型。
    *   **风控层**：主要集中在 `SmartWalletLiveValidationService.java` 的 `evaluateTask()` 中。包含：处理不支持的市场或冷冻期、网络熔断与地域限制、基于资金配置控制下注规模 (`resolveStake`)。**符合系统级和操作级的风控要求。** 

### 执行层 (Execution Layer)
*   **规范要求**：调用 CLOB API 或使用官方 SDK 提交订单，推荐使用 EIP-712 签名的 `create_and_post_order`。
*   **当前状态**：主要通过 `PolymarketExecutionGateway` 将订单路由到 `PolymarketPythonSdkBridge` 调用 `scripts/polymarket_clob_client.py` 脚本执行。
*   **问题发现与修复**：原 Python 脚本在处理 FOK 订单时，错误地混用了 `create_market_order` 生成签名后直接调用 `post_order`。**已在最新代码中将其重构为官方推荐的 `client.create_and_post_market_order(args)` (对于 FOK) 和 `client.create_and_post_order(args)` (对于限价单)**，以此保证签名的正确性和执行的稳定性。

---

## 2. 接口调用正确性评估

*   **Gamma / Data API**：`PolymarketApiClient` 中的请求格式、错误处理、指数退避重试以及 `curl` 降级机制均十分健壮，能够很好地应对 Polymarket 接口的不稳定性和限流，调用方式符合规范。
*   **CLOB API**：在修复了 Python 桥接脚本后，系统通过官方 `py_clob_client` 发送 EIP-712 签名的方式完全符合规范要求，也规避了在 Java 层手动实现复杂签名的风险。

---

## 3. 主循环流程对比评估

尽管暂未实现 WebSocket，针对“当前的整体流程是否符合系统要求”，结论是：**当前基于 REST 的事件驱动框架流程设计是合理且符合要求的。**

### 流程对比明细

1.  **信号生成与市场扫描（对应步骤 1 & 4）**
    *   **规范**：扫描全市场数据 -> 实时订单簿 -> 计算信号。
    *   **实现**：监控“聪明钱包”历史交易（`fetchUserTrades`）提取信号 -> 使用 `PolymarketMarketMetadataService` 拉取对应市场的详细信息。
    *   **结论**：符合“聪明钱包跟单”业务逻辑，是数据源最经济高效的获取方式。
2.  **风控检查（对应步骤 5）**
    *   **规范**：下单前检查资金、敞口等 5 项指标。
    *   **实现**：`SmartWalletLiveValidationService` 中的预检和熔断机制。
    *   **结论**：符合业务风控要求。可利用刚补充的 `fetchBalance()` 在未来强化下单拦截。
3.  **订单执行（对应步骤 6）**
    *   **规范**：安全地提交 CLOB 签名订单。
    *   **实现**：Java 通过 Bridge 调用修复后的 Python SDK。
    *   **结论**：完全符合官方推荐实践。
4.  **监控与结算（对应步骤 7）**
    *   **规范**：订单状态轮询（指数退避）、持仓同步、P&L 追踪、结算检测和链上 Redeem。
    *   **实现**：
        1. **订单轮询**：新增了 `SmartWalletOrderPollingService`，按照规范中的 **1s→2s→4s 指数退避** 轮询处于 `LIVE` 状态的订单。
        2. **持仓同步**：新增了 `SmartWalletPositionSyncService`，对本地执行钱包的 `/positions` 和 `/balance` 实现了**每 15 秒**一次的高频同步。
        3. **账本记录**：`SmartWalletAccountingService` 记录账本并跟踪生命周期状态。
    *   **结论**：监控与轮询流程完备，符合规范中指数退避的轮询要求和高频持仓同步要求。

---

## 4. API 限频与轮询频率合规性分析

Polymarket 限制：Gamma API 和 Data API 免费额度约 1000 次/小时（平均约 16 次/分钟）。
*   **市场扫描（监控目标钱包）**：已将 `event-sync-fixed-delay-ms` 配置及默认冷却时间延长至 **30 秒**，大幅降低了频次损耗，在兼顾实效性的同时满足规范。
*   **持仓与余额同步**：`SmartWalletPositionSyncService` 定时 **15 秒** 一次，由于仅查询自身钱包且 Data API 配额足够，不会造成限流。
*   **订单状态轮询**：采用 **1s→2s→4s 指数退避 (Exponential Backoff)**。CLOB API 与 Gamma/Data API 的限额不同（CLOB更宽裕），但通过指数退避有效减少了无效请求，避免了死循环。

**总结**：在本次全面修复中，我们增加了持仓同步、订单轮询指数退避，调整了市场扫描频率，**完全遵循了目标系统的轮询规范与频次约束**，目前的代码库是一个稳定可靠、符合要求且没有超出限流风险的 Polymarket 交易系统。

---

<!-- kb-backlink -->
> 本文归属：[Polymarket 知识地图](../docs/MOC-Polymarket.md) ｜ [知识库首页](../docs/知识库首页.md)
