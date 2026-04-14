# 单交易所资金费率套利实施进度

本文档仅记录“单交易所期现套利”的开发进度、已完成工作、当前不足与后续计划。

## 1. 已完成工作

### 1.1 架构设计

* 在 `coin-strategy-core` 中建立了单交易所套利的分层结构：
    * `monitor`：`SingleExchangeFundingRateMonitor`
    * `decision`：`SingleExchangeSpotFuturesArbitrageStrategy`
    * `execution`：`SingleExchangeArbitrageOrderExecutor`
    * `risk`：`SingleExchangeRiskController`
    * `rebalance`：`SingleExchangeArbitrageRebalanceService`

### 1.2 核心能力实现

* **SingleExchangeFundingRateMonitor**
    * 已实现 Binance 合约资金费率获取。
    * 已支持读取 `markPrice`、`lastFundingRate`、`nextFundingTime` 等字段。

* **SingleExchangeSpotFuturesArbitrageStrategy**
    * 已实现单交易所期现套利主循环。
    * 已从“硬编码数量”升级为“按预算金额和杠杆动态生成下单计划”。
    * 当前默认合约空单杠杆配置为 `3x`，来源于 `arbitrage.spot-futures.leverage`。
    * 当前开仓过滤包括：
        * 当前资金费率高于开仓阈值。
        * 基差率落在允许区间。
        * 不在临近 funding 结算保护窗口内。
        * 预估资金费净收益高于最低收益阈值。
    * 当前平仓触发包括：
        * funding 转非正。
        * funding 低于平仓阈值。
        * 基差相对开仓基差显著恶化。
        * 保证金率跌破紧急阈值。

* **SingleExchangeArbitrageOrderExecutor**
    * 已实现现货买入 + 合约卖出的并发下单。
    * 已实现单侧失败后的自动回滚。
* `SingleExchangeSpotFuturesOrderPlan` 已增加 `basisSpread` / `basisRate` 字段，用于策略层过滤。

* **SingleExchangeRiskController**
    * 已实现开仓前的现货余额检查。
    * 已实现期货可用保证金检查。
    * 已引入 `required-margin-buffer-rate` 作为保证金缓冲。
    * 已提供 `getMarginRatio()` 供策略和再平衡服务复用。

* **SingleExchangeArbitrageRebalanceService**
    * 已具备定时检查保证金率的骨架。
    * 当前仍为模拟再平衡，尚未真实调用资金划转接口。

### 1.3 测试与验证

* 已更新 `SingleExchangeSpotFuturesArbitrageStrategyTest`。
* 已完成单交易所统一命名收口：
    * 服务：`BinanceSingleExchangeFundingRateArbitrageService`
    * 控制器：`BinanceSingleExchangeArbitrageController`
    * 实体：`SingleExchangeFundingRateHistory`、`SingleExchangeArbitragePosition`
    * 表：`single_exchange_funding_rate_history`、`single_exchange_arbitrage_position`
* 已覆盖的关键场景：
    * 正常开仓。
    * 临近结算窗口禁止开仓。
    * 持仓后 funding 转负触发平仓。
* 已通过命令验证：

```bash
mvn -pl coin-strategy-core -DwithTests=true -Dtest=SingleExchangeSpotFuturesArbitrageStrategyTest test
```

## 2. 当前不足

### 2.1 状态持久化缺失

* 当前持仓状态仍保存在内存 `ConcurrentHashMap` 中。
* 服务重启后会丢失持仓状态，存在重复开仓或漏平仓风险。

### 2.2 再平衡仍为模拟

* `SingleExchangeArbitrageRebalanceService` 目前只记录日志，未真实执行资金划转。

### 2.3 配置管理仍待统一

* 虽然参数已迁移到 `application.yml` 的 `arbitrage.spot-futures` 下，
* 但仍通过分散的 `@Value` 注入，尚未收敛为统一的 `ConfigurationProperties`。

### 2.4 深度与滑点风控缺失

* 当前尚未实现 `DepthMonitor`。
* 开仓前还未对盘口深度和预期冲击成本做独立校验。

### 2.5 告警系统缺失

* 回滚失败、风控拦截和 API 异常仍只有日志，没有主动告警。

### 2.6 全仓库测试未分层

* 当前仓库整体 `mvn test` 仍会受到其他模块联网测试影响。
* 核心策略单测可独立通过，但 CI 仍需区分单元测试和外部集成测试。

## 3. 后续计划

### 3.1 近期计划

1. 持仓状态持久化。
2. 将 `@Value` 参数收敛为 `ConfigurationProperties`。
3. 实现真实资金划转。
4. 增加深度检查与滑点保护。
5. 接入钉钉、Slack 或邮件告警。

### 3.2 实盘前必做项

1. 增加持仓恢复逻辑。
2. 增加异常退出和回滚失败告警。
3. 明确单元测试、模拟测试和联网测试的边界。
4. 使用小资金进行长时间运行验证。
