# 单交易所资金费率套利策略链路执行文档

## 1. 文档范围

本文只描述当前仓库里已经实现的“单交易所现货多 + 永续空”的资金费率套利链路，不扩展到未落地的跨所路径。

主链路对应的策略 ID：

- `SINGLE_EXCHANGE_SPOT_FUTURES_ARBITRAGE_V1`

对外展示编码：

- `FUNDING_ARB`

## 2. 当前代码里的两条执行路径

### 2.1 策略化主链路

核心类：

- `coin-strategy-core/.../decision/SingleExchangeSpotFuturesArbitrageStrategy.java`

特点：

- 有内存态 `activePositions`。
- 有持仓状态流转。
- 有风险快照、订单流水、资金费结算、再平衡记录。
- 适合作为“真实策略生命周期”的主文档对象。

### 2.2 手动触发简化链路

核心类：

- `coin-strategy-job/.../funding/controller/BinanceSingleExchangeArbitrageController.java`
- `coin-strategy-job/.../service/BinanceSingleExchangeFundingRateArbitrageService.java`

特点：

- 通过 HTTP 手工触发。
- 会做 funding 判断、建仓计划、基本风控和实际下单。
- 返回字符串日志。
- 当前不负责完整持仓生命周期落库，不应作为主策略链路的唯一依据。

## 3. 主链路模块划分

### 3.1 行情与监控

- `SingleExchangeFundingRateMonitor`
  负责读取 Binance 永续资金费率和下一次结算时间。
- `SingleExchangeDepthMonitor`
  负责读取 Spot/Futures 双边 depth，支持重试，给流动性风控做逐档 VWAP 估算。

### 3.2 计划生成与执行

- `SingleExchangeArbitrageOrderExecutor`
  负责：
  - 拉取 Spot/Futures 交易规则
  - 计算可交易数量
  - 校验最小下单量与最小名义金额
  - 并发开仓、并发平仓
  - 单腿失败时执行回滚
  - 生成订单流水对象 `SingleExchangeArbitrageOrder`

### 3.3 风控

- `SingleExchangeRiskService`
  负责：
  - Spot 账户可用 USDT 检查
  - Futures 可用保证金检查
  - 维持保证金比例检查
  - 开平仓前流动性检查
  - 风险快照数据组装

### 3.4 策略决策

- `SingleExchangeSpotFuturesArbitrageStrategy`
  负责：
  - 主循环调度
  - 开仓过滤
  - 平仓触发
  - 状态迁移
  - 资金费结算对账
  - 与数据库状态保持一致

### 3.5 再平衡

- `SingleExchangeArbitrageRebalanceService`
  负责：
  - 每 5 分钟检查一次合约账户保证金率
  - 当保证金率过低时，把 Spot 的 `USDT` 划转到 U 本位合约账户
  - 记录风险快照和再平衡结果

### 3.6 查询与展示

- `StrategyProductQueryService`
  负责：
  - 将 `FUNDING_ARB` 读模型拼装成策略详情页、订单列表、时间线、洞察信息
  - 当前页面展示直接读取单交易所套利相关库表

## 4. 配置入口

配置类：

- `coin-strategy-core/.../config/SingleExchangeSpotFuturesProperties.java`
- `coin-strategy-job/src/main/resources/application.yml`

关键配置项：

- `arbitrage.spot-futures.symbol`
- `arbitrage.spot-futures.trade-usdt-amount`
- `arbitrage.spot-futures.leverage`
- `arbitrage.spot-futures.open-threshold`
- `arbitrage.spot-futures.close-threshold`
- `arbitrage.spot-futures.min-basis-rate`
- `arbitrage.spot-futures.max-basis-rate`
- `arbitrage.spot-futures.max-basis-deterioration-rate`
- `arbitrage.spot-futures.estimated-round-trip-fee-rate`
- `arbitrage.spot-futures.estimated-slippage-rate`
- `arbitrage.spot-futures.min-funding-periods`
- `arbitrage.spot-futures.min-net-yield-rate`
- `arbitrage.spot-futures.settlement-protection-window-minutes`
- `arbitrage.spot-futures.max-position-value-usdt`
- `arbitrage.spot-futures.min-margin-ratio`
- `arbitrage.spot-futures.required-margin-buffer-rate`
- `arbitrage.spot-futures.emergency-close-margin-ratio`
- `arbitrage.spot-futures.rebalance-enabled`
- `arbitrage.spot-futures.rebalance-threshold-margin-ratio`
- `arbitrage.spot-futures.rebalance-target-margin-ratio`

## 5. 自动策略链路详解

### 5.1 启动阶段

入口：

- `SingleExchangeSpotFuturesArbitrageStrategy.start()`

启动动作：

1. 将 `running` 置为 `true`。
2. 调用 `restorePersistedPositions()`，从 `single_exchange_arbitrage_position` 中恢复 `OPENING`、`OPENED`、`CLOSING` 状态的最近持仓。
3. 启动单线程定时器，按 1 分钟频率执行 `executeLogic()`。

实现备注：

