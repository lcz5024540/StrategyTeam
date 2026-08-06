# HTX 自主做市策略（Market Maker）设计

> 状态：设计已与用户逐段确认（2026-08-06）。下一步：writing-plans 生成实现计划。
> 模块：`coin-strategy-htx`，包根 `com.metaverse.coin.strategy.htx`。

## 1. 背景与定位

现有 `coin-strategy-htx` 是**执行 SDK，不含策略**（见 `docs/architecture-refactor/HTX_SPOT_EXECUTION_SDK_V1.md` §1/§8/§10）：外部调用 `POST /htx/api/htx/spot/locked-pair` 给定 symbol/qty/buyPrice/sellPrice，服务同步执行「挂买-limit → 等成交 → 挂卖-limit → 等成交」，5 秒填单超时。它**刻意不定义何时/以什么价触发交易**。

本设计新增当初被排除的**策略层**：一个自主运行的做市组件，周期性检测行情、自动开平仓，无需外部 HTTP 触发。它是执行 SDK **之上的独立组件**，不改动现有同步执行器（`HtxLockedPairExecutor`），而是直接复用底层 HTX 客户端与 `HtxDepthGuard`、现有风控上限，自己管理长期挂单。

## 2. 核心决策（来自用户逐个确认）

| 维度 | 决策 |
|---|---|
| 标的 | 白名单，仅 `btcusdt` / `ethusdt`，非白名单一律拒 |
| 定价模型 | **自设价差做市**：以参考价 P0 为中心，buyPrice=P0·(1−δ)、sellPrice=P0·(1+δ)，δ 可配且需 ≥ 手续费覆盖线 |
| 挂单生命周期 | **长期挂单（resting）**：买单挂在市价下方长期等成交（可配 TTL）；成交后挂卖单也长期等 |
| 下单节奏 | **多笔并行**：允许同一标的同时有多个未完成 cell |
| 开仓闸门 | **最大并行数 K + 价差去重**：open cell 数 < K 且新买价与已挂买价间距 ≥ min-gap 才开新 cell |
| 单笔金额 | **每 cell 固定 quote 名义**（如 20U），qty = notional / buyPrice |
| 不亏保证 | **硬不变量**：允许手续费磨损，但每笔 round-trip 必须非负。开仓前用保守配置费率 f 校验 `(1−f)²·sellPrice ≥ buyPrice`，不满足则拒开该 cell（不查真实费率） |
| 余额不足 | 下单前查现货账户 `trade` USDT；不足或 HTX 返回余额不足时暂停全部新买单，默认 5 分钟后重新查余额，不按 10 秒轮询频繁重试 |
| 成交后卖出 | 用 HTX 实际成交金额/成交量重算买入均价，在该成本附近加配置价差挂 `SELL-LIMIT`；提交失败保持待卖状态并使用固定 client id 重试，不转市价卖 |
| 急停语义 | **只停开仓、挂单保留**：`HTX_MM_ENABLED=false` 重启后停止开新 cell，已 resting 单不自动撤（不砸盘），靠 TTL 或人工了结 |

### 2.1 为什么不是「抓盘口现有价差」

用户初选「抓盘口 (ask−bid) 价差」，但存在经济性硬伤：BTC/USDT、ETH/USDT 主流对自然盘口价差常年仅约 0.01%~0.02%，而 HTX 现货双边手续费约 0.4%（买卖各约 0.2%）。抓天然价差无法覆盖手续费，主流对上几乎永不触发且 round-trip 亏损。且 locked-pair 是「先买成交再挂卖」，下单瞬间看到的 ask 在买腿成交后可能已消失，「现有价差」在成交时并不锁定。因此改为**自设价差**：价差由策略设定得足够宽以覆盖手续费，靠市场在买/卖价之间震荡赚差，δ 默认 0.5%（gross 1% 名义 − 约 0.4% 手续费 ≈ 净 0.6%）。

