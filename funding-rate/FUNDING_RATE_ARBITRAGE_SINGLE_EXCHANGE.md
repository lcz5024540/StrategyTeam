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
* | 步骤 | 现货 | 合约 | 基差变化 |
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

## 7. 模拟订单执行教程

### 7.1 模拟模式说明

当前实现支持通过 `arbitrage.spot-futures.order-execution-mode` 控制下单模式：

* `BINANCE_LIVE`：保持真实 Binance 下单行为。
* `BINANCE_TEST`：使用真实 Binance production API 的 `/order/test` 接口校验签名、权限、交易对、数量和订单参数，但不会把订单发送到撮合引擎；系统会在本地构造 `FILLED` 模拟成交回执，并继续走正常持仓、订单、风控快照和资金费结算落库链路。

模拟模式只模拟开仓和平仓订单本身，行情、资金费率、账户余额、保证金率、深度和风控检查仍读取真实 Binance API。

### 7.2 配置方式

在 `application.yml` 配置：

```yaml
arbitrage:
  spot-futures:
    order-execution-mode: "BINANCE_TEST"
    symbol: "BTCUSDT"
    trade-usdt-amount: 100
    leverage: 3
```

如果通过环境变量覆盖，可使用：

```powershell
$env:ARBITRAGE_SPOT_FUTURES_ORDER_EXECUTION_MODE="BINANCE_TEST"
```

### 7.3 启动服务

在项目根目录执行：

```powershell
mvn -pl coin-strategy-job -am spring-boot:run -Dspring-boot.run.profiles=test
```

服务默认监听：

```text
http://localhost:8081/strategy/api
```

### 7.4 手动执行一次策略

使用 `run-once` 复用主策略链路执行一次判断、风控、模拟下单和落库：

```powershell
Invoke-RestMethod -Method Post "http://localhost:8081/strategy/api/binance/strategy/arbitrage/runtime/run-once"
```

返回结果中应能看到：

* `orderExecutionMode` 为 `BINANCE_TEST`
* `running` 在单次执行后保持或恢复为原状态
* `symbol`、`tradeUsdtAmount`、`leverage` 为当前配置值

### 7.5 持续运行与停止

启动每分钟循环：

```powershell
Invoke-RestMethod -Method Post "http://localhost:8081/strategy/api/binance/strategy/arbitrage/runtime/start"
```

查看运行状态：

```powershell
Invoke-RestMethod "http://localhost:8081/strategy/api/binance/strategy/arbitrage/runtime/status"
```

停止循环：

```powershell
Invoke-RestMethod -Method Post "http://localhost:8081/strategy/api/binance/strategy/arbitrage/runtime/stop"
```

### 7.6 数据库观察

执行后可查看以下表确认数据是否正常落库：

```sql
SELECT * FROM single_exchange_arbitrage_position ORDER BY id DESC LIMIT 5;
SELECT * FROM single_exchange_arbitrage_order ORDER BY id DESC LIMIT 10;
SELECT * FROM single_exchange_arbitrage_risk_snapshot ORDER BY id DESC LIMIT 10;
SELECT * FROM single_exchange_arbitrage_funding_settlement ORDER BY id DESC LIMIT 10;
```

重点检查：

* `single_exchange_arbitrage_order.raw_response` 应包含 `BINANCE_TEST simulated fill after /order/test validation`。
* 订单状态应按模拟成交写入 `FILLED`，失败时会写入 `FAILED` 和 `error_message`。
* Binance 真实账户不会新增真实现货成交或合约持仓。

### 7.7 注意事项

* `BINANCE_TEST` 仍需要 Binance API key/secret 可用，因为 `/order/test` 会校验签名和账户权限。
* 模拟模式覆盖开仓和平仓，避免没有真实仓位时触发真实平仓。
* `POST /binance/strategy/arbitrage/execute` 已接入策略落库闭环；适合从前端 FundingArb 页面手动批量开仓，并由后台跟踪任务继续管理订单、资金费结算与自动平仓。
* 切回实盘前必须确认 `arbitrage.spot-futures.order-execution-mode` 已显式设置为 `BINANCE_LIVE`，并重新检查资金、杠杆、风控阈值和网络代理配置。

## 8. FundingArb 落库与自动跟踪闭环教程

