# W2 — 风控门控 (T1-T6 共 11 个 Gate)

> ⏱️ **上次更新**: 2026-05-16 第十四批 | 回看主决议第四 / 十六 / 十八节
>
> **核心问题**：每个信号经过哪些风控门？什么时候拒绝 / 降级 / 加成？怎么灰度启用新门？

---

## 一、Gate 执行顺序（SmartWalletSignalRiskService 责任链）

```
G1   Manual blocklist
G2   Wallet archetype                     (OBSERVE_ONLY → 拒绝)
G2a  Wallet score / 流动性 / 价差          (基础硬阈值)
G2b  Leader 滑动窗口胜率 (W2-T2)          (COOLING → 降级)
G2c  Leader 行为漂移 (W2-T6)               (DRIFTED → 降级 / SEVERE → 拒绝)
G3-5 仓位 / 暴露 / 集中度
G6   Market lifecycle + maxHoursToMarketEnd
G7-9 EV / profitability / consensus (W2-T3)
G10  Stake (Kelly + 共识倍率)
G11  价格冲击三段式 (W2-T4)               (VWAP / PROXY bps)
G11  价差 P70 (W2-T1.4)                   (基于 daily 表的高分位)
G11  距结算时间 (W2-T5)                   (<30min 拒绝 / [30,120) 降级)
G11  其它 slippage 兜底
```

按顺序短路：任一 reject 即停。

---

## 二、各 Gate 详解

### W2-T1 流动性 P30 滚动门
- 数据源：`pm_market_liquidity_percentile_daily.liquidity_p30`（每日 02:30 snapshot）
- 当 category 滚动 P30 已计算 → 门槛 = P30；否则 fallback `properties.minMarketLiquidity` 硬阈值
- 配置：`signal-risk.enable-liquidity-rolling-percentile`（**prod=true**, test 默认 false）

### W2-T1.4 价差 P70 门
- 数据源：`pm_market_liquidity_percentile_daily.spread_bps_p70`
- 三档：
  - `spread ≤ P70` → 全仓
  - `(P70, P70 × downgradeFactor]` → 仓位 × downgradeMultiplier (默认 0.5)
  - `> P70 × rejectFactor` → 拒绝
- 配置：`signal-risk.enable-spread-p70-gate`（**prod=true**, test 默认 false）

### W2-T2 Leader 滑动窗口胜率门
- 数据源：`smart_wallet_order(BUY+ENTRY) ⨝ smart_wallet_position(已关闭)` 最近 N 笔
- 三态：ACTIVE / COOLING（仓位 ×0.05）/ SUSPENDED
- 配置：`signal-risk.enable-leader-rolling-gate=false`（**默认关闭**，待 W1-T4 数据验证）

### W2-T3 共识强度（BOOST_ONLY）
- `EntityAwareConsensusService` 输出多实体共识 → Kelly 仓位倍率
- 2 实体 ×1.20 / 3+ 实体 ×1.50
- 配置：`consensus-gate-mode=BOOST_ONLY`（默认；可切 HARD_GATE）

### W2-T4 价格冲击三段式 + MAKER 路由
- bps source：**VWAP**（`MarketPriceImpactEstimator`，基于 market_snapshot 订单簿深度逐档 VWAP）/ **PROXY**（既有 `profitabilityEstimate.estimatedSlippageUsdc/stake`）
- 三段式：
  - `< 100 bps` → 全仓 NORMAL
  - `[100, 300) bps` → 仓位 ×0.5 + MAKER_FIRST
  - `[300, 500) bps` → 仓位 ×0.25 + MAKER_ONLY (10s 超时撤单)
  - `≥ 500 bps` → 拒绝
- **MAKER_ONLY 超时撤单**：`MakerOnlyOrderTimeoutTracker`（fixedDelay 2s scan，超时 cancelOrder）
- 配置：
  - `signal-risk.enable-price-impact-gate=true`（默认）
  - `signal-risk.enable-vwap-slippage-estimator`（**prod=true / test=true**）
  - `signal-risk.maker-only-timeout-tracker-enabled`（**prod=true / test=true**）

### W2-T5 距结算时间门
- 三档：
  - `< 30 min` → 拒绝
  - `[30, 120) min` → 仓位 ×0.5
  - `≥ 120 min` → 全仓
- 不影响已有仓位退出，只针对新开仓
- 豁免列表 `time-to-expiry-exempt-market-types`（天气 / BTC 5min 上线时配置）