### 2.2 不亏保证（硬不变量）

用户明确：**允许手续费磨损，但每笔买入-卖出 round-trip 不能亏损**，且不必查账户真实费率。

按 HTX 费用语义（买手续费扣 base、卖手续费扣 quote，每边费率 f）：
- 买：花 `qty·buyPrice` quote，实收 base = `qty·(1−f)`；
- 卖：卖出实收 base 于 sellPrice，实收 quote = `qty·(1−f)·sellPrice·(1−f)` = `qty·(1−f)²·sellPrice`。
- 净收益（quote）= `qty·[(1−f)²·sellPrice − buyPrice]`。

**不亏 ⟺ `(1−f)²·sellPrice ≥ buyPrice`**。该条件只依赖锁定的 buy/sell 价与 f，**与成交量无关**，故部分成交同样成立。

落地为**开仓前硬闸门**：用保守配置费率 `fee-rate-per-side`（默认 0.002，可 env 覆盖，宁高勿低）校验上式；不满足则拒开该 cell。这使「不亏」成为不可能被 δ 配错破坏的不变量。启动时若 `spread-pct` 相对配置费率过小（几乎必被闸门拒），记 WARN 提示调大 δ。

## 3. 一个「做市 cell」的状态机

一笔 pair = 一个 cell。开仓瞬间取一次参考价 P0=(bid+ask)/2 并锁定买单限价；买单成交后用 HTX `filled-cash-amount / filled-amount` 得到实际买入均价，再以 `actualBuyPrice·(1+δ)` 计算卖单限价。HTX 未返回成交金额时才回退原买单限价。

```
开仓：P0 → buyPrice=P0(1−δ), sellPrice=P0(1+δ), qty=notional/buyPrice
      挂 BUY-LIMIT(buyPrice)
          │
   ┌── MM_RESTING_BUY ──(买单成交)──→ MM_RESTING_SELL(按实际成本限价挂卖) ──(卖单成交)──→ MM_DONE(记净盈亏)
   │        │                             │
   │  (买单超 buy-ttl 未成交)         (sell-ttl=0 默认不撤，一直挂等涨)
   │        ↓
   └──→ MM_CANCELLED_BUY(撤单, cell 关闭)
```

- **MM_RESTING_BUY**：买单挂着，仅占挂单额度，无持仓。买腿成交前若超 buy-ttl → 撤单 → MM_CANCELLED_BUY。
- **MM_RESTING_SELL**：买腿成交，持有 base，按实际成交成本加价挂卖单等涨。卖单 REST 暂时失败时仍保持该状态，下一 tick 用固定 `$clientId-SELL` 重试。**这是故意的中间态，不是裸露异常**。sell-ttl=0（默认）表示卖单一直挂到成交。
- **MM_DONE**：卖腿成交，完整 round-trip，记录 qty·(sellPrice−buyPrice) − 手续费。

## 4. 调度器主循环

`HtxMarketMakerScheduler` 每 `poll-interval-ms`（默认 10s）跑一次。对每个白名单 symbol：

1. **刷新 open cell 状态**：对本 symbol 每个 MM_RESTING_* cell 调 `queryOrder` 推进状态机——
   - RESTING_BUY 且买单已成交 → 挂 SELL-LIMIT(sellPrice) → RESTING_SELL；
   - RESTING_BUY 且超 buy-ttl → cancelOrder → CANCELLED_BUY；
   - RESTING_SELL 且卖单已成交 → MM_DONE。