### 8.1 闭环目标

本节对应前端 `FundingArbPage.tsx` 的手动执行入口。用户在页面提交 symbol、amount、minRate、leverage 后，后端不再只返回执行日志，而是完成以下闭环：

1. 开仓前生成 `SingleExchangeSpotFuturesOrderPlan`，并通过资金费率、基差和风控检查。
2. 下单前创建 `single_exchange_arbitrage_position`，状态为 `OPENING`。
3. 现货买入和合约做空执行后，写入 `single_exchange_arbitrage_order`。
4. 双侧成功时仓位更新为 `OPENED`；失败时保留订单记录并标记 `FAILED`。
5. 后台定时任务持续扫描 `OPENING / OPENED / CLOSING` 仓位，刷新订单、记录资金费结算，并在退出条件触发时自动平仓。
6. 平仓成功后写入 `CLOSE` 阶段订单，并将仓位标记为 `CLOSED`。

### 8.2 核心代码入口

本闭环主要由以下类协作完成：

* `BinanceSingleExchangeFundingRateArbitrageService`：前端手动执行入口的应用服务，负责批量 symbol 评估和触发开仓。
* `FundingArbLifecycleService`：统一处理 position、order、funding settlement 的落库和状态流转。
* `FundingArbTrackingJob`：后台定时扫描活跃仓位，执行订单刷新、资金费结算和自动平仓判断。
* `SingleExchangeArbitrageOrderExecutor`：负责现货/合约双侧开仓、平仓和失败回滚。
* `SingleExchangeSpotFuturesProperties`：读取 `arbitrage.spot-futures.*` 配置。

### 8.3 配置项

在 `coin-strategy-job/src/main/resources/application.yml` 中配置：

```yaml
arbitrage:
  spot-futures:
    order-execution-mode: "BINANCE_TEST"
    tracking-enabled: true
    tracking-fixed-delay-ms: 60000
    tracking-position-limit: 50
    close-threshold: 0.0001
    max-basis-deterioration-rate: 0.0015
    emergency-close-margin-ratio: 1.80
```

配置含义：

* `tracking-enabled`：是否启用 FundingArb 持久化仓位跟踪任务。
* `tracking-fixed-delay-ms`：每轮跟踪任务结束后等待多久再执行下一轮，默认 60 秒。
* `tracking-position-limit`：单轮最多扫描多少个活跃仓位，默认 50。
* `close-threshold`：资金费率跌破该值时触发平仓。
* `max-basis-deterioration-rate`：当前基差相对开仓基差走阔超过该值时触发平仓。
* `emergency-close-margin-ratio`：保证金率低于该值时触发紧急平仓。

### 8.4 前端手动开仓

前端 FundingArb 页面调用的接口保持不变：

```text
POST /strategy/api/binance/strategy/arbitrage/execute
```

PowerShell 示例：

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri "http://localhost:8081/strategy/api/binance/strategy/arbitrage/execute?symbols=BTCUSDT,ETHUSDT&amount=50&minRate=0.0005&leverage=3"
```

返回值仍是执行日志，日志中的 `Summary` 会显示每个 symbol 的结果，例如：

```text
=== Summary ===
BTCUSDT -> Opened
ETHUSDT -> Skipped: funding rate below threshold
```

只要某个 symbol 返回 `Opened`，对应仓位和订单应已落库，后台任务随后会自动接管跟踪。

### 8.5 数据库检查

开仓成功后检查仓位：

```sql
SELECT id, position_code, symbol, status, leverage, quantity,
       entry_funding_rate, entry_basis_rate, entry_next_funding_time,
       spot_buy_order_id, futures_sell_order_id, updated_at
FROM single_exchange_arbitrage_position
WHERE strategy_type = 'SINGLE_EXCHANGE_SPOT_FUTURES_ARBITRAGE_V1'
ORDER BY id DESC
LIMIT 10;
```

检查订单：

```sql
SELECT id, position_id, position_code, symbol, market_type, trade_phase,
       hedge_side, side, status, exchange_order_id, client_order_id,
       executed_qty, avg_price, quote_qty, error_message, placed_at, updated_at