- 当前仓库中可以看到策略类具备 `start()/stop()` 生命周期。
- 但在本次检索范围内，没有看到统一的 `StrategyManager/Registry` 自动拉起该策略。
- 这意味着如果外部没有显式调用 `start()`，这条自动轮询链路不会自行运行。

### 5.2 每轮主循环输入

`executeLogic()` 每次会读取：

1. `symbol`
2. `tradeUsdtAmount`
3. `leverage`
4. 最新 `premiumIndex`
5. 最新 Spot/Futures 价格与交易规则

随后构造：

- `SingleExchangeSpotFuturesOrderPlan`

该计划对象包含：

- `quantity`
- `spotPrice`
- `futuresPrice`
- `basisSpread`
- `basisRate`
- `spotNotional`
- `futuresNotional`
- `minQuantity`
- `minNotional`
- `leverage`

## 6. 开仓链路

### 6.1 流动性前置检查

执行：

- `riskService.evaluateLiquidityRisk(plan, PHASE_OPEN)`

检查点：

- Spot/Futures depth 是否成功获取
- 双边盘口是否能覆盖目标数量
- 逐档 VWAP 推算的组合滑点是否超过阈值

失败处理：

- 直接拒绝开仓
- 落 `PRE_OPEN` 风险快照到 `single_exchange_arbitrage_risk_snapshot`

### 6.2 开仓准入过滤

执行：

- `getOpenBlockReason(...)`

依次判断：

1. `fundingRate > openThreshold`
2. 距离下一次资金费结算是否过近
3. `basisRate` 是否在 `[minBasisRate, maxBasisRate]`
4. 预估净收益是否高于 `minNetYieldRate`

预估净收益公式：

`currentFundingRate * minFundingPeriods - estimatedRoundTripFeeRate - estimatedSlippageRate`

### 6.3 账户风险检查

执行：

- `riskService.evaluateTradeRisk(plan)`

检查点：

1. 单笔价值是否超过 `maxPositionValueUsdt`
2. Spot 账户 USDT 余额是否足够
3. Futures 账户可用余额是否覆盖带缓冲的所需保证金
4. 当前合约保证金率是否高于 `minMarginRatio`

### 6.4 开仓前落库

执行：

1. `createOpeningPosition(...)`
2. `persistRiskSnapshot(...)`

结果：

- 在 `single_exchange_arbitrage_position` 中插入一条 `OPENING` 记录
- 在 `single_exchange_arbitrage_risk_snapshot` 中插入一条 `PRE_OPEN` 记录

### 6.5 双腿并发开仓

执行：

- `orderExecutor.openArbitragePosition(plan)`

内部行为：

1. 并发发起 Spot `BUY MARKET`
2. 并发发起 Futures `SELL MARKET` + `SHORT`
3. 汇总双腿结果
4. 构建 `SingleExchangeArbitrageExecutionResult`
5. 返回两条订单流水对象

### 6.6 单腿失败的回滚逻辑

在 `SingleExchangeArbitrageOrderExecutor` 中：

- Spot 成功 / Futures 失败：立即下 Spot `SELL` 回滚
- Spot 失败 / Futures 成功：立即下 Futures `BUY` 回补空头
- 双腿都失败：直接返回失败

### 6.7 开仓成功后的状态流转

成功时：

1. `persistOrderRecords(...)` 写入 `single_exchange_arbitrage_order`
2. `markPositionOpened(...)` 将持仓更新为 `OPENED`
3. 将仓位放入内存 `activePositions`

失败时：

1. 订单流水仍会落库
2. 持仓被标记为 `FAILED`

## 7. 持仓阶段链路

### 7.1 资金费结算对账

执行：

- `reconcileFundingSettlement(activePosition, premiumIndex, marketPlan)`

触发条件：

- `latestNextFundingTime > lastRecordedFundingTime`
- 且当前时间已经跨过 `lastRecordedFundingTime`

处理动作：

1. 计算 `markPrice`
2. 计算本次 `notionalValue`
3. 计算 `settlementAmount = notionalValue * fundingRate`
4. 写入 `single_exchange_arbitrage_funding_settlement`
5. 累计到持仓字段 `fundingFeesEarned`
6. 推进持仓中的 `entryNextFundingTime`

幂等控制：

- 通过 `(positionId, fundingTime)` 查重，避免重复落账

### 7.2 平仓触发决策

执行：

- `getCloseDecision(...)`

触发条件包括：

1. 当前资金费率 `<= 0`
2. 当前资金费率 `< closeThreshold`
3. 当前基差相对入场基差恶化超过 `maxBasisDeteriorationRate`
4. 当前保证金率 `< emergencyCloseMarginRatio`

其中第 4 条会返回 `emergency=true`。

## 8. 平仓链路

### 8.1 平仓前流动性检查

执行：

- `riskService.evaluateLiquidityRisk(closePlan, PHASE_CLOSE)`

与开仓不同的地方：

- 结果会被落库和告警
- 但不会阻塞平仓
- 代码倾向于优先退出风险，而不是因为流动性不足继续持有

### 8.2 平仓前落库

执行：

1. `persistRiskSnapshot(..., PRE_CLOSE, ...)`
2. `markPositionClosing(...)`

结果：