### W2-T6 Leader 行为漂移门
- 数据源：`smart_wallet_behavior_profile.behavior_vector_json` 优先（方案 B），fallback 实时聚合最近 N 天 trade_event
- 7 维特征：`[log10(size+1)/6, sideBuy, cat_POLITICS, cat_CRYPTO, cat_SPORTS, cat_WEATHER, cat_OTHER]`
- cosine 距离三态：ACTIVE / DRIFTED（×0.25）/ DRIFTED_SEVERE（拒绝）
- 配置（test / prod 不同阈值）：
  - test: `min-samples=15 / cosine=0.50 / severe=0.70`
  - prod: `min-samples=20 / cosine=0.55 / severe=0.75`

---

## 三、灰度启用顺序（按风险）

1. **默认开**：W2-T3 (consensus) / W2-T4 (priceImpact) / W2-T5 (time-to-expiry) — 已稳定
2. **prod 开 / test 验证**：W2-T1 / W2-T1.4 / W2-T6 — 已上线 prod yml
3. **默认关**：W2-T2 leader-rolling — 待 W1-T4 影子记账数据验证 1-2 周后再启用

每个门都有 `enable-xxx-gate` 配置开关，回退 = 一行配置零重启。

---

## 四、Hot path 数据流（一个信号经过 G11 的完整链路）

```
SmartWalletFollowTaskService.createFollowTaskIfQualified(event)
    ↓
SmartWalletSignalRiskService.evaluate(profile, event)
    ↓
对每个 Gate 逐一调用 (G1..G11)
    ↓
G11 priceImpact 三段式 (示例):
    ├─→ if (enableVwapSlippageEstimator) {
    │       estimate = MarketPriceImpactEstimator.estimate(event, stake);
    │       if (estimate.ok) bps = estimate.slippageBps; bpsSource = "VWAP(OK)";
    │   }
    ├─→ else {
    │       bps = profitabilityEstimate.slippageUsdc / stake * 10000;
    │       bpsSource = "PROXY";
    │   }
    ├─→ 三段式判定: bps → REJECTED / SEVERE_DOWNGRADE / DOWNGRADE / OK
    ├─→ 设置 decision.priceImpactExecutionMode (NORMAL/MAKER_FIRST/MAKER_ONLY/REJECTED)
    └─→ 设置 decision.priceImpactBpsSource (第十四批 #1: 落 shadow 表)
    ↓
SignalRiskDecision (含完整 reasons + priceImpactBpsSource)
    ↓
SmartWalletFollowTaskService.createFollowTaskIfQualified
    ├─→ 写 follow_task
    └─→ recordSignalShadowIfRejected → SignalShadowRecorder
              ↓ 透传 decision.priceImpactBpsSource → entity.entryEstimatorSource
        INSERT INTO smart_wallet_signal_shadow
    ↓
若 accepted:
    LoggingPolymarketExecutionGateway.execute
        ↓ LIVE 成功后
    MakerOnlyOrderTimeoutTracker.register (if MAKER_ONLY)
```

---

## 五、配置开关速查（按 enable-* 字段）

详见 [audit-observability-index.md 一节末尾 "配置开关速查"](../audit-observability-index.md#四按-enable--字段)。

---

## 六、灰度数据看板

测试启用一个新门后 1-2 周关注：

```sql
-- 某 Gate 的命中率 (示例: W2-T6)
SELECT decision_status, COUNT(*) cnt
  FROM smart_wallet_signal_shadow
 WHERE reject_layer LIKE 'G2c_LEADER_DRIFT%'
   AND created_at >= NOW() - INTERVAL 7 DAY
 GROUP BY decision_status;

-- W2-T4 三段式触发分布
SELECT
    CASE
        WHEN reject_layer LIKE 'G11_PRICE_IMPACT_REJECT%' THEN 'REJECT'
        WHEN reject_layer LIKE 'G11_PRICE_IMPACT_SEVERE%' THEN 'SEVERE'
        WHEN reject_layer LIKE 'G11_PRICE_IMPACT_DOWNGRADE%' THEN 'DOWNGRADE'
        ELSE 'OK'
    END seg,
    COUNT(*) cnt
  FROM smart_wallet_signal_shadow
 WHERE created_at >= NOW() - INTERVAL 7 DAY
 GROUP BY seg;
```

Web 端可看：
- `/leader-rolling/{wallet}` — 单 leader 滑动胜率
- `/leader-drift/{wallet}` — 单 leader 行为漂移
- `/market-liquidity/percentile` — 当日各品类 P30/P70
- `/audit/slippage-postfact` — 滑点事后统计
