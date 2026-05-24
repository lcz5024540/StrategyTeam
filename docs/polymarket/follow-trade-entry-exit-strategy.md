---
title: "Polymarket 跟单 + 提前平仓策略体系"
type: strategy
status: active
tags: [polymarket, copy-trading, strategy]
created: 2026-05-06
updated: 2026-05-06
---

# Polymarket 跟单 + 提前平仓策略体系

> **TL;DR**：Polymarket 跟单的关键不是「谁胜率高就跟谁」，而是 *高质量 leader + 可复制行为 + 未被过度追价 + 有独立跟随资金 + 退出时仍有流动性* 五者同时成立。默认策略从「机械跟单 + 持有到结算」改为「行为信号短中期交易」—— 入场靠 `signal_score` 与追高过滤、仓位靠折扣 Kelly 与流动性容量、退出靠止盈止损 + 动量衰减 + leader 反向行为 + oracle 阶段强制降风险。leader 能否继续跟只信本系统真实/纸面跟单归因，不信静态 leaderboard 光环。完整论证见下文，落地优先级见第 13 章，核心结论见第 14 章。

## 1. 策略定位

本策略不是传统股票/期货的趋势跟随，而是 Polymarket 行为驱动交易：

```text
leader 成交事件 -> 判断该成交是否有可复制 alpha -> 延迟/分批入场 -> 动态持仓管理 -> 提前止盈止损 -> 临近 oracle 强制降风险
```

核心目标：

- 避免看到 leader 买入就机械追单。
- 过滤 leaderboard 幸存者偏差、假共识、多钱包刷单。
- 把二元预测市场的 0/1 结算风险前置到入场和退出。
- 优先赚 leader 行为造成的短中期价格迁移，不默认持有到结算。

适用数据：

- `smart_wallet_trade_event`：leader 成交价格、时间、数量、marketId、tokenId、side。
- `smart_wallet_profile` / 行为画像：wallet_score、近期胜率、回撤、followability、replicability。
- 市场价格流：mid/last/bestBid/bestAsk、1m/5m/15m 价格变化。
- 可选订单簿：touch depth、spread、撤单、盘口厚度变化。
- 已有跟单结果：follow task、fill、position、exitReason、realizedPnL。

## 2. 当前跟单系统核心缺陷

### 2.1 入场问题：容易追涨接盘

典型错误：

- leader 成交后价格已经跳涨，我们按市场价继续追，实际买在短期冲高末端。
- leader 是主动吃单者，但后续没有第二波资金，价格很快回落。
- 对 0.70 以上的 YES/NO 继续追单，赔率空间变小，止损空间反而变大。
- 临近结算市场中，盘口价格对消息极敏感，一旦方向错，价格可能直接向 0 收敛。

必须引入的判断：

- leader 成交价与当前可成交价的漂移。
- leader 成交后 30s/2m/5m 是否有持续跟随资金。
- 当前价格处于该市场近期分位数的哪一段。
- 我方预估成交滑点是否吃掉大部分 alpha。

### 2.2 信号质量问题：leader 未必有 alpha

高胜率地址可能来自：

- 只展示 closed winning positions 的样本偏差。
- 小样本高胜率。
- 多钱包刷共识。
- 只在低流动性市场用小额交易推动价格。
- leader 自己可以早退，但跟单系统延迟后已经不可复制。

leader 质量不能只看历史胜率，需要看：

- 未来可跟单收益，而不是历史结算胜率。
- leader trade 后 30s/5m/30m 的价格路径。
- leader 是否经常在高点买、低点卖。
- leader 的退出风格是否可复制。
- leader 的成交规模相对盘口深度是否可容量化。

### 2.3 持仓问题：不应默认持有到结算

Polymarket 是 0/1 结算，持有到结算的收益分布高度非线性：

- 买 0.60，结算赢只赚 0.40，结算输亏 0.60。
- 买 0.80，结算赢只赚 0.20，结算输亏 0.80。
- 越接近 oracle，错误方向的价格不是慢慢下跌，而是阶跃式归零。

因此跟单仓位默认应当是“事件后 alpha 捕捉仓”，不是“结算信仰仓”。只有当 market edge、leader quality、事件阶段和价格空间同时达标时，才允许持有到结算。

