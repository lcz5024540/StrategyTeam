# HTX 做市 · 跟价重挂（方案 A）设计

> 目标：在**不自成交、不裸卖、始终 maker、不亏损**的前提下，通过 (1) 买腿定价贴最优买价、(2) 收窄 δ、(3) 买腿跟价重挂，提高真实成交（被交易池现有对手单吃掉）的频率。

**日期：** 2026-08-06
**模块：** `coin-strategy-htx`
**前置：** 现有做市策略（spec `2026-08-06-htx-market-maker-design.md`）已上线（`HTX_MM_ENABLED=true`，实盘）。本设计是增量增强，不重写。

---

## 背景与问题

现状做市按固定网格开单：

- `p0 = (bestBid + bestAsk) / 2`（盘口中价）
- `buyPrice = p0·(1−δ)`、`sellPrice = p0·(1+δ)`，δ=0.5%

买价被钉在中价下方 0.5%，**远离最优买价**，且价格漂移后订单原地不动。结果：买单挂满 1h TTL 仍不成交，自动撤单重开（日志 `MM buy expired & cancelled`），几乎无真实成交。

用户诉求：让交易池现有对手单来吃我们的挂单（真实成交，非自成交），接受手续费磨损，但**期望为正**（不做策略性亏损）。

## 不变量（硬约束，任何改动不得破坏）

1. **不亏损下限：** `(1−f)²·sellPrice ≥ buyPrice`，f=`feeRatePerSide`=0.002。
   - 由此推出 δ 硬下限 ≈ **0.20%**；低于此，成交即亏。
2. **先买后卖（现货约束）：** 不裸卖；卖腿库存必须来自已成交的买腿。
3. **始终 maker：** 买腿定价 ≤ bestBid、卖腿定价 ≥ bestAsk，绝不吃价穿价。
4. **急停语义不变：** kill-switch 只停「开新单」，不动已挂单（靠 TTL/人工）。
5. **单笔/当日名义额闸门不变。**

---

## §1 定价（买腿贴价、δ 收窄）

改动定价基准：买腿不再挂在中价下方，而是**贴最优买价**（排在买盘队首，等对手卖单来吃）。

- `buyPrice = bestBid`（maker，排队首）
- `sellPrice = buyPrice·(1 + 2δ)`，δ=0.003（0.3%）

> 卖价相对买价加 `2δ`：因买价从「中价下 δ」上移到「中价附近」，为保持买卖两腿总利差 ≈ 2δ 且满足不亏损，卖腿按买价上抬 2δ。δ=0.003 时 `2δ`=0.6% 名义利差，`(1−f)²` 磨损 ≈ 0.4%，净期望为正。

新增纯函数（`MarketMakerMath`）：

- `sellFromBuy(buyPrice, spreadPct) = buyPrice·(1 + 2·spreadPct)`，PRICE_SCALE=2，HALF_UP
- `shouldReprice(bestBid, currentBuy, thresholdPct) = |bestBid − currentBuy| / bestBid ≥ thresholdPct`

**启动校验：** 若 `spreadPct < feeRatePerSide`（δ<0.2%），拒绝启动并报明确错误（成交必亏）。

## §2 买腿跟价重挂（频率杠杆）

在 `advanceRestingBuy` 中，当买单仍在挂（未成交、未到 TTL）时，检查价格漂移：

```
若 shouldReprice(bestBid, cell.buyPrice, repriceThresholdPct):
    cancelOrder(buyOrderId)
    requery(buyOrderId)                      # 复用现有竞态处理
    若 requery 显示已成交(filled/partial):
        openSellLeg(cell, 已成交量)           # 撤单竞态中成交 → 直接开卖腿
        return
    newBuy  = bestBid
    newSell = sellFromBuy(newBuy, spreadPct)
    newBuyId = placeOrder(symbol, newBuy, qty, "buy-limit")
    recorder.markMmReprice(pairId, newBuyId, newBuy, newSell)   # 原地更新，同 pairId
    # createdAt 重置（重新计 TTL）
```

