---
title: "单交易所资金费率套利策略"
type: strategy
status: active
tags: [funding-rate, arbitrage]
created: 2026-04-13
updated: 2026-04-26
---

# 单交易所资金费率套利策略

## 0. 代码对齐版补充文档

- 流程图：`./SINGLE_EXCHANGE_FUNDING_RATE_ARBITRAGE_FLOW.md`
- 链路执行文档：`./SINGLE_EXCHANGE_FUNDING_RATE_ARBITRAGE_EXECUTION_CHAIN.md`

## 1. 策略定位

单交易所套利本质上是同一交易所内的期现对冲策略：买入现货、做空同标的永续合约，使净方向敞口尽量接近 0，主要赚取永续空头收到的资金费，并在基差收敛时争取额外收益。

## 2. 策略原理

### 2.1 基本操作

* **现货侧**：买入现货，持有标的多头。
* **合约侧**：卖出同标的永续合约，持有空头。
* **目标**：通过数量匹配或 Delta 匹配降低方向性风险。

### 2.2 收益来源

* **资金费收益**：当资金费率为正时，永续多头通常需要向空头支付资金费；反之，当资金费为负时，永续空头通常需要向多头支付资金费。
* **基差收益**：若开仓时合约升水，平仓时基差收敛，可获得额外收益；若基差继续走阔，也可能亏损。

#### 说明：基差 = 合约价 - 现货价

| 步骤 | 现货 | 合约 | 基差变化 |
|------|-----------|-------------|----------|
| 开仓时 | 花10元买入 | 以10.5元卖出合约 | 基差 = +0.5元（升水） |
| 平仓时 | 以10.1元卖出 | 以10.2元买回平仓 | 基差 = +0.1元（收敛了！） |
| 盈亏 | +0.1元（10.1-10） | +0.3元（10.5-10.2） | 总赚0.4元 ✅ |

### 2.3 主要成本

* 双边开平仓手续费。
* 滑点与买卖价差。
* 基差扩大导致的浮亏。
* 资金占用成本；若现货侧通过借币或融资完成，还需计入借贷成本。

### 2.4 合约侧杠杆

* **策略建议**：单交易所期现套利的合约空单应使用低杠杆，通常建议 `1x - 3x`。
* **推荐默认值**：若没有特别强的资金利用需求，优先使用 `3x` 及以下。
* **原因**：
    * 杠杆过高不会提升资金费率本身，却会明显放大保证金率恶化和强平风险。
    * 该策略核心是吃 funding 和控制回撤，不应为了压缩保证金占用而牺牲安全边际。

### 2.5 优势与边界

* **优势**：
    * 相比单边做多/做空，方向性风险显著下降。
    * 无跨所转账风险、跨所信用风险和跨所结算风险。
* **边界**：
    * 这不是无风险套利。
    * 即使保持 1:1 对冲，合约空头仍可能因标记价格快速上涨、保证金不足而被强平。

## 3. 详细执行逻辑

### 3.1 标的筛选

* 优先选择 BTC、ETH、SOL 等高流动性标的。
* 避免盘口过薄、价差过大、资金费波动极端的长尾币。

### 3.2 开仓前检查

* 当前资金费率 > `OPEN_THRESHOLD`。
* 若有预测能力，优先要求下期资金费率仍为正；若没有预测能力，应明确这是缺失项而不是隐含前提。
* 当前基差率处于可接受区间，既不过度为负，也不过度偏高。
* 预估最小持仓周期内的资金费收益应覆盖手续费、滑点和基差波动成本。
* 应优先读取现货与合约的实时 `depth` 盘口，并按计划下单量逐档吃单估算 VWAP。
* 开仓阶段必须拿到可用深度数据，并确认逐档 VWAP 估算出的组合滑点率不超过阈值；若盘口连续获取失败，应直接拒绝开仓。
* 避开临近资金费结算时段的追价开仓。

### 3.3 开仓动作

1. 根据预算金额和杠杆生成现货/合约对冲计划。
2. 对齐现货和合约的最小下单单位、最小名义价值与可交易数量。
3. 合约空单所在的合约侧使用预设低杠杆开仓；当前实现默认杠杆为 `3x`。
4. 并发提交现货买入和合约卖出。
5. 若一侧成交、另一侧失败，立即回滚，控制单侧暴露。

### 3.4 持仓监控