## 3. 总体策略分层

```text
Layer 1 Leader Admission    决定 leader 是否可监听
Layer 2 Signal Scoring      决定某笔 leader trade 是否值得跟
Layer 3 Entry Execution     决定何时、以什么价格、分几笔入场
Layer 4 Position Management 决定仓位大小、加仓、冻结
Layer 5 Exit Engine         止盈、止损、动量衰减、流动性撤退、强制退出
Layer 6 Attribution         用真实跟单结果反向更新 leader 和规则
```

## 4. Signal Layer：交易级信号评分

### 4.1 信号总分

每笔 leader trade 生成 `signal_score`，范围 `[0, 100]`：

```text
signal_score =
  25 * leader_quality_score
+ 20 * entry_timing_score
+ 15 * momentum_confirmation_score
+ 15 * follow_money_score
+ 15 * liquidity_score
+ 10 * event_stage_score
- penalty_score
```

入场分级：

| signal_score | 动作 |
|---:|---|
| >= 82 | 标准入场，可分批，允许后续加仓 |
| 72 - 81 | 小仓 canary，只允许一次入场 |
| 60 - 71 | paper 观察，不真实下单 |
| < 60 | 拒绝 |

生产初始建议：真实资金只跑 `>= 72`，`60 - 71` 全部 paper，用 200 笔以上样本回测后再调低。

### 4.2 leader_quality_score

```text
leader_quality_score =
  0.25 * wallet_score
+ 0.20 * recent_7d_30d_quality
+ 0.20 * replicability_score
+ 0.15 * behavior_stability_score
+ 0.10 * capacity_score
+ 0.10 * internal_follow_pnl_score
```

硬性拒绝：

- `wallet_score < 0.70`。
- `recent_trade_count_30d < 8` 且不是手动白名单。
- `recent_win_rate_7d < 0.45` 且 `recent_trade_count_7d >= 5`。
- `max_drawdown_rate > 0.45`。
- `replicability_score < 0.55`。
- `recommendedExitLane = FAST_EXIT_OR_SKIP`。
- 该 leader 最近 20 笔本系统 paper follow 的净收益 `< -20 USDC` 或胜率 `< 45%`。

高质量单钱包 override：

- `wallet_score >= 0.86`
- `replicability_score >= 0.70`
- `recent_trade_count_30d >= 15`
- `internal_follow_pnl_30d > 0`
- 且非可疑完美胜率样本。

### 4.3 entry_timing_score

用于避免追高。

关键指标：

```text
price_drift = current_ask - leader_fill_price
drift_pct = price_drift / leader_fill_price
market_5m_move = current_mid - mid_5m_ago
range_position_1h = (current_mid - low_1h) / (high_1h - low_1h)
spread_pct = (bestAsk - bestBid) / mid
```

评分规则：

| 条件 | 分数影响 |
|---|---:|
| `drift_pct <= 1.5%` | 满分 |
| `1.5% < drift_pct <= 3%` | -20% |
| `3% < drift_pct <= 5%` | -50% |
| `drift_pct > 5%` | 直接拒绝 |
| `range_position_1h > 0.85` 且 `market_5m_move > 6%` | 直接拒绝，疑似冲高接盘 |
| `spread_pct > 4%` | 直接拒绝，除非订单簿深度极好 |

价格带硬限制：

- 默认只跟 `0.18 <= price <= 0.78`。
- `price > 0.78` 只允许 `signal_score >= 88` 且剩余时间 > 72h。
- `price < 0.08` 禁止真实买入，通常是彩票仓或信息极弱。

### 4.4 momentum_confirmation_score

leader 买入后必须看到二次确认，而不是单笔孤立成交。

确认窗口：

- 快速市场：30s / 90s。
- 普通市场：2m / 5m。
- 长周期市场：5m / 15m。

正向确认：

- leader 后 2m 内，同方向主动买入金额 `>= leader_size * 0.6`。
- `mid_price` 没有回到 leader 成交价以下。
- 买方成交笔数 >= 3，且来自至少 2 个独立地址或实体。
- 5m VWAP 高于 leader 成交价，但当前价未超过 leader 成交价 5%。

拒绝条件：

- leader 后 90s 内价格回撤超过 `3%`。
- leader 后 5m 内无新增同方向买入，且盘口买一深度下降超过 `40%`。
- 出现高质量反向 leader 成交。