FROM single_exchange_arbitrage_order
ORDER BY id DESC
LIMIT 20;
```

检查资金费结算：

```sql
SELECT id, position_id, position_code, symbol, funding_time,
       funding_rate, mark_price, position_qty, notional_value,
       settlement_amount, direction, created_at
FROM single_exchange_arbitrage_funding_settlement
ORDER BY id DESC
LIMIT 20;
```

预期状态：

* 开仓执行中：`position.status = OPENING`。
* 双侧开仓成功：`position.status = OPENED`，订单表有 `trade_phase = OPEN` 的 `SPOT` 和 `FUTURES` 记录。
* 开仓失败：`position.status = FAILED`，订单表保留失败腿的 `error_message`。
* 自动平仓执行中：`position.status = CLOSING`。
* 自动平仓成功：`position.status = CLOSED`，订单表新增 `trade_phase = CLOSE` 的双侧记录。
* 资金费结算：每个仓位同一个 `funding_time` 只插入一条 settlement，避免重复记账。

### 8.6 后台跟踪任务行为

`FundingArbTrackingJob` 每轮会加载 `SINGLE_EXCHANGE_SPOT_FUTURES_ARBITRAGE_V1` 下的活跃仓位：

```text
OPENING, OPENED, CLOSING
```

处理规则：

* `OPENING`：刷新订单后，如果开仓双侧都已成交，则恢复为 `OPENED`。
* `OPENED`：刷新订单、检查 funding settlement、判断是否触发平仓。
* `CLOSING`：刷新订单后，如果平仓双侧都已成交，则恢复为 `CLOSED`；否则继续等待下一轮。

自动平仓触发条件沿用当前单交易所策略规则：

* 当前资金费率小于等于 0。
* 当前资金费率低于 `close-threshold`。
* 当前基差相对开仓基差走阔超过 `max-basis-deterioration-rate`。
* 合约保证金率低于 `emergency-close-margin-ratio`。

平仓前仍会做流动性评估，但该评估只用于日志和风险提示，不阻塞退出。

### 8.7 前端面板验证

FundingArb 页面已经读取 `FUNDING_ARB` 的以下接口：

```text
GET /strategy/api/strategy-platform/strategy/FUNDING_ARB/summary
GET /strategy/api/strategy-platform/strategy/FUNDING_ARB/positions
GET /strategy/api/strategy-platform/strategy/FUNDING_ARB/orders
GET /strategy/api/strategy-platform/strategy/FUNDING_ARB/timeline
```

落库闭环完成后，页面应能看到：

* 指标区展示 FundingArb 已实现 PnL、当前持仓、订单数量和胜率。
* 持仓列表展示 `OPENING / OPENED / CLOSING` 仓位。
* 订单明细展示开仓和平仓双侧订单。
* 收益时间线展示资金费结算和已平仓收益。

### 8.8 验证命令

代码编译验证：

```powershell
mvn -pl coin-strategy-job -am -DskipTests compile
```

如果本地 `target/classes/application.yml` 被 IDE 或运行中的服务锁定，可临时跳过资源复制，只验证 Java 编译：

```powershell
mvn -pl coin-strategy-job -am -DskipTests "-Dmaven.resources.skip=true" compile
```

新增测试类：

```text
coin-strategy-job/src/test/java/com/metaverse/coin/strategy/job/funding/service/FundingArbLifecycleServiceTest.java
```

该测试覆盖：

* 开仓成功后 position 标记 `OPENED` 并写入订单。
* 开仓失败后 position 标记 `FAILED`，订单错误信息保留。
* funding time 推进后 settlement 只记录一次。
* funding 低于平仓阈值后自动平仓并标记 `CLOSED`。

### 8.9 注意事项

* `BINANCE_TEST` 会模拟订单成交，但行情、资金费率、账户余额、保证金率和深度仍读取真实 Binance API。
* `BINANCE_LIVE` 会真实下单和平仓，启用前必须确认 API 权限、账户资金、杠杆、代理和风控阈值。
* 手动入口支持多 symbol，但每个 symbol 都会独立创建仓位；后台跟踪按数据库中的活跃仓位逐个 symbol 管理。
* 不要手工修改活跃仓位状态，除非已经确认交易所真实订单与数据库状态一致。

## 9. Binance 查询 WebSocket 化与 HTTP 写接口边界

FundingArb 的高频查询已经接入 `BinanceQueryDataProvider` 读门面。业务侧不再直接依赖 Binance REST 查询价格、资金费、深度、订单状态和合约账户状态；默认优先读取本地 websocket 缓存，缓存缺失或过期时才使用 HTTP fallback。

### 9.1 数据来源

公开行情 websocket：

```text
Spot:
  <symbol>@bookTicker
  <symbol>@depth20@100ms