* 持续监控当前资金费率、下一次结算时间、基差变化和保证金率。
* 持续检查现货数量与合约空单数量是否仍接近匹配。
* 平仓前同样应评估现货与合约侧的 depth 流动性与预估滑点，但评估结果主要用于日志、告警和风险提示。
* 为保障退出及时性，当前实现中平仓不会因流动性评估失败而被阻塞；即使行情缺失，也会优先执行平仓。

### 3.5 平仓条件

* 当前资金费率跌破 `CLOSE_THRESHOLD`。
* 当前资金费率转非正，且预期继续恶化。
* 当前基差相对开仓基差显著走阔，继续持有的风险收益比失衡。
* 保证金率下降到预警或强平风险区间。
* 急需释放资金。

## 4. 核心风险与注意事项

### 4.1 摩擦成本

* 资金费率通常较低，双边手续费和滑点很容易吞噬利润。
* 策略不应只看单次资金费率，而应看预计持仓周期内的净收益。
* 静态滑点阈值仅用于判断是否可接受，开仓实盘中应优先使用实时 depth 的逐档 VWAP 估算组合滑点。

### 4.2 单侧成交风险

* 同一交易所内也存在现货侧与合约侧不同步成交的问题。
* 应并发下单，并在失败时即时回滚。

### 4.3 保证金与强平风险

* 对冲只能降低方向风险，不能消除合约侧的保证金风险。
* 必须监控合约账户保证金率，并预留缓冲。

### 4.4 基差风险

* 永续与现货之间的基差并不保证按有利方向回归。
* 若开仓后合约继续升水，空头侧会出现额外浮亏。

### 4.5 时间窗口风险

* 结算前后资金费和盘口波动可能明显放大。
* 应避开结算前后几分钟的新开仓操作，或专门为此做事件驱动策略。

## 5. 代码架构建议

### 5.1 模块划分（coin-strategy-core/arbitrage）

* **`monitor`**：获取 funding、mark price、spot/futures price、next funding time。
* **`decision`**：基于 funding、基差、净收益和保证金做开平仓决策。
* **`execution`**：负责下单计划生成、并发执行和回滚。
* **`risk`**：负责账户余额、保证金率和风控阈值校验。

### 5.2 当前已实现的核心能力

* 动态按 USDT 预算和杠杆生成 `SingleExchangeSpotFuturesOrderPlan`。
* 引入基差率过滤与临近结算保护。
* 引入预估净收益过滤，而非只看当前 funding。
* 开仓阶段已接入基于 depth 的逐档 VWAP 滑点风控，并在行情缺失时 fail-closed 直接拒绝开仓。
* 持仓后基于资金费率转负、基差恶化和保证金率恶化触发平仓；平仓阶段的流动性评估只做告警，不阻塞退出。

## 6. 当前实现状态

当前仓库中已经实现并持续优化该策略，对应核心类包括：

* `com.metaverse.coin.strategy.core.arbitrage.decision.SingleExchangeSpotFuturesArbitrageStrategy`
* `com.metaverse.coin.strategy.core.arbitrage.execution.SingleExchangeArbitrageOrderExecutor`
* `com.metaverse.coin.strategy.core.arbitrage.execution.SingleExchangeSpotFuturesOrderPlan`
* `com.metaverse.coin.strategy.core.arbitrage.risk.SingleExchangeRiskController`
* `com.metaverse.coin.strategy.core.arbitrage.execution.SingleExchangeArbitrageRebalanceService`
* `com.metaverse.coin.strategy.common.model.arbitrage.entity.SingleExchangeFundingRateHistory`
* `com.metaverse.coin.strategy.common.model.arbitrage.entity.SingleExchangeArbitragePosition`

当前实现中的默认参数：

* 合约侧默认杠杆：`3x`
* 配置位置：`coin-strategy-job/src/main/resources/application.yml`
* 配置键：`arbitrage.spot-futures.leverage`
* 单交易所持久化表：`single_exchange_funding_rate_history`、`single_exchange_arbitrage_position`

实施进度详见：[单交易所套利实施进度](./FUNDING_RATE_ARBITRAGE_SINGLE_EXCHANGE_SHARE.md)

---

<!-- kb-backlink -->
> 本文归属：[交易策略 知识地图](../docs/MOC-交易策略.md) ｜ [知识库首页](../docs/知识库首页.md)
