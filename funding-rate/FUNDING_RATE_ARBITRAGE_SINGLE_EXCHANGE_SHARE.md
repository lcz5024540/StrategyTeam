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
    * 已补齐 `PRE_OPEN` / `PRE_CLOSE` 阶段的风控快照运行时落库：
        * 开仓前在流动性评估失败、交易风险拒绝或风险检查通过进入下单前，会写入 `single_exchange_arbitrage_risk_snapshot`。
        * 平仓触发后在执行下单前，会写入 `PRE_CLOSE` 风控快照，记录关仓触发原因以及当时的流动性评估结果。

* **SingleExchangeArbitrageOrderExecutor**
    * 已实现现货买入 + 合约卖出的并发下单。
    * 已实现单侧失败后的自动回滚。
* `SingleExchangeSpotFuturesOrderPlan` 已增加 `basisSpread` / `basisRate` 字段，用于策略层过滤。

* **SingleExchangeRiskController**
    * 已实现开仓前的现货余额检查。
    * 已实现期货可用保证金检查。
    * 已引入 `required-margin-buffer-rate` 作为保证金缓冲。
    * 已提供 `getMarginRatio()` 供策略和再平衡服务复用。
    * 已接入基于 `depth` 的逐档 VWAP 流动性与滑点评估，供开仓和平仓阶段复用。
    * 已补充开仓风险评估明细对象，可同时返回现货余额、合约可用余额、所需保证金、缓冲后保证金和保证金率，用于风控快照落库。

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
    * 开仓/平仓阶段的风控快照运行时落库。
* 已通过命令验证：

```bash
mvn -pl coin-strategy-core -DwithTests=true -Dtest=SingleExchangeSpotFuturesArbitrageStrategyTest test
```

## 2. 当前不足

### 2.1 状态持久化缺失

* 已解决。
* 当前持仓会在开仓前写入 `OPENING`，开仓成功后更新为 `OPENED`，平仓过程更新为 `CLOSING/CLOSED`，失败时更新为 `FAILED`。
* 服务启动时会从 `single_exchange_arbitrage_position` 恢复 `OPENING/OPENED/CLOSING` 状态的记录，并重建内存持仓视图。
* 已接入 `single_exchange_arbitrage_order` 的运行时写入，开仓、平仓以及单侧失败后的回滚订单都会按持仓维度落库。
* 已接入 `single_exchange_arbitrage_funding_settlement` 的运行时写入：当持仓跨过 `entry_next_funding_time` 且检测到新的 `nextFundingTime` 时，会按持仓维度记录资金费结算流水，并同步推进持仓主表中的 `entry_next_funding_time` 与 `funding_fees_earned`。
* 当前持仓主表也会在平仓成功时同步回填 `spot_sell_order_id` 和 `futures_buy_order_id`。

### 2.2 再平衡真实划转已补齐

* 已解决。
* `SingleExchangeArbitrageRebalanceService` 现在会在保证金率低于 `rebalance-threshold-margin-ratio` 时，调用 Binance Universal Transfer 将 `rebalance-asset` 从现货账户划转到 U 本位合约账户。
* 划转前会检查 API Key 是否具备 `permitsUniversalTransfer` / `enableInternalTransfer` 权限，并根据目标保证金率 `rebalance-target-margin-ratio`、当前合约保证金余额、维持保证金和现货可用余额计算实际划转金额。
* 已新增 `single_exchange_arbitrage_rebalance_record` 运行时流水表写入，会记录再平衡执行成功、条件不满足跳过以及异常失败三类结果。
* 流水表中已包含当前保证金率、阈值/目标保证金率、理论应划转金额、现货可用余额、实际划转金额以及交易所返回的 `transfer_id`，可直接用于再平衡审计和问题排查。
* 已新增 `single_exchange_arbitrage_risk_snapshot` 的 `REBALANCE` 阶段运行时写入，仅在保证金率低于 `rebalance-threshold-margin-ratio` 并真正进入再平衡评估时落库。
* 每次再平衡评估无论结果是 `EXECUTED`、`SKIPPED` 还是 `FAILED`，都会同步写入 1 条风控快照和 1 条再平衡流水。
* `single_exchange_arbitrage_rebalance_record` 已新增 `risk_snapshot_id`，用于将再平衡流水与对应的风控快照进行逻辑关联。
* 当前已补齐 `REBALANCE`、`PRE_OPEN`、`PRE_CLOSE` 三个阶段的运行时风控快照落库。

### 2.3 配置管理仍待统一

* 已解决。
* 单交易所套利相关参数已统一收敛到 `SingleExchangeSpotFuturesProperties`，由单一配置对象集中读取 `arbitrage.spot-futures` 下的全部参数。
* `SingleExchangeSpotFuturesArbitrageStrategy`、`SingleExchangeRiskController`、`SingleExchangeArbitrageRebalanceService` 已不再分散使用 `@Value`，避免配置项扩展时出现重复默认值和参数漂移。
* 当前 `application.yml` 的配置 key 保持不变，因此对现有部署和调参方式无破坏性影响。

### 2.4 深度与滑点风控缺失

* 已解决。
* 已新增 `SingleExchangeDepthMonitor`，在运行时读取现货与 U 本位合约的 `depth` 盘口，并在行情缺失时按配置进行快速重试。
* `SingleExchangeRiskController` 已增加基于逐档 VWAP 的深度与滑点风控：开仓会按计划下单量逐档估算现货与合约侧的成交均价，并据此计算组合滑点率。
* 开仓阶段采用 fail-closed 策略：若深度数据连续重试后仍不可用，或逐档 VWAP 估算出的组合滑点超过阈值，则直接拒绝开仓。
* 平仓阶段采用分级校验：即使流动性评估失败或行情缺失，也只记录告警和风险日志，不阻塞平仓执行。
* `SingleExchangeSpotFuturesArbitrageStrategy` 的净收益估算已改为优先使用本次实时逐档 VWAP 滑点估值，不再依赖“行情缺失时回退固定滑点后继续开仓”的旧逻辑。

### 2.5 告警系统缺失

* 回滚失败、风控拦截和 API 异常仍只有日志，没有主动告警。

### 2.6 全仓库测试未分层

* 当前仓库整体 `mvn test` 仍会受到其他模块联网测试影响。
* 核心策略单测可独立通过，但 CI 仍需区分单元测试和外部集成测试。

## 3. 后续计划

### 3.1 近期计划

1. 将流动性评估结果接入钉钉、Slack 或邮件告警。
2. 接入异常回滚和订单执行失败的主动告警。

### 3.2 实盘前必做项

1. 增加异常退出和回滚失败告警。
2. 明确单元测试、模拟测试和联网测试的边界。
3. 使用小资金进行长时间运行验证。