USDS-M Futures:
  <symbol>@bookTicker
  <symbol>@depth20@100ms
  <symbol>@markPrice@1s
```

账户与订单 websocket：

```text
Spot user data stream:
  wss://ws-api.binance.com:443/ws-api/v3
  userDataStream.subscribe.signature
  executionReport

USDS-M Futures user data stream:
  wss://ws-fapi.binance.com/ws-fapi/v1
  userDataStream.start / userDataStream.ping / userDataStream.stop
  wss://fstream.binance.com/private/ws/<listenKey>
  ORDER_TRADE_UPDATE
  ACCOUNT_UPDATE
```

这些流用于维护 spot/futures price ticker、futures premium index、depth20 盘口、spot/futures order cache、futures account cache 和 position risk cache。Spot 不再使用 legacy listenKey，也不会再调用 `POST /api/v3/userDataStream`。USDS-M Futures 仍有 listenKey 概念，但创建、续期、关闭已经改为 WebSocket API `userDataStream.start/ping/stop`，不再调用 HTTP `/fapi/v1/listenKey`。

### 9.2 HTTP 仍保留的场景

写接口继续使用 HTTP：

```text
spot/futures place order
spot/futures cancel order
change leverage
change margin type
universal transfer
```

查询接口只有在以下情况使用 HTTP：应用启动后的首次查询、websocket 断线或事件缺口、缓存超过 `binance.websocket.cache-ttl-ms`、REST-only 元数据查询，以及 websocket 开关关闭。

### 9.3 配置项

```yaml
binance:
  websocket:
    enabled: true
    public-market-enabled: true
    user-data-enabled: true
    cache-ttl-ms: 5000
    http-fallback-enabled: true
    reconnect-delay-ms: 5000
    listen-key-keepalive-ms: 1800000
    spot-stream-url: "wss://stream.binance.com"
    futures-stream-url: "wss://fstream.binance.com"
    spot-websocket-api-url: "wss://ws-api.binance.com:443/ws-api/v3"
    futures-websocket-api-url: "wss://ws-fapi.binance.com/ws-fapi/v1"
    symbols: ""
```

`symbols` 为空时，系统会在策略使用某个 symbol 时动态订阅；也会用 `arbitrage.spot-futures.symbol` 做启动订阅。多 symbol 手动开仓时，每个 symbol 的查询都会触发对应 websocket 订阅。

### 9.4 FundingArb 调用链变化

* `SingleExchangeArbitrageOrderExecutor.createSpotFuturesPlan`：spot/futures price 优先 websocket cache，`exchangeInfo` 继续 HTTP 低频读取。
* `SingleExchangeDepthMonitor`：spot/futures depth 优先 websocket cache。
* `FundingArbLifecycleService`：premium index、order refresh 优先 websocket/user data cache。
* `SingleExchangeRiskService`：futures account/margin ratio 优先 websocket account cache，缺失时 HTTP reconcile。

正常跟踪轮询不会每轮都打 Binance REST 行情接口；只有缓存不可用时才回补，能够显著降低限频压力。

### 9.5 排障建议

* 如果日志持续出现 `websocket cache miss/stale`，优先检查 websocket endpoint、代理和 `cache-ttl-ms` 是否过小。
* 如果订单状态没有及时刷新，检查 Spot/Futures WebSocket API 连接是否订阅成功，以及 `user-data-enabled` 是否为 true；Spot legacy listenKey 已不可用，Futures listenKey 通过 WebSocket API 维护。
* 如果启动后第一次查询仍打 HTTP，这是预期行为；第一次 HTTP 回补会同步 warm cache。
* 如果希望严格禁止查询 HTTP fallback，可将 `http-fallback-enabled=false`。此时缓存缺失会直接抛出错误，不建议 live 交易初期使用。