### 4.5 follow_money_score

衡量市场是否有真实跟随资金。

```text
follow_money_ratio = same_side_notional_after_leader_5m / leader_size_usdc
independent_entity_count = 去重实体后的同方向跟随者数量
```

分数：

- `follow_money_ratio >= 1.5` 且 `independent_entity_count >= 3`：满分。
- `follow_money_ratio >= 0.8` 且 `independent_entity_count >= 2`：合格。
- `follow_money_ratio < 0.4`：拒绝或 paper。
- 单一实体贡献超过 75%：降级，防多钱包伪共识。

### 4.6 liquidity_score

Polymarket 低流动性市场中，账面收益常被滑点吞掉。

硬性门槛：

- `spread_pct <= 3.5%`。
- 我方预计下单额不超过 near-touch depth 的 `20%`。
- 预估滑点 `< 1.5%` 或 `< 预期 alpha 的 30%`。
- 退出方向盘口深度足以在 3 笔内退出至少 `70%` 仓位。

拒绝：

- 买入会把价格推高超过 `4%`。
- best bid 撤单后，当前持仓预计只能以低于标记价 `5%+` 退出。
- 市场 5m 成交额小于我方目标仓位的 3 倍。

### 4.7 event_stage_score

见第 8 节。事件阶段决定同一个信号该不该跟，以及该持有多久。

## 5. Entry：入场策略

### 5.1 入场动作

默认不在 leader 成交事件到达后立即市价追单，改为二段式：

```text
T0: 收到 leader trade
T0 + 10s: 采集当前价格、盘口、同向成交
T0 + 30s/120s: 做 momentum confirmation
通过后：以 limit order 入场，价格上限 = min(currentAsk, leaderPrice * (1 + maxDrift))
```

建议参数：

| 市场类型 | 等待确认 | 最大追价 | 订单有效期 |
|---|---:|---:|---:|
| 快速事件 / 体育 | 10-30s | 1.5% | 10s |
| 普通事件 | 30-120s | 3.0% | 30s |
| 长周期政治/宏观 | 2-5m | 4.0% | 60s |

### 5.2 什么情况下跟单

同时满足：

- leader 准入通过。
- `signal_score >= 72`。
- `calibrated_follow_probability >= 0.54`，强共识可放宽到 0.52。
- `expected_net_ev_usdc > max(0.20, fee + slippage * 1.5)`。
- 当前可成交价未超过 leader 成交价上限。
- 市场未进入禁止开仓阶段。
- 没有同市场反向持仓或反向 pending task。

### 5.3 什么情况下不跟

硬拒绝：

- 价格从 leader 成交价已上涨 `> 5%`。
- 当前价位在 1h 区间顶部 `> 85%` 且 5m 涨幅 `> 6%`。
- leader 买入后没有跟随资金。
- 盘口 spread `> 4%`。
- 距 marketEnd/oracle `< 6h`，除非是平仓/对冲信号。
- leader 近期内部跟单结果恶化，进入 observe cooldown。
- 同一真实实体多钱包制造共识，`entity_independence_score < 0.45`。
- 单市场已有风险敞口超过组合预算 `15%`。

### 5.4 回撤入场

如果信号强但当前追价过高，不直接拒绝，可挂回撤单：

```text
if signal_score >= 82 and drift_pct in (3%, 5%]:
    limit_price = leader_fill_price * 1.02
    ttl = 3 minutes
    if not filled: cancel
```

回撤入场只适合普通/长周期市场，不适合临近 oracle 的快速市场。

## 6. Position：持仓管理

### 6.1 仓位大小

以折扣 Kelly 为基础：

```text
b = (1 - entry_price) / entry_price
kelly_raw = (p * b - (1 - p)) / b
stake = bankroll * kelly_raw * kelly_fraction
```

初始参数：

- `kelly_fraction = 0.25 - 0.30`。
- 单笔基础上限：账户权益 `2%`。
- 高质量强共识上限：账户权益 `4%`。
- 单 market/outcome 上限：账户权益 `6%`。
- 单 market 全方向上限：账户权益 `8%`。
- 单 leader 贡献的未平仓风险上限：账户权益 `10%`。