- **原地更新**：同一 `pairId`，不新增行；只换 `buy_order_id`、`buy_price`、`sell_price`、`created_at`，状态仍 `MM_RESTING_BUY`。
- 未漂移超阈值 → 不动（避免撤挂抖动、少交撤单费）。
- 默认 `reprice-threshold-pct = 0.001`（0.1%）。
- 买腿 TTL 从 1h 缩到 **15min**（`buy-ttl-ms=900000`）：跟价为主、TTL 为兜底。

## §3 卖腿（不变）

- 买腿成交 → `openSellLeg` 挂 `sell-limit` 于 `cell.sellPrice`。
- 卖腿只涨不跟降（`sell-ttl-ms=0`，一直挂到成交），保证不亏损下限。
- 卖腿**不做**跟价重挂（下调卖价会击穿不亏损下限）。

## §4 配置（`htx.market-maker`）

| 键 | 旧值 | 新值 | 说明 |
|---|---|---|---|
| `spread-pct` | 0.005 | **0.003** | δ；启动校验 δ≥`fee-rate-per-side` |
| `reprice-threshold-pct` | —（新增） | **0.001** | 买价漂移超此比例触发重挂 |
| `buy-ttl-ms` | 3600000 | **900000** | 买单兜底 TTL 15min |
| `sell-ttl-ms` | 0 | 0 | 不变 |
| `fee-rate-per-side` | 0.002 | 0.002 | 不变 |
| `per-cell-quote-notional` | 20 | 20 | 不变（< 单笔上限 50） |

`HtxMarketMakerProperties` 增字段 `repriceThresholdPct`（默认 0.001）。

## §5 持久层

新增 `HtxTradeRecorder.markMmReprice(pairId, newBuyId, newBuy, newSell)`：
`UPDATE htx_locked_pair SET buy_order_id=?, buy_price=?, sell_price=?, created_at=NOW() WHERE id=? AND status='MM_RESTING_BUY'`（状态保持 `MM_RESTING_BUY`）。

无 schema 变更（复用现有列）。上线后现存 `MM_RESTING_BUY` cell 会在下一轮自动跟价到当前盘口。

## §6 测试

**MarketMakerMathTest（纯函数）**
- `sellFromBuy`：buy=60000、δ=0.003 → 60360.00；scale/舍入固定。
- `shouldReprice`：边界——恰好等于阈值→true；略低→false；bestBid 上/下漂均覆盖。
- `isNoLoss`：δ=0.003 通过；δ=0.001 拒绝。

**MarketMakerServiceAdvanceTest（Mockito，行为）**
- 漂移超阈值：买单未成交 → 触发 `cancelOrder` + 新 `placeOrder(buy-limit)` + `markMmReprice`，同 pairId。
- 重挂竞态：撤单 requery 显示已成交 → 走 `openSellLeg`，不再重挂。
- 未超阈值：不 cancel、不 place、不 markMmReprice。
- 买单到 TTL（15min）：走原有过期撤单路径（回归，不被重挂逻辑吞掉）。

**回归**
- 全链路开单在 δ=0.003 通过既有开单测试（no-loss/min-gap/notional/depthGuard 闸门不变）。
- `HtxDepthParseTest`、`HtxMarketMakerSchedulerTest`、`HtxMarketMakerIntegrationTest` 全绿。

## §7 保持的不变量（回归清单）

- 先买后卖；无预置库存、无裸卖。
- 始终 maker（买≤bestBid、卖≥bestAsk），绝不穿价。
- 不亏损下限 `(1−f)²·sell ≥ buy` 恒成立（含重挂后的新买/卖对）。
- kill-switch 只停开新单。
- 单笔/当日名义额闸门不变。

---

## 落地顺序（交由 writing-plans 细化）

1. `MarketMakerMath`：`sellFromBuy`、`shouldReprice`（+ 单测）。
2. `HtxMarketMakerProperties` + `application.yml`：新键与启动校验。
3. `HtxTradeRecorder.markMmReprice`（+ mapper/SQL）。
4. `MarketMakerService`：`openNewCellIfEligible` 改贴价定价；`advanceRestingBuy` 加跟价重挂（+ 行为测试）。
5. 回归全绿 → 部署。
