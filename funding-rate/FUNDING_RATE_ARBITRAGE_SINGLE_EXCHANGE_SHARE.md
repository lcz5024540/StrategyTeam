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
    * 已接入 `SingleExchangeArbitragePositionMapper`，开仓/平仓状态会写入 `single_exchange_arbitrage_position`。
    * 策略启动时会自动恢复数据库中未关闭的持仓，避免服务重启后重复开仓。
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
    * 已接入 Binance Universal Transfer，可在保证金率过低时执行“现货账户 -> U 本位合约账户”的真实资金划转。
    * 已增加 API 权限校验、现货可用余额校验以及基于目标保证金率的划转金额计算。

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

* 已解决。
* 当前持仓会在开仓前写入 `OPENING`，开仓成功后更新为 `OPENED`，平仓过程更新为 `CLOSING/CLOSED`，失败时更新为 `FAILED`。
* 服务启动时会从 `single_exchange_arbitrage_position` 恢复 `OPENING/OPENED/CLOSING` 状态的记录，并重建内存持仓视图。
* 当前仍未记录单交易所订单明细表，因此平仓订单 ID 和更细颗粒度成交恢复仍可继续补强。

### 2.2 再平衡真实划转已补齐

* 已解决。
* `SingleExchangeArbitrageRebalanceService` 现在会在保证金率低于 `rebalance-threshold-margin-ratio` 时，
  调用 Binance Universal Transfer 将 `rebalance-asset` 从现货账户划转到 U 本位合约账户。
* 划转前会检查 API Key 是否具备 `permitsUniversalTransfer` / `enableInternalTransfer` 权限，
  并根据目标保证金率 `rebalance-target-margin-ratio`、当前合约保证金余额、维持保证金和现货可用余额计算实际划转金额。
* 当前仍未把再平衡动作写入独立流水表，如需完整审计，后续可补充资金划转日志落库。

### 2.3 配置管理仍待统一

* 已解决。
* 单交易所套利相关参数已统一收敛到 `SingleExchangeSpotFuturesProperties`，由单一配置对象集中读取 `arbitrage.spot-futures` 下的全部参数。
* `SingleExchangeSpotFuturesArbitrageStrategy`、`SingleExchangeRiskController`、`SingleExchangeArbitrageRebalanceService` 已不再分散使用 `@Value`，避免配置项扩展时出现重复默认值和参数漂移。
* 当前 `application.yml` 的配置 key 保持不变，因此对现有部署和调参方式无破坏性影响。

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

1. 将再平衡动作与风控快照联动落库。
2. 增加深度检查与滑点保护。
3. 接入钉钉、Slack 或邮件告警。

### 3.2 实盘前必做项

1. 增加异常退出和回滚失败告警。
2. 将订单明细和资金费流水与持仓主表联动落库。
3. 明确单元测试、模拟测试和联网测试的边界。
4. 使用小资金进行长时间运行验证。