### 6.2 分批建仓

分批规则：

| 信号等级 | 第一笔 | 第二笔 | 第三笔 |
|---|---:|---:|---:|
| `signal_score >= 88` | 50% | 30% | 20% |
| `82 - 87` | 60% | 40% | 不开 |
| `72 - 81` | 100% canary 小仓 | 不加 | 不加 |

加仓条件：

- 当前仓位 ROI `>-3%`。
- 新增独立实体同向成交。
- 当前价不高于持仓均价 `+4%`。
- 同市场预算未触顶。
- 距 oracle > 24h。

禁止摊平：

- 仓位亏损超过 `-5%` 后，不因同一 leader 再次买入而加仓。
- 只有“不同高质量实体确认 + 价格未破关键低点”才允许重新评估，但默认不加。

### 6.3 持仓分类

入场时给每个仓位打 `holding_intent`：

| holding_intent | 条件 | 默认退出 |
|---|---|---|
| `SCALP_ALPHA` | 追随 leader 后短期资金流 | +8% 至 +12% 或动量衰减 |
| `EVENT_REPRICE` | 事件信息正在重定价 | +12% 至 +20%，最多持有 24-72h |
| `SETTLEMENT_EDGE` | 真实概率优势明显 | 可持有到结算前，但必须有 trailing stop |
| `PAPER_CANARY` | 观察型小仓 | +6% 或 -4%，不加仓 |

默认所有跟单仓位都是 `SCALP_ALPHA`，除非证据足够才升级。

## 7. 提前平仓策略

### 7.1 止盈规则

分层止盈：

| 仓位类型 | 第一止盈 | 第二止盈 | 尾仓 |
|---|---:|---:|---:|
| `PAPER_CANARY` | +6%，全平 | - | - |
| `SCALP_ALPHA` | +8%，平 50% | +12%，全平 | 无 |
| `EVENT_REPRICE` | +10%，平 40% | +18%，再平 40% | 20% trailing |
| `SETTLEMENT_EDGE` | +15%，平 30% | +25%，再平 30% | 40% 持有到强制退出 |

保护利润：

```text
if max_roi >= 10% and current_roi <= max_roi - 5%:
    exit 50% or all

if max_roi >= 18% and current_roi <= max_roi - 7%:
    exit all non-settlement-edge position
```

价格接近 0.90 后：

- 禁止新增。
- 若不是 `SETTLEMENT_EDGE`，至少平掉 70%。
- 因为上行空间最多 0.10，下行到 0 的尾部风险不对称。

### 7.2 止损规则

基础止损：

| 市场剩余时间 | 止损阈值 |
|---:|---:|
| > 7 天 | -12% |
| 24h - 7 天 | -8% |
| 6h - 24h | -5% |
| < 6h | -3% 或禁止新开 |

按仓位类型调整：

- `PAPER_CANARY`：-4%。
- `SCALP_ALPHA`：-6%。
- `EVENT_REPRICE`：-8%。
- `SETTLEMENT_EDGE`：-12%，但临近 oracle 自动收紧。

硬止损：

- 当前价格跌破 leader 成交价 `-4%`，且无新增同向资金：平 50%。
- 当前价格跌破入场价 `-8%`：全平。
- 高质量反向 leader 或 2 个独立实体反向买入：全平。

### 7.3 动量衰减退出

跟单收益主要来自后续资金流，资金流断掉就不应继续暴露。

触发条件：

- 入场后 10m 内无新增同向主动成交，且 ROI 未超过 +3%：平仓。
- 入场后 30m 内价格未创新高，且 follow_money_ratio < 0.5：平仓。
- 5m 动量从正转负，且当前 ROI > 0：落袋平仓。
- 连续 3 个扫描周期价格单调下跌，且 ROI < 0：趋势止损。

### 7.4 流动性撤退信号

任一触发则降风险：

- best bid depth 较入场下降 `> 50%`。
- spread 扩大到入场时 `2 倍` 或绝对值 `> 5%`。
- 退出侧可成交深度低于持仓数量的 `40%`。
- 大额卖单压在上方，ask depth / bid depth `> 3`。
- 同市场成交突然停止超过 15m，且距离 oracle < 24h。

动作：