2. **判断是否开新 cell**：
   - 若处于余额不足退避窗口，跳过全部新买单；窗口到期后重新查询账户余额；
   - 查盘口 depth，算 P0=(bid+ask)/2、buyPrice、sellPrice、qty；
   - 闸门：本 symbol open cell 数 < K **且** 新 buyPrice 与所有已挂买单价差 ≥ min-gap-pct（去重防同价堆单）；
   - **不亏闸门**：校验 `(1−f)²·sellPrice ≥ buyPrice`（f=fee-rate-per-side），不满足则拒开；
   - 过 `HtxDepthGuard`（top-N 深度足够）；
   - 查现货账户余额，`trade` USDT 必须覆盖本单名义；查询失败 fail-closed；
   - 过现有风控上限（单笔 max-single ≤ 50U、日累计 max-daily ≤ 100000U，读库失败 fail-closed 拒开）；
   - 全过 → 写库(MM_RESTING_BUY) → placeOrder(BUY-LIMIT)，clientOrderId 幂等。

## 5. 配置参数

全部走 `application.yml` + env 覆盖，Java 里仅兜底默认，**权威值在配置文件**（与现有风控一致约定）。

```yaml
htx:
  market-maker:
    enabled: ${HTX_MM_ENABLED:false}            # 总开关，默认关（急停=false 重启）
    symbols: ${HTX_MM_SYMBOLS:btcusdt,ethusdt}  # 白名单
    poll-interval-ms: ${HTX_MM_POLL_INTERVAL_MS:10000}
    spread-pct: ${HTX_MM_SPREAD_PCT:0.005}      # δ=0.5%
    fee-rate-per-side: ${HTX_MM_FEE_RATE_PER_SIDE:0.002}  # 保守单边费率 f，用于不亏闸门(宁高勿低)
    per-cell-quote-notional: ${HTX_MM_PER_CELL_QUOTE_NOTIONAL:20}
    max-concurrent-cells-per-symbol: ${HTX_MM_MAX_CELLS:3}   # K
    min-gap-pct: ${HTX_MM_MIN_GAP_PCT:0.003}    # 新买价与已挂买价最小间距
    buy-ttl-ms: ${HTX_MM_BUY_TTL_MS:3600000}    # 买单 1h 未成交则撤
    insufficient-balance-backoff-ms: ${HTX_MM_INSUFFICIENT_BALANCE_BACKOFF_MS:300000} # USDT 不足暂停新买 5min
    sell-ttl-ms: ${HTX_MM_SELL_TTL_MS:0}        # 0=卖单不设 TTL，一直挂
```

约束：per-cell-quote-notional 天然应 < max-single-quote-notional(50)。spread-pct 应满足不亏闸门 `(1−f)²·(1+δ) ≥ (1−δ)`；低于时开仓被拒并记 WARN。

## 6. 持久化与重启恢复

复用 `htx_locked_pair` 表，新增：

- 状态值：`MM_RESTING_BUY / MM_RESTING_SELL / MM_DONE / MM_CANCELLED_BUY`（写入现有 `status` 列）。
- 新列 `strategy VARCHAR(16)`：做市单写 `MARKET_MAKER`，执行 SDK 单为 `NULL`。用于区分两类单，并驱动对账器豁免。

**重启恢复**：调度器启动先扫库 `WHERE strategy='MARKET_MAKER' AND status IN ('MM_RESTING_BUY','MM_RESTING_SELL')` → 逐个 `queryOrder` 对齐真实状态 → 重建内存 cell 状态机；同时复查最近 500 条带 `buy_order_id` 的 `MM_CANCELLED_BUY`，若 HTX 显示实际成交量大于 0，则按真实成本恢复为 `MM_RESTING_SELL` 并补挂限价卖单。绝不重复下单（clientOrderId 幂等：`$id-BUY` / `$id-SELL`）。

## 7. 与现有组件的冲突解决：对账器豁免

现有 `HtxExposureReconciler`（每 30s）扫「买腿成交、卖腿未成交」的单腿裸露并**自动市价平仓**。做市的 MM_RESTING_SELL 是故意持仓，若被当裸露市价砸掉，策略被打死。

**解决**：对账器的裸露查询加 `AND strategy IS NULL`（只管执行 SDK 单的裸露）。做市 cell 的「单腿」由调度器自己的状态机管理，不进对账器视野。