- 风险快照写入 `single_exchange_arbitrage_risk_snapshot`
- 持仓状态更新为 `CLOSING`

### 8.3 双腿并发平仓

执行：

- `orderExecutor.closeArbitragePosition(closePlan)`

内部行为：

1. 并发发起 Spot `SELL MARKET`
2. 并发发起 Futures `BUY MARKET` 平空
3. 若任一侧失败，则尝试反向回滚

### 8.4 平仓后的状态流转

成功时：

1. 写入平仓订单流水
2. 更新持仓为 `CLOSED`
3. 记录退出价格、退出订单号、退出资金费率、退出基差
4. 从 `activePositions` 移除

失败时：

1. 订单流水照常记录
2. 调用 `revertPositionToOpened(...)`
3. 将状态恢复为 `OPENED`
4. 等待下一轮再次评估退出

## 9. 再平衡链路

核心类：

- `SingleExchangeArbitrageRebalanceService`

调度：

- `@Scheduled(fixedRate = 300000)`

执行步骤：

1. 查询当前 Futures `marginRatio`
2. 若低于 `rebalanceThresholdMarginRatio`，进入补保证金流程
3. 检查 API key 是否具备 `universal transfer` 权限
4. 计算目标保证金余额和需要划转金额
5. 读取 Spot 可用余额
6. 执行 `MAIN_UMFUTURE` 划转
7. 写入：
   - `single_exchange_arbitrage_risk_snapshot`
   - `single_exchange_arbitrage_rebalance_record`

这一链路与开平仓并行存在，属于“持仓安全护栏”。

## 10. 手动触发链路说明

### 10.1 入口

- `POST /binance/strategy/arbitrage/execute`

参数：

- `symbol` 或 `symbols`
- `amount`
- `minRate`
- `leverage`

### 10.2 执行步骤

1. 解析 symbol 列表
2. 逐个 symbol 拉取 funding rate
3. funding 不达标则跳过
4. 构造 `SingleExchangeSpotFuturesOrderPlan`
5. 基础风控检查
6. 设置合约杠杆
7. 调用 `openArbitragePosition(plan)`
8. 将结果以字符串日志形式返回

### 10.3 与主链路的差异

- 不维护 `activePositions`
- 不会补全完整持仓状态机
- 不会自动记录 funding settlement
- 不负责自动平仓与周期性再平衡

因此：

- 调试、联调可以用这条链路
- 生产态策略说明应以自动策略链路为主

## 11. 持久化对象与职责

### 11.1 `single_exchange_arbitrage_position`

职责：

- 记录持仓生命周期主状态
- 保存开仓/平仓价格、订单号、数量、资金费、基差、异常信息

状态：

- `OPENING`
- `OPENED`
- `CLOSING`
- `CLOSED`
- `FAILED`

### 11.2 `single_exchange_arbitrage_order`

职责：

- 记录开仓、平仓、回滚三类订单
- 支持区分 `SPOT/FUTURES`、`OPEN/CLOSE/ROLLBACK`

### 11.3 `single_exchange_arbitrage_funding_settlement`

职责：

- 记录每个结算窗口的 funding 收付
- 支持按持仓回溯累计收益

### 11.4 `single_exchange_arbitrage_risk_snapshot`

职责：

- 记录 `PRE_OPEN`、`PRE_CLOSE`、`REBALANCE` 等关键风控快照

### 11.5 `single_exchange_arbitrage_rebalance_record`

职责：

- 记录补保证金动作、原因、金额、transferId 和失败信息

## 12. 页面读模型链路

策略看板读路径：

- `StrategyProductQueryService`

主要读取：

- 持仓表
- 订单表
- 资金费结算表

对外聚合成：

- summary
- metrics
- insights
- positions
- next settlements
- recent orders
- timeline

这意味着当前管理后台并不是直接读运行中内存，而是读数据库状态。

## 13. 当前实现边界

### 13.1 已实现

- 现货多 / 合约空对冲建仓
- 逐档深度与滑点风控
- 开平仓双腿并发与回滚
- 状态机持仓落库
- 风险快照落库
- 资金费结算对账
- 保证金不足时自动再平衡
- 看板读模型

### 13.2 需要注意的现实边界

- 自动策略具备 `start()`，但当前仓库检索范围内未看到统一自动启动器。
- 手动触发链路和自动策略链路并存，能力不对等。
- `markPositionClosed(...)` 当前只写退出价格和状态，没有看到显式计算 `realizedPnl` 的逻辑；看板侧更多依赖 `fundingFeesEarned + realizedPnl`，其中 `fundingFeesEarned` 已实现，`realizedPnl` 的完整闭环仍需后续核对。
- 再平衡只处理 Spot -> U 本位合约的补保证金，不负责更复杂的资产调仓。

## 14. 建议阅读顺序

建议从以下顺序理解代码：

1. `SingleExchangeSpotFuturesArbitrageStrategy`
2. `SingleExchangeArbitrageOrderExecutor`
3. `SingleExchangeRiskService`
4. `SingleExchangeDepthMonitor`
5. `SingleExchangeArbitrageRebalanceService`
6. `StrategyProductQueryService`