- 有盈利：立即平 50%-100%。
- 小亏：挂限价退出 50%，若 2 个周期未成交，降价退出。
- 大亏且临近 oracle：不等盘口恢复，按止损规则退出。

### 7.5 Leader 行为退出

Polymarket 跟单必须监听 leader 的后续行为：

- 原 leader 卖出同 outcome：立即退出 50%，若价格跌破我方成本，全平。
- 原 leader 买入反向 outcome：全平。
- 其他更高质量 leader 反向买入，且共识实体数 >= 2：全平。
- leader 加仓但价格低于其上一笔买入价：不自动加仓，只作为持仓观察。

## 8. 事件阶段策略

### 8.1 早期：信息未定价

定义：

- 距离市场结束 > 7 天。
- 价格通常在 0.20 - 0.65。
- 消息尚未充分扩散，盘口可能较薄。

策略：

- 可跟高质量 leader，但要求流动性足够。
- 允许等待回撤入场。
- 持仓最长 72h，除非升级为 `SETTLEMENT_EDGE`。
- 止盈目标可放宽到 +15% / +20%。
- 止损不宜太窄，建议 -10% 至 -12%。

禁止：

- 只因单笔小额 leader 买入就开仓。
- 在低成交额市场超过 touch depth 20%。

### 8.2 中期：价格发现

定义：

- 距离结束 24h - 7 天。
- 多方信息开始汇聚，行为信号最有效。

策略：

- 这是跟单主战场。
- 要求 momentum confirmation 和 follow money。
- 默认 `SCALP_ALPHA` 或 `EVENT_REPRICE`。
- 止盈 +8% / +12% / +18% 分批。
- 止损 -6% 至 -8%。
- 若 30m 无持续资金，主动退出。

最佳入场：

- leader 买入后价格小幅上行但未冲顶。
- 2-3 个独立实体跟随。
- 盘口深度改善，spread 收窄。

### 8.3 后期：临近 oracle

定义：

- 距离结束 < 24h，尤其 < 6h。
- 价格对信息和 oracle 预期极敏感。

策略：

- 默认禁止新开仓。
- 只允许三类动作：
  - 平已有仓。
  - 对冲已有风险。
  - 极高置信度 `SETTLEMENT_EDGE` 小仓。
- 止损收紧到 -3% 至 -5%。
- 盈利仓强制降仓。

强制退出：

| 距 oracle | 动作 |
|---:|---|
| < 24h | 非 settlement edge 仓位至少平 50% |
| < 6h | 非 settlement edge 全平 |
| < 2h | settlement edge 也至少降到原始仓位 30% 以下 |
| oracle 不确定或流动性消失 | 全平，不等待结算 |

## 9. 完整策略流程

```text
1. Leader 下单
   -> 写入 smart_wallet_trade_event
   -> 识别 wallet/entity/market/outcome/side

2. Leader Admission
   -> 检查 wallet_score、近期表现、行为画像、内部跟单结果
   -> 不合格：observe/paper，不进入真实执行

3. Market Snapshot
   -> 拉取当前 bestBid/bestAsk/mid/spread/depth
   -> 计算 price drift、range_position、market stage

4. Signal Scoring
   -> 计算 leader_quality、entry_timing、momentum、follow_money、liquidity、event_stage
   -> signal_score < 72：拒绝或 paper

5. Entry Decision
   -> signal_score >= 82：标准分批入场
   -> 72 <= signal_score < 82：canary 小仓
   -> 追价过高：挂回撤单或拒绝

6. Position Management
   -> Kelly 折扣计算 stake
   -> 设置 holding_intent
   -> 记录 entry_price、max_roi、leader_ref、signal_score

7. Exit Engine
   -> 自动止盈
   -> 自动止损
   -> 动量衰减退出
   -> 流动性撤退
   -> leader 反向行为退出

8. Forced Exit
   -> 临近 oracle 按阶段强制降仓
   -> 非 settlement edge 不持有到结算

9. Attribution
   -> 按 leader、market、signal_score、entry_timing、exit_reason 归因
   -> 更新 leader admission 和参数
```

## 10. 伪代码