## 8. 风控与急停

- 现有 `htx.risk.max-single-quote-notional`(50) / `max-daily-quote-notional`(100000) **继续强制**：每开一个 cell 的买腿名义都过两道闸，日累计读库失败 fail-closed 拒开。
- `HtxDepthGuard` 继续用：开 cell 前查盘口，深度不足则跳过本轮。
- **急停**（`HTX_MM_ENABLED=false` 重启）：停止开新 cell；已 resting 单保留（不自动撤，避免砸盘），靠 buy-ttl 或人工了结。调度器 bean 通过 `@ConditionalOnProperty(prefix="htx.market-maker", name="enabled", havingValue="true")` 在关闭时不加载。

## 9. 文件结构

| 文件 | 职责 |
|---|---|
| `config/HtxMarketMakerProperties.java`（新） | `@ConfigurationProperties(prefix="htx.market-maker")` 绑定 §5 全部参数 |
| `strategy/MarketMakerCell.java`（新） | cell 内存模型：P0/buyPrice/sellPrice/qty/state/buyOrderId/sellOrderId/clientId/openedAt |
| `strategy/HtxMarketMakerScheduler.java`（新） | `@Scheduled` 主循环，`@ConditionalOnProperty(...enabled=true)`；委托 MarketMakerService |
| `strategy/MarketMakerService.java`（新） | 单标的一轮：查盘口→算价→depth guard→风控闸→placeOrder→写库；queryOrder 推进状态机；重启扫库恢复 |
| `service/HtxExposureReconciler.java`（改） | 裸露查询加 `AND strategy IS NULL` 豁免做市持仓 |
| `mapper/HtxLockedPairMapper`（改） | 新增按 strategy+status 查 open cell、按 symbol 统计 open cell 数的方法 |
| `db/schema.sql`（改） | `htx_locked_pair` 加 `strategy VARCHAR(16)` 列 |
| `application.yml`（改） | 加 `htx.market-maker` 配置块 |

## 10. 测试策略（TDD）

复用已修好的 surefire `slf4j-simple` 排除（模块本地）。

- **纯单测**：
  - 价格计算：由 P0+δ+notional 推出 buyPrice/sellPrice/qty 正确（含精度/四舍五入）。
  - 开仓闸门：K 上限拦截；min-gap 去重（新买价太近被拒）。
  - **不亏闸门**：`(1−f)²·sellPrice ≥ buyPrice` 边界——恰好相等应放行、略低应拒开；δ 相对 f 过小时全部被拒。
  - 状态机推进：买成交→挂卖；买超 buy-ttl→撤单；卖成交→DONE。
  - 余额闸门：USDT 不足不创建 DB cell，并在退避窗口内不重复查行情/下买单；余额查询失败 fail-closed。
  - 恢复补偿：已标记取消但 HTX 实际成交的买单，启动时按实际成本补挂限价卖单。
  - 白名单过滤：非白名单 symbol 不开 cell。
  - 风控闸集成：单笔超 50U / 日累计超 100000U → 拒开；读库失败 fail-closed。
- **Mock HTX 客户端**：placeOrder/queryOrder/cancelOrder 打桩，验证主循环与重启恢复逻辑不真下单也能跑通。
- **@SpringBootTest**：
  - enabled=false 时调度器 bean 不加载；
  - enabled=true + mock 客户端 + H2：验证一轮开仓正确落库（MM_RESTING_BUY + strategy=MARKET_MAKER）；
  - 对账器豁免：造一条 MM_RESTING_SELL，验证对账器不将其列为裸露。

## 11. 明确排除（YAGNI）

- 不做指标/信号策略（均线/RSI/突破）。
- 不做动态网格重铺、追价。
- 不做急停即全平（用户选只停开仓保留挂单）。
- 不改现有同步执行器与 locked-pair HTTP 接口。
- 卖单默认不设 TTL（sell-ttl=0），不做卖单超时止损（后续可加）。
