# 单交易所资金费率套利策略流程图

本文基于当前仓库已实现代码整理，主参考实现为：

- `coin-strategy-core/src/main/java/com/metaverse/coin/strategy/core/arbitrage/decision/SingleExchangeSpotFuturesArbitrageStrategy.java`
- `coin-strategy-core/src/main/java/com/metaverse/coin/strategy/core/arbitrage/execution/SingleExchangeArbitrageOrderExecutor.java`
- `coin-strategy-core/src/main/java/com/metaverse/coin/strategy/core/arbitrage/risk/SingleExchangeRiskService.java`
- `coin-strategy-core/src/main/java/com/metaverse/coin/strategy/core/arbitrage/execution/SingleExchangeArbitrageRebalanceService.java`
- `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/service/BinanceSingleExchangeFundingRateArbitrageService.java`

## 1. 主流程图

```mermaid
flowchart TD
    A["策略启动<br/>SingleExchangeSpotFuturesArbitrageStrategy.start()"] --> B["恢复数据库中未完成持仓<br/>restorePersistedPositions()"]
    B --> C["每 1 分钟执行一次<br/>executeLogic()"]
    C --> D["读取配置 symbol / tradeUsdtAmount / leverage"]
    D --> E["获取资金费率快照<br/>SingleExchangeFundingRateMonitor.getBinanceFundingRate(symbol)"]
    E --> F["生成现货/合约对冲计划<br/>orderExecutor.createSpotFuturesPlan(...)"]
    F --> G{"activePositions 是否已有持仓?"}

    G -- 否 --> H["开仓前流动性检查<br/>riskService.evaluateLiquidityRisk(plan, OPEN)"]
    H --> I{"流动性通过?"}
    I -- 否 --> I1["写 PRE_OPEN 风险快照<br/>single_exchange_arbitrage_risk_snapshot<br/>结束本轮"]
    I -- 是 --> J["开仓条件过滤<br/>getOpenBlockReason()"]
    J --> K{"满足开仓条件?"}
    K -- 否 --> K1["记录 skip 原因并结束本轮"]
    K -- 是 --> L["交易风控检查<br/>riskService.evaluateTradeRisk(plan)"]
    L --> M{"风控通过?"}
    M -- 否 --> M1["写 PRE_OPEN 风险快照<br/>结束本轮"]
    M -- 是 --> N["先落 OPENING 持仓<br/>single_exchange_arbitrage_position"]
    N --> O["写 PRE_OPEN 风险快照"]
    O --> P["并发下单<br/>现货 BUY + 合约 SHORT<br/>openArbitragePosition(plan)"]
    P --> Q["落订单流水<br/>single_exchange_arbitrage_order"]
    Q --> R{"双侧都成功?"}
    R -- 是 --> S["更新持仓为 OPENED<br/>写开仓价格/订单号/nextFundingTime"]
    R -- 否 --> T["执行回滚或标记 FAILED"]
    S --> U["加入内存 activePositions"]
    T --> V["结束本轮"]

    G -- 是 --> W["对账资金费结算窗口<br/>reconcileFundingSettlement(...)"]
    W --> X{"nextFundingTime 已跨结算点?"}
    X -- 否 --> Y["评估平仓触发条件<br/>getCloseDecision()"]
    X -- 是 --> X1["写资金费结算记录<br/>single_exchange_arbitrage_funding_settlement"]
    X1 --> X2["累计 fundingFeesEarned<br/>推进 entryNextFundingTime"]
    X2 --> Y
    Y --> Z{"是否触发平仓?"}
    Z -- 否 --> Z1["继续持有，结束本轮"]
    Z -- 是 --> AA["平仓前流动性评估<br/>riskService.evaluateLiquidityRisk(plan, CLOSE)"]
    AA --> AB["写 PRE_CLOSE 风险快照"]
    AB --> AC["标记持仓为 CLOSING"]
    AC --> AD["并发平仓<br/>现货 SELL + 合约 BUY<br/>closeArbitragePosition(plan)"]
    AD --> AE["落平仓订单流水"]
    AE --> AF{"平仓成功?"}
    AF -- 是 --> AG["更新持仓为 CLOSED<br/>写 exit 价格/订单号/资金费率"]
    AF -- 否 --> AH["回滚状态为 OPENED"]
    AG --> AI["从 activePositions 移除"]
    AH --> AJ["等待下一轮继续处理"]

    subgraph RB["旁路流程：保证金再平衡"]
        RB1["每 5 分钟执行<br/>SingleExchangeArbitrageRebalanceService.checkAndRebalance()"] --> RB2["读取 futures marginRatio"]
        RB2 --> RB3{"低于 rebalanceThresholdMarginRatio?"}
        RB3 -- 否 --> RB4["结束"]
        RB3 -- 是 --> RB5["检查 universal transfer 权限"]
        RB5 --> RB6["计算目标保证金与需转入金额"]
        RB6 --> RB7["Spot -> U 本位合约划转"]
        RB7 --> RB8["落风险快照与再平衡记录<br/>single_exchange_arbitrage_risk_snapshot<br/>single_exchange_arbitrage_rebalance_record"]
    end
```

## 2. 手动触发简化链路

当前仓库还保留了一条手动触发链路：

```mermaid
flowchart LR
    A["POST /binance/strategy/arbitrage/execute"] --> B["BinanceSingleExchangeArbitrageController"]
    B --> C["BinanceSingleExchangeFundingRateArbitrageService.executeArbitrage(...)"]
    C --> D["读取 funding rate"]
    D --> E["createSpotFuturesPlan(...)"]
    E --> F["checkTradeRisk(plan)"]
    F --> G["changeInitialLeverage(...)"]
    G --> H["openArbitragePosition(plan)"]
    H --> I["返回字符串日志给调用方"]
```

这条链路的特点：

- 适合人工调试或手工开仓。
- 当前不会像策略化主链路那样完整落库持仓、风险快照、资金费结算。
- 因此如果要描述“生产态策略生命周期”，应以 `SingleExchangeSpotFuturesArbitrageStrategy` 为准。

## 3. 关键决策点

- 开仓准入：资金费率、结算保护窗口、基差区间、预期净收益、流动性、账户余额、保证金率。
- 持仓中检查：是否跨过资金费结算点、是否需要累计 funding settlement。
- 平仓触发：资金费率转非正、低于平仓阈值、基差恶化超阈值、保证金率跌破紧急阈值。
- 失败保护：双侧任一侧失败时回滚；平仓失败则把状态恢复为 `OPENED`，等待下一轮继续退出。

## 4. 相关落库表

- `single_exchange_arbitrage_position`：持仓状态机主表。
- `single_exchange_arbitrage_order`：开仓、平仓、回滚订单流水。
- `single_exchange_arbitrage_funding_settlement`：资金费结算记录。
- `single_exchange_arbitrage_risk_snapshot`：开仓前、平仓前、再平衡检查快照。
- `single_exchange_arbitrage_rebalance_record`：自动补保证金记录。