```pseudo
onLeaderTrade(event):
    leader = loadLeaderProfile(event.wallet)
    market = loadMarketState(event.marketId)

    if not passLeaderAdmission(leader):
        recordPaperOnly(event, reason="LEADER_ADMISSION_FAILED")
        return

    snapshot0 = loadOrderBookAndPrice(event.marketId, event.tokenId)
    timing = calcEntryTiming(event, snapshot0)

    if timing.driftPct > 0.05:
        reject(event, "CHASE_PRICE_TOO_FAR")
        return

    wait confirmationWindow(market.stage)
    snapshot1 = loadOrderBookAndPrice(event.marketId, event.tokenId)
    flow = calcFollowMoney(event, since=event.time)
    liquidity = calcLiquidity(snapshot1, targetStakeEstimate)

    score = calcSignalScore(leader, timing, flow, liquidity, market.stage)

    if score < 60:
        reject(event, "SIGNAL_SCORE_TOO_LOW")
        return
    if score < 72:
        recordPaperOnly(event, reason="PAPER_SCORE_BUCKET")
        return

    if not passEntryHardGates(event, snapshot1, market, flow):
        reject(event, "ENTRY_GATE_FAILED")
        return

    p = estimateCalibratedFollowProbability(event, leader, market, flow)
    if p < minProbability(score, flow.consensusEntities):
        reject(event, "FOLLOW_PROBABILITY_TOO_LOW")
        return

    stake = calcDiscountKellyStake(p, snapshot1.ask, bankroll)
    stake = applyPortfolioLimits(stake, leader, market, score)

    if score < 82:
        stake = min(stake, paperCanaryOrSmallLiveCap)
        intent = "PAPER_CANARY"
    else if isSettlementEdge(event, leader, market, p):
        intent = "SETTLEMENT_EDGE"
    else if flow.followMoneyRatio >= 1.0:
        intent = "EVENT_REPRICE"
    else:
        intent = "SCALP_ALPHA"

    maxEntryPrice = min(snapshot1.ask, event.price * (1 + maxDriftByStage(market.stage)))
    order = placeLimitBuy(event.tokenId, stake, maxEntryPrice, ttlByStage(market.stage))

    if order.filled:
        openPosition(order, intent, score, p, event)
    else:
        cancel(order)
```

```pseudo
monitorPosition(position):
    mark = loadCurrentMark(position.tokenId)
    roi = (mark.exitPrice - position.avgEntryPrice) / position.avgEntryPrice
    position.maxRoi = max(position.maxRoi, roi)
    stage = resolveEventStage(position.marketEndAt)
    flow = calcRecentFlow(position.marketId, position.outcome, window=5m)
    liquidity = calcExitLiquidity(position)
    leaderAction = loadRecentLeaderAction(position.leaderWallet, position.marketId)

    if leaderAction == "SELL_SAME_OUTCOME":
        exitPercent(position, 0.5, "LEADER_SELL")
        if roi <= 0:
            exitAll(position, "LEADER_SELL_AT_LOSS")
        return

    if leaderAction == "BUY_OPPOSITE_OUTCOME":
        exitAll(position, "LEADER_REVERSED")
        return

    if mustForceExitByOracle(stage, position.intent):
        exitAllOrReduce(position, "ORACLE_FORCE_EXIT")
        return

    if hitTakeProfit(position.intent, roi):
        exitByProfitTier(position, roi, "TAKE_PROFIT")
        return

    if position.maxRoi >= 0.10 and roi <= position.maxRoi - 0.05:
        exitAllOrHalf(position, "TRAILING_PROFIT_PROTECT")
        return

    stopLoss = stopLossThreshold(position.intent, stage)
    if roi <= -stopLoss:
        exitAll(position, "STOP_LOSS")
        return

    if flow.noSameSideMoneyFor(10m) and roi < 0.03:
        exitAll(position, "MOMENTUM_DECAY")
        return

    if liquidity.exitDepthRatio < 0.4 or liquidity.spreadPct > 0.05:
        reduceRisk(position, "LIQUIDITY_RETREAT")
        return
```

## 11. 初始参数建议

