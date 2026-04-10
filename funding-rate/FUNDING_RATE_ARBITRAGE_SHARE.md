# 资金费率套利策略实施进度 (Funding Rate Arbitrage Implementation Status)

本文档用于记录资金费率套利策略的开发进度、已完成工作及后续计划，以便进行逻辑审查和后续执行。

## 1. 已完成工作 (Completed)

### 1.1 架构设计 (Architecture)
*   **模块划分**：在 `coin-strategy-core` 中建立了分层架构：
    *   `monitor`: 市场数据监控 (FundingRateMonitor)。
    *   `decision`: 策略决策核心 (SpotFuturesArbitrageStrategy)。
    *   `execution`: 交易执行 (OrderExecutor)。
    *   `risk`: 风控管理 (RiskController)。

### 1.2 核心功能实现 (Core Implementation)
*   **FundingRateMonitor**:
    *   实现了获取 Binance 合约资金费率的功能。
    *   更新了 `common-client` 添加 `getFuturesFundingRates` 接口。
*   **SpotFuturesArbitrageStrategy**:
    *   实现了单交易所期现套利逻辑。
    *   **开仓条件**：资金费率 > 0.05% (Open Threshold)。
    *   **平仓条件**：资金费率 < 0.01% (Close Threshold)。
    *   集成定时任务 (每分钟轮询)。
*   **OrderExecutor**:
    *   实现了**并发下单** (CompletableFuture)，同时发送现货买入和合约卖出指令，降低单腿风险。
    *   **[NEW] Legging Risk 处理**: 实现了自动回滚机制。若 Spot 成交 Futures 失败，自动卖出 Spot；反之亦然。
    *   **[NEW] Execution Result**: 引入 `ArbitrageExecutionResult` 明确返回执行状态和订单ID。
*   **RiskController**:
    *   实现了基础的交易前风控 (`checkTradeRisk`)。
    *   **[NEW] 增强检查**: 增加了 Spot 余额检查 (USDT) 和 Futures 账户健康度检查。
    *   **[NEW] 保证金率计算**: 初步集成了 Margin Ratio 计算逻辑 (需进一步对接 API)。
*   **RebalanceService [NEW]**:
    *   创建了 `RebalanceService` 骨架。
    *   实现了定时任务 (每5分钟) 检查账户健康度。
    *   定义了再平衡触发阈值 (150%) 和目标阈值 (300%)。

### 1.3 测试与文档 (Tests & Docs)
*   **单元测试**: 完成了 `SpotFuturesArbitrageStrategyTest`，覆盖了开仓逻辑、风控拦截逻辑及Mock更新。
*   **文档**: 更新了 `FUNDING_RATE_ARBITRAGE.md`，补充了详细执行逻辑和当前代码实现说明。

---

## 2. 方案分析与不足 (Analysis & Deficiencies)

虽然核心逻辑已实现，但距离实盘稳定运行仍存在以下不足：

### 2.1 状态持久化缺失 (State Persistence)
*   **问题**: 当前策略持仓状态 (`activePositions`) 存储在内存 `ConcurrentHashMap` 中。
*   **风险**: 如果服务重启或崩溃，策略将丢失当前是否持仓的状态，导致逻辑混乱 (如重复开仓或无法平仓)。
*   **影响**: 严重。实盘必须解决。

### 2.2 再平衡逻辑仅为模拟 (Rebalance Simulation)
*   **问题**: `RebalanceService` 目前仅打印日志，未实际调用 `universalTransfer` 接口。
*   **原因**: 需要确认 `BinanceWalletService` 或 SDK 是否支持划转接口，并进行严格测试。
*   **影响**: 中等。初期资金充足时可手动划转，但长期运行需要自动化。

### 2.3 配置硬编码 (Hardcoded Configuration)
*   **问题**: 交易对 (`BTCUSDT`)、阈值 (`0.0005`)、下单数量 (`0.001`) 等均硬编码在 Java 文件中。
*   **影响**: 灵活性差，调整参数需重新编译部署。

### 2.4 告警系统缺失 (Missing Alerting)
*   **问题**: 当发生 Legging Risk 回滚失败或 API 异常时，仅记录日志，无主动通知。
*   **风险**: 资金风险发生时人工无法及时介入。

### 2.5 深度检查缺失 (No Depth Check)
*   **问题**: 下单前未检查盘口深度。
*   **风险**: 大额下单可能造成巨大滑点，吞噬利润。

---

## 3. 完善与修复计划 (Improvement Plan)

### 3.1 阶段一：稳健性增强 (本周计划)
1.  **持久化存储**:
    *   引入 H2 Database 或 Redis。
    *   创建 `PositionRepository`，将开平仓状态写入数据库。
    *   策略启动时从数据库加载状态。
2.  **配置外部化**:
    *   使用 Spring `@ConfigurationProperties` 或 `application.yml` 管理策略参数。
3.  **资金划转实现**:
    *   调研 Binance Universal Transfer API。
    *   在 `RebalanceService` 中实现真实的资金划转逻辑。

### 3.2 阶段二：安全网建设 (下周计划)
1.  **告警集成**:
    *   集成钉钉/Slack/邮件告警。
    *   在 `OrderExecutor` 回滚失败、`RiskController` 拦截时触发告警。
2.  **流动性风控**:
    *   实现 `DepthMonitor`，下单前检查买一/卖一量是否满足 `TRADE_QUANTITY`。

### 3.3 阶段三：实盘验证
1.  **Testnet 运行**: 连接 Binance Testnet 进行 24小时无人值守测试。
2.  **小资金实盘**: 使用 100 USDT 进行实盘验证，观察滑点和费率损耗。