| 参数 | 建议值 |
|---|---:|
| min live `signal_score` | 72 |
| standard entry `signal_score` | 82 |
| min `wallet_score` | 0.70 |
| min `replicability_score` | 0.55 |
| min `calibrated_follow_probability` | 0.54 |
| strong consensus min probability | 0.52 |
| max price drift | 5% |
| normal max follow drift | 3% |
| max spread | 4% |
| max stake / bankroll single trade | 2% |
| high conviction max stake | 4% |
| market/outcome max exposure | 6% |
| Kelly fraction | 0.25 - 0.30 |
| scalp take profit | +8% / +12% |
| event reprice take profit | +10% / +18% |
| default stop loss | -6% to -8% |
| late stage stop loss | -3% to -5% |
| no-money decay exit | 10m - 30m |

## 12. 策略失效场景

### 12.1 leader alpha 失效

表现：

- leader 历史强，但近期交易不再推动价格。
- 市场参与者开始反向利用该 leader 的公开行为。

防御：

- 使用 7d/30d 滚动 follow PnL。
- leader 连续 5 笔 paper/live 负 alpha，进入 48h observe cooldown。
- admission 不看历史胜率单指标，必须看内部跟单结果。

### 12.2 假 leader / 多钱包伪共识

表现：

- 多个地址短时间同向买入，但链上/行为上属于同一实体。
- 小额推价后吸引跟单资金，再反向卖出。

防御：

- consensus 使用 deduped entity count，不用原始 wallet count。
- `top_entity_weight_share > 0.75` 降级。
- `entity_independence_score < 0.45` 拒绝。
- leader 买入后若无独立外部资金跟随，不入场。

### 12.3 流动性假象

表现：

- 入场时盘口看似有深度，准备退出时 bid 被撤。
- 账面盈利无法成交。

防御：

- 下单额不超过 near-touch depth 20%。
- 退出侧深度低于持仓 40% 立即降风险。
- spread 扩大到 5% 以上不等待止盈，优先退出。

### 12.4 临近 oracle 跳变

表现：

- 市场结果预期突然确定，价格从 0.40/0.60 快速收敛到 0/1。
- 止损单可能没有足够深度成交。

防御：

- `< 24h` 禁止普通新开仓。
- `< 6h` 非 settlement edge 全平。
- `< 2h` settlement edge 降到 30% 以下。
- oracle 信息不确定或盘口消失时强制平仓。

### 12.5 参数过拟合

表现：

- 在历史样本中效果很好，实盘因为延迟、滑点、盘口变化变差。

防御：

- 所有新规则先 paper/canary。
- 按 market category、leader、价格带、事件阶段分层归因。
- 不用单一总收益评价规则，必须看最大回撤、亏损尾部、退出可成交性。

## 13. 落地优先级

### Phase 1：低风险规则化

- 新增 `signal_score` 字段或在 follow task 决策中输出。
- 增加价格漂移、追高拒绝、range_position 拒绝。
- 把 `< 24h` 和 `< 6h` 阶段规则接入新开仓 gate。
- 增加 leader 反向行为退出。

### Phase 2：资金流确认

- 对 leader trade 后 30s/2m/5m 的同向成交打标签。
- 建 `follow_money_ratio`、`independent_entity_count`。
- 小于阈值的信号转 paper，不真实下单。

### Phase 3：流动性与退出强化

- 接订单簿 snapshot。
- 用 near-touch depth 控制 stake。
- 加入流动性撤退退出。

### Phase 4：归因闭环

- 按 `signal_score_bucket`、`entry_timing_bucket`、`leader`、`event_stage`、`exit_reason` 出报表。
- 每日更新 leader observe cooldown。
- 用内部 paper/live 结果替代 leaderboard 胜率作为主权重。

## 14. 核心结论

Polymarket 跟单的关键不是“谁胜率高就跟谁”，而是：

```text
高质量 leader + 可复制行为 + 未被过度追价 + 有独立跟随资金 + 退出时仍有流动性
```

默认策略应从“机械跟单并持有到结算”改为“行为信号短中期交易”：

- 入场靠 `signal_score` 和追高过滤。
- 仓位靠折扣 Kelly 和流动性容量。
- 退出靠止盈、止损、动量衰减、leader 反向行为和 oracle 阶段强制降风险。
- leader 是否继续可跟，只相信本系统真实/纸面跟单归因，不相信静态 leaderboard 光环。

---

<!-- kb-backlink -->
> 本文归属：[Polymarket 知识地图](../MOC-Polymarket.md) ｜ [知识库首页](../知识库首页.md)
