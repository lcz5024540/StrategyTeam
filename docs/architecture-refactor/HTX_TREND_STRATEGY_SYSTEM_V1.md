---
title: "HTX 趋势策略系统 v1 · 重构设计"
type: design
status: draft
created: 2026-09-21
updated: 2026-09-22
tags: [htx, trend, strategy, futures, crypto, backtest]
---

# HTX 趋势策略系统 v1 · 重构设计

> **上层总纲**：本设计隶属于 [CoinStrategy 策略总纲 v1](COINSTRATEGY_STRATEGY_MASTER_PLAN_V1.md) 的趋势策略子系统。

> **实施路线**：趋势子系统落地顺序见 [CoinStrategy 策略体系实施路线图 v1](COINSTRATEGY_STRATEGY_IMPLEMENTATION_ROADMAP_V1.md) 的 P1–P4。

> **执行重构计划**：开仓链路、已有仓位管理、冷却解冻、多周期和外部仓位处理见 [HTX 趋势策略开仓与持仓管理重构计划 v1](HTX_TREND_EXECUTION_REFACTOR_PLAN_V1.md)。

> **结论先行**：HTX 趋势策略必须从“单指标补丁式开仓”重构为“市场分型 + 多模型信号 + 因子评分 + 统一 gate + 模型匹配退出 + 回测/实盘闭环”的系统。第一版只做合约趋势交易，不做资金费套利；资金费只作为成本与拥挤度因子。

---

## 0. 文档定位

| 项 | 内容 |
|---|---|
| 文档类型 | 趋势策略系统设计 / 后续研发实施依据 |
| 目标读者 | 策略研发、后端、前端、运维、实盘观察人员 |
| 适用模块 | `coin-strategy-htx` 趋势合约交易、HTX 趋势看板、回测工具 |
| 非目标 | 不设计现货网格；不恢复资金费套利为主策略；不承诺固定收益 |
| 当前线上问题 | 2026-09-21 日内趋势单 10 笔，扣费后 5 胜 5 负，净亏约 -2.064878U；亏损集中在 Fibonacci 回踩单和 no-extension/adverse-shock 退出 |

---

## 1. 总方向

HTX 趋势策略 v1 定位为 **市场分型驱动的多模型趋势跟随系统**。

系统不再让某一个指标直接触发实盘开仓。任何真实仓位必须经过下面链路：

```text
市场分型
  → 单币结构识别
  → 多模型信号生成
  → 因子评分
  → 开仓 gate
  → 仓位 sizing
  → 模型匹配退出
  → 回测验证
  → 实盘观察闭环
```

### 1.1 设计原则

| 编号 | 原则 | 说明 |
|---|---|---|
| P-1 | 先分型，再开仓 | 先判断市场是否适合趋势交易，再判断单币是否值得交易。 |
| P-2 | 模型独立产出候选 | 箱体突破、Donchian、Fibonacci、多周期动量分别产出候选，不互相污染。 |
| P-3 | 因子可追溯 | 每次候选、开仓、平仓都要能回放当时因子值。 |
| P-4 | gate 和 score 分离 | 分数决定排序与仓位，硬 gate 决定能不能开。 |
| P-5 | 退出匹配开仓假设 | 用趋势逻辑开仓，不能用超短线 no-extension 一刀切退出。 |
| P-6 | 扣费后收益为第一口径 | 胜率、盈亏、profit factor、回测指标均以扣费后净值为准。 |

---

## 2. 市场分型

市场分型只输出环境，不直接下单。它控制方向、仓位和是否允许新开仓。

### 2.1 分型结果

| 分型 | 定义 | 策略行为 |
|---|---|---|
| `BROAD_UP` | BTC/ETH 多周期偏多，且市场上涨宽度达到阈值 | 允许多单；空单只允许极高分且降仓。 |
| `BROAD_DOWN` | BTC/ETH 多周期偏空，且市场下跌宽度达到阈值 | 允许空单；多单只允许极高分且降仓。 |
| `MIXED` | 主流与山寨分化，或上涨/下跌宽度不足 | 只做高分模型，降低仓位。 |
| `CHOP` | 多数币无趋势，箱体震荡、ADX/ATR 低 | 禁止新开趋势仓，只管理已有仓位。 |
| `HIGH_VOL_RISK` | 市场 ATR 异常扩大，容易扫损 | 暂停新开仓；已有仓位进入保护模式。 |
| `LOW_VOL_DEAD` | 波动太小，预期收益覆盖不了手续费和滑点 | 禁止新开仓。 |

### 2.2 市场分型因子

| 因子 | 计算口径 | 用途 |
|---|---|---|
| `btc_eth_anchor` | BTC/ETH 在 15m/30m/60m/4h 的方向投票 | 判断主流趋势。 |
| `breadth_long_ratio` | 白名单币中多头结构占比 | 判断是否普涨。 |
| `breadth_short_ratio` | 白名单币中空头结构占比 | 判断是否普跌。 |
| `market_avg_atr_pct` | 白名单币 ATR% 均值/分位数 | 判断波动是否可交易。 |
| `market_volume_expansion` | 当前成交额相对均量 | 判断趋势是否有资金推动。 |
| `dispersion_score` | 币种表现离散度 | 区分普涨与少数币独涨。 |
| `funding_skew` | 资金费率分布 | 仅判断拥挤与持仓成本，不直接开仓。 |

### 2.3 分型阈值初版

| 参数 | 建议值 | 说明 |
|---|---:|---|
| `anchor_min_aligned_symbols` | 2 | BTC 与 ETH 都要参与主方向。 |
| `anchor_min_aligned_periods_per_symbol` | 2 | 每个主流币至少 2 个周期同向。 |
| `breadth_trend_threshold` | 0.55 | 第一版不使用 70% 作为唯一阈值，避免错过局部趋势。 |
| `breadth_strong_threshold` | 0.70 | 超过后可轻微放大顺风仓位。 |
| `opposite_breadth_max` | 0.20 | 反向宽度高时禁止顺风放大。 |
| `high_vol_percentile` | P90 | 市场波动超过近 30 天 P90 进入风险态。 |
| `low_vol_percentile` | P20 | 市场波动低于近 30 天 P20 且成本覆盖不足时不开。 |

---

## 3. 量化因子列表

因子分为硬门槛因子、方向因子、结构因子、入场因子、成本因子、风险因子。第一版要求所有候选因子入库。

### 3.1 硬门槛因子

| 因子 | 说明 | gate 用法 |
|---|---|---|
| `spread_rate` | 当前盘口买卖价差 | 大于阈值拒绝。 |
| `depth_ratio` | 前 N 档深度 / 计划下单量 | 小于阈值拒绝。 |
| `avg_turnover_usdt` | 最近 N 根 K 成交额均值 | 小于阈值拒绝。 |
| `expected_net_after_fee` | 预期波动扣费扣滑点后的收益 | 小于 0 或小于最小净收益拒绝。 |
| `signal_age_seconds` | 信号距当前时间 | 超时拒绝。 |
| `higher_tf_conflict` | 30m/60m 是否明确反向 | 明确反向拒绝。 |
| `market_regime_blocked` | 市场是否 CHOP/HIGH_VOL_RISK/LOW_VOL_DEAD | 被阻断时拒绝。 |
| `invalidation_price` | 结构失效价是否可计算 | 无法计算止损价拒绝。 |
| `risk_reward_ratio` | 预期收益 / 风险 | 小于阈值拒绝。 |

### 3.2 方向因子

| 因子 | 说明 |
|---|---|
| `roc_15m` / `roc_30m` / `roc_60m` | 多周期收益率。 |
| `ema_fast_above_slow_15m/30m/60m` | EMA 多空方向。 |
| `ema_slope_15m/30m/60m` | EMA 斜率，避免横盘均线缠绕。 |
| `multi_tf_alignment_count` | 15m/30m/60m 同向数量。 |
| `anchor_alignment` | 是否顺 BTC/ETH 多周期锚点。 |
| `breadth_alignment` | 是否顺市场宽度。 |

### 3.3 结构因子

| 因子 | 说明 |
|---|---|
| `box_high` / `box_low` | 箱体上下沿。 |
| `box_bars` | 箱体持续 K 数。 |
| `box_width_atr` | 箱体宽度 / ATR。 |
| `breakout_strength_atr` | 突破幅度 / ATR。 |
| `breakout_close_quality` | 收盘是否站稳箱体外。 |
| `swing_high` / `swing_low` | 有效波段高低点。 |
| `hh_hl_structure` / `lh_ll_structure` | 高低点结构。 |
| `retest_success` | 突破后回踩是否成功。 |
| `false_breakout_risk` | 是否刚跌回箱体/上影线过长。 |

### 3.4 Fibonacci 因子

| 因子 | 说明 |
|---|---|
| `fib_source_type` | Fibonacci 来源：箱体突破、趋势波段、无效。 |
| `fib_swing_low` / `fib_swing_high` | 拉线起点和终点。 |
| `fib_retrace_rate` | 当前回撤比例。 |
| `fib_zone` | 0.382–0.618 / 0.618–0.786 / 失效。 |
| `fib_reclaim_signal` | 回踩后是否重新转强。 |
| `fib_invalidation_price` | 回踩失效价。 |
| `fib_box_relation` | 是否跌回箱体、是否守住箱体上沿/下沿。 |

### 3.5 量价与波动因子

| 因子 | 说明 |
|---|---|
| `atr_pct` | 波动空间。 |
| `adx` | 趋势强度。 |
| `volume_ratio` | 成交量 / 均量。 |
| `turnover_ratio` | 成交额 / 均额。 |
| `close_position_in_candle` | 收盘在 K 线高低区间的位置。 |
| `upper_wick_ratio` / `lower_wick_ratio` | 上下影线风险。 |
| `distance_from_ema` | 是否追高/追空。 |
| `volatility_expansion` | 波动是否扩张。 |
| `chop_score` | 震荡风险。 |

### 3.6 成本与风险因子

| 因子 | 说明 |
|---|---|
| `account_fee_rate` | 账户级手续费率。 |
| `spread_cost` | 点差成本。 |
| `slippage_estimate` | 滑点估算。 |
| `funding_cost_or_income` | 持仓方向对应资金费成本/收益。 |
| `recent_model_win_rate` | 当前模型近期胜率。 |
| `recent_model_profit_factor` | 当前模型近期 profit factor。 |
| `symbol_cooldown_state` | 当前币冷却状态。 |
| `correlation_exposure` | 同方向相关币风险。 |

---

## 4. 信号模型

第一版采用四类模型，前三类可进入真实开仓，Donchian 与箱体突破合并评审后决定是否单独拆出。

### 4.1 箱体突破模型 `BOX_BREAKOUT`

| 项 | 内容 |
|---|---|
| 适合行情 | 横盘收敛后放量启动。 |
| 多头信号 | 价格突破箱体上沿，收盘站稳，成交量放大，30m/60m 不反向。 |
| 空头信号 | 价格跌破箱体下沿，收盘站稳，成交量放大，30m/60m 不反向。 |
| 失效条件 | 跌回箱体、突破 K 低点被破、成交量衰竭。 |
| 主要风险 | 假突破。 |

评分：

| 因子 | 权重 |
|---|---:|
| 箱体质量 | 20 |
| 突破强度 | 25 |
| 成交量确认 | 15 |
| 多周期方向 | 20 |
| 市场分型 | 10 |
| 成本和流动性 | 10 |

候选阈值：`>= 75`。真实开仓阈值：`>= 85`。

### 4.2 Donchian / 海龟突破模型 `DONCHIAN_BREAKOUT`

| 项 | 内容 |
|---|---|
| 适合行情 | 强趋势刚启动或持续加速。 |
| 多头信号 | close > 最近 N 根最高价，EMA/ADX/ATR 支持。 |
| 空头信号 | close < 最近 N 根最低价，EMA/ADX/ATR 支持。 |
| 失效条件 | 反向通道突破、ATR trailing stop。 |
| 主要风险 | 震荡市频繁假突破。 |

评分：

| 因子 | 权重 |
|---|---:|
| 通道突破强度 | 25 |
| ADX / ATR 趋势质量 | 20 |
| 多周期方向 | 20 |
| 成交量确认 | 10 |
| 市场顺风 | 15 |
| 成本和流动性 | 10 |

真实开仓阈值：`>= 80`。若市场为 `CHOP`，直接拒绝。

### 4.3 Fibonacci 回踩延续模型 `FIB_PULLBACK_CONTINUATION`

| 项 | 内容 |
|---|---|
| 适合行情 | 趋势已成立后的中继回踩。 |
| 多头信号 | 上涨结构成立，回踩 0.382–0.618，守住箱体/前高/EMA，15m 重新转强。 |
| 空头信号 | 下跌结构成立，反弹 0.382–0.618，未站回箱体/前低，15m 重新转弱。 |
| 失效条件 | 跌破回踩低点、跌破 0.786、跌回箱体、30m/60m 转弱。 |
| 主要风险 | 把下跌中继误判为上涨回踩。 |

评分：

| 因子 | 权重 |
|---|---:|
| 上级趋势确认 | 20 |
| swing / 箱体结构有效 | 20 |
| Fibonacci 位置 | 15 |
| 回踩后重新转强 | 20 |
| 未破结构 | 15 |
| 成本和流动性 | 10 |

真实开仓阈值：`>= 85`。

硬约束：

```text
localMomentum- 不允许直接开仓。
必须有 fib_reclaim_signal=true。
必须能计算 fib_invalidation_price。
30m/60m 任一明确反向时拒绝。
```

### 4.4 多周期动量模型 `MULTI_TIMEFRAME_MOMENTUM`

| 项 | 内容 |
|---|---|
| 适合行情 | 小趋势、主升/主跌延续，不一定等大突破。 |
| 多头信号 | 15m/30m/60m 至少两个周期同向，ROC/EMA 斜率/成交量支持。 |
| 空头信号 | 15m/30m/60m 至少两个周期同向向下，ROC/EMA 斜率/成交量支持。 |
| 失效条件 | 15m/30m 同时转弱，或 60m 趋势破坏。 |
| 主要风险 | 追在短线末端。 |

评分：

| 因子 | 权重 |
|---|---:|
| 15m/30m/60m 同向 | 30 |
| EMA 斜率 | 15 |
| ROC 动量 | 15 |
| 成交量确认 | 15 |
| 市场分型 | 15 |
| 成本和流动性 | 10 |

真实开仓阈值：`>= 80`。若价格远离 EMA 超过阈值，降级为观察候选。

---

## 5. 开仓 gate

开仓 gate 是硬条件，任何模型都必须通过。

### 5.1 通用 gate

| 编号 | Gate | 拒绝条件 |
|---|---|---|
| G-1 | 实盘开关 | `entryEnabled=false` 或 `observeOnly=true`。 |
| G-2 | 仓位上限 | open-like positions >= `maxActivePositions`。 |
| G-3 | 单币冲突 | 当前币已有策略仓位或外部仓位。 |
| G-4 | 信号新鲜度 | `signal_age_seconds > maxEntrySignalAgeSeconds`。 |
| G-5 | 市场分型 | `CHOP` / `HIGH_VOL_RISK` / `LOW_VOL_DEAD`。 |
| G-6 | 高周期冲突 | 30m/60m 明确反向。 |
| G-7 | 点差 | spread 大于阈值。 |
| G-8 | 深度 | 前 N 档深度不足。 |
| G-9 | 成交额 | 最近 N 根成交额低于阈值。 |
| G-10 | 成本覆盖 | expected net after fee/slippage 不达标。 |
| G-11 | 止损可计算 | 无 invalidation price。 |
| G-12 | 盈亏比 | risk reward 小于阈值。 |

### 5.2 模型专属 gate

| 模型 | Gate |
|---|---|
| `BOX_BREAKOUT` | 不能刚突破又跌回箱体；突破强度必须超过最小 ATR。 |
| `DONCHIAN_BREAKOUT` | 不能在过热位置追单；ADX/ATR 必须支持趋势。 |
| `FIB_PULLBACK_CONTINUATION` | 必须有有效 swing/箱体来源，必须重新转强。 |
| `MULTI_TIMEFRAME_MOMENTUM` | 至少两个周期同向，不能 15m 单独决定。 |

---

## 6. 仓位 sizing

仓位由风险决定，不由“想成交量大”决定。

### 6.1 基础公式

```text
risk_usdt = account_equity * risk_per_trade_pct
stop_distance = abs(entry_price - invalidation_price)
base_quantity = risk_usdt / stop_distance
notional = base_quantity * entry_price
```

再经过交易所合约面值、最小张数、最大名义额和账户保证金约束。

### 6.2 仓位乘数

| 条件 | 乘数 |
|---|---:|
| 市场分型顺风，且 breadth >= 70% | 1.20 |
| 市场分型顺风，breadth 55%–70% | 1.00 |
| `MIXED` | 0.50 |
| `CHOP` / `HIGH_VOL_RISK` / `LOW_VOL_DEAD` | 0 |
| 模型分数 85–90 | 1.00 |
| 模型分数 >= 90 | 1.20 |
| 最近模型 profit factor < 1.1 | 0.50 |
| 同方向相关暴露过高 | 0.50 或拒绝 |

### 6.3 第一版风险建议

| 参数 | 建议值 |
|---|---:|
| 单笔风险 | 账户权益 0.5%–1.0% |
| 最大同时仓位 | 3 |
| 单方向总风险 | 账户权益 2.0% 以内 |
| 单模型总风险 | 账户权益 1.5% 以内 |
| 单币冷却 | 亏损后至少 1 小时，盈利后按趋势强度缩短 |

---

## 7. 退出逻辑

退出不再一刀切。每笔仓位必须记录 `entry_model`、`entry_hypothesis`、`invalidation_price`、`factor_snapshot_id`。

### 7.1 模型匹配退出

| 开仓模型 | 失效退出 |
|---|---|
| `BOX_BREAKOUT` | 收盘跌回箱体；跌破突破 K 低点；放量反向。 |
| `DONCHIAN_BREAKOUT` | 反向通道突破；ATR trailing stop。 |
| `FIB_PULLBACK_CONTINUATION` | 跌破回踩低点；跌破 0.786；跌回箱体；30m/60m 转弱。 |
| `MULTI_TIMEFRAME_MOMENTUM` | 15m/30m 同时转弱；60m 趋势破坏；EMA 斜率反转。 |

### 7.2 通用退出

| 退出 | 规则 |
|---|---|
| 最大亏损退出 | 价格触达 invalidation price。 |
| ATR trailing stop | 随趋势推进，不早于结构确认。 |
| 扣费后止盈保护 | 扣费后达到最小利润后，止损至少推到净保本。 |
| 时间失效退出 | 只适用于动量模型；Fibonacci/箱体模型要看结构是否失效。 |
| 极端波动退出 | 异常波动且价格打穿结构时退出。 |

### 7.3 `no-extension` 重写方向

当前线上 `no-extension` 的问题：趋势逻辑开仓后，2 根 15m 没有足够浮盈就退出，容易卖在趋势回踩低点。

新规则：

```text
BOX_BREAKOUT：若 2–3 根 K 后跌回箱体，退出；否则不因没有立刻浮盈退出。
FIB_PULLBACK：若没有跌破回踩低点/0.786/箱体边界，不因 no-extension 退出。
MULTI_TIMEFRAME_MOMENTUM：允许 no-extension，但要求 15m/30m 同时转弱。
DONCHIAN：使用 ATR trailing，不使用短 no-extension。
```

---

## 8. 回测验证指标

回测必须按模型、币种、市场分型拆开。总收益为正但某个模型长期亏损时，不能整体掩盖。

| 指标 | 说明 |
|---|---|
| `net_pnl_after_fee` | 第一指标，扣手续费和滑点。 |
| `win_rate_after_fee` | 扣费后胜率。 |
| `profit_factor_after_fee` | 总盈利 / 总亏损。 |
| `expectancy_per_trade` | 单笔期望收益。 |
| `max_drawdown` | 最大回撤。 |
| `mfe` | 最大有利浮盈，用于判断是否过早止盈。 |
| `mae` | 最大不利浮亏，用于判断止损是否合理。 |
| `avg_hold_minutes` | 平均持仓时间。 |
| `fee_to_gross_profit_ratio` | 手续费占毛利润比例。 |
| `model_pnl` | 各模型 PnL。 |
| `regime_pnl` | 各市场分型 PnL。 |
| `symbol_pnl` | 各币种 PnL。 |
| `gate_reject_distribution` | gate 拦截分布。 |
| `signal_to_fill_slippage` | 信号价到成交价滑点。 |

### 8.1 回测验收门槛

| 指标 | 最低验收 |
|---|---:|
| 扣费后 profit factor | > 1.15 |
| 扣费后胜率 | > 50% 或盈亏比显著大于 1 |
| 单日最大回撤 | 小于预设日风险预算 |
| 手续费占毛利润 | < 35% |
| 单模型连续亏损 | 达阈值后自动降权或禁用 |
| 9/18 好行情回测 | 应能捕捉主要上涨段，不能完全不开仓 |
| 9/19 差行情回测 | 应显著减少错误开仓，亏损可控 |
| 9/20/9/21 最近行情 | 应解释真实订单亏损原因并复现主要决策 |

---

## 9. 实盘观察指标

页面和数据库必须能回答“为什么开、为什么不开、为什么平、哪里亏”。

### 9.1 顶层驾驶舱

| 区块 | 指标 |
|---|---|
| Bot 状态 | 运行中/暂停/观察、最近心跳、下次扫描。 |
| 市场分型 | 当前 regime、BTC/ETH 锚点、市场宽度。 |
| 今日收益 | 扣费后已实现、未实现、手续费、交易量。 |
| 当前仓位 | 按账户聚合，支持进入子账号明细。 |
| 最近信号 | 模型、分数、gate 结果、拒绝原因。 |

### 9.2 诊断视图

| 问题 | 页面必须能回答 |
|---|---|
| 为什么没开仓 | 哪个 gate 拦截最多。 |
| 为什么亏损 | 方向错、入场错、退出错、成本错。 |
| 哪个模型有效 | model-level win rate / PnL / drawdown。 |
| 哪个币拖累 | symbol-level PnL。 |
| 是否手续费吃掉 | gross PnL、fee、net PnL 同屏。 |
| 多账号表现 | 总览聚合 + 单账号 drilldown。 |

---

## 10. 数据模型改造方向

第一版新增或改造以下表。DDL 在实现阶段落地，这里先定字段语义。

### 10.1 `htx_trend_factor_snapshot`

记录每次候选评估的因子快照。

| 字段 | 说明 |
|---|---|
| `id` | 主键。 |
| `account_label` | 账户标签，后续多账号扩展。 |
| `contract_code` | 合约代码。 |
| `bar_time` | 信号 K 线时间。 |
| `market_regime` | 市场分型。 |
| `factor_json` | 所有因子值 JSON。 |
| `created_at` | 记录时间。 |

### 10.2 `htx_trend_model_signal`

每个模型独立产出候选。

| 字段 | 说明 |
|---|---|
| `model_name` | `BOX_BREAKOUT` / `FIB_PULLBACK_CONTINUATION` 等。 |
| `side` | LONG / SHORT / FLAT。 |
| `score` | 模型分。 |
| `entry_price_hint` | 建议入场价。 |
| `invalidation_price` | 失效价。 |
| `expected_net_after_fee` | 扣成本后预期收益。 |
| `gate_result` | PASS / REJECT / OBSERVE。 |
| `reject_reason` | 拒绝原因。 |
| `factor_snapshot_id` | 因子快照。 |

### 10.3 `htx_trend_position` 补充字段

| 字段 | 说明 |
|---|---|
| `entry_model` | 开仓模型。 |
| `entry_hypothesis` | 开仓假设。 |
| `invalidation_price` | 结构失效价。 |
| `entry_factor_snapshot_id` | 开仓因子快照。 |
| `exit_model_reason` | 模型匹配退出原因。 |
| `net_realized_profit` | 扣费后净收益，避免页面误读。 |

---

## 11. 实施计划

### M1：因子快照与观察，不改变实盘开仓

| 任务 | 验收 |
|---|---|
| 新增市场分型计算器 | 每轮输出 regime、anchor、breadth、volatility。 |
| 新增单币多周期结构计算 | 能输出 15m/30m/60m 方向、结构、箱体、swing。 |
| 新增因子快照表 | 每个候选都有 factor JSON。 |
| 页面展示候选因子 | 能看到模型、分数、gate 结果。 |

### M2：多模型候选，不直接下单

| 任务 | 验收 |
|---|---|
| 拆 `SignalModel` 接口 | 每个模型独立产出候选。 |
| 实现 `BOX_BREAKOUT` | 回测可识别箱体突破。 |
| 重写 `FIB_PULLBACK_CONTINUATION` | 必须有结构来源和重新转强。 |
| 实现 `MULTI_TIMEFRAME_MOMENTUM` | 支持小周期趋势候选。 |
| 候选排序 | 所有模型候选统一排名。 |

### M3：回测框架闭环

| 任务 | 验收 |
|---|---|
| 拉取 9/18、9/19、9/20、9/21 K 线 | 可离线重复运行。 |
| 按模型回测 | 输出模型级 PnL、胜率、PF、MFE/MAE。 |
| 对比线上真实单 | 能解释今天亏损单为什么会开、为什么会平。 |
| 参数初调 | 不靠单日调参，至少覆盖好/差/回撤三类行情。 |

### M4：真实开仓切换

| 任务 | 验收 |
|---|---|
| 新 gate 接入实盘链路 | 未通过 gate 的候选不下单。 |
| 仓位 sizing 接入 | 按失效价和风险预算算仓位。 |
| 模型匹配退出接入 | position 记录 entry_model 并按模型退出。 |
| 灰度上线 | 先小仓真实运行，再放大。 |

---

## 12. 当前线上策略到新骨架的映射

| 当前逻辑 | 新骨架位置 | 问题 | 处理 |
|---|---|---|---|
| 15m entry period | 多周期动量/入场周期 | 单独决定太弱 | 保留，但不能单独开仓。 |
| 60m trend period | 上级趋势因子 | 只看 EMA，不够结构化 | 加入 30m/60m 结构与斜率。 |
| Fibonacci 回踩 | `FIB_PULLBACK_CONTINUATION` | 缺少箱体/swing 来源与重新转强 | 重写。 |
| market breadth | 市场分型 | 目前同时参与 sizing 和 confidence，边界不清 | 上移到市场分型层。 |
| BTC/ETH anchor | 市场分型/方向因子 | 只能当环境，不能替代单币结构 | 保留但降为环境输入。 |
| direction confidence | 模型评分 | 权重混杂，不同模型一套分 | 改为模型独立评分。 |
| no-extension | 退出逻辑 | 过早平掉趋势单 | 按 entry_model 重写。 |
| realized_profit | 账本口径 | 不含手续费，容易误判 | 页面主口径改 net after fee。 |

---

## 13. 未决问题

| 编号 | 问题 | 处理方式 |
|---|---|---|
| OQ-1 | 箱体识别窗口取多少根 K？ | 先回测 20/32/48 三档。 |
| OQ-2 | Fibonacci swing 如何自动识别？ | 第一版用局部高低点 + ATR 最小波段过滤。 |
| OQ-3 | 52 个币是否全部实盘？ | 先全部观察，真实开仓根据回测剔除低质量币。 |
| OQ-4 | 15m/30m/60m 权重如何校准？ | 以 9/18–9/21 回测为初始样本，后续扩大历史窗口。 |
| OQ-5 | 多账号是否同一信号全同步？ | 第一版信号共享，账户级 sizing 和手续费独立。 |
| OQ-6 | 是否保留资金费 carry？ | 暂不作为开仓模型，只作为成本/拥挤因子。 |

---

## 14. 下一步执行清单

1. 创建因子快照和模型候选数据结构。
2. 实现市场分型服务，输出 regime/anchor/breadth/volatility。
3. 实现单币结构分析服务，输出 box/swing/multi-timeframe trend。
4. 拆出 `TrendSignalModel` 接口与三个第一版模型。
5. 改造候选排序与 gate，不直接让单模型下单。
6. 改造 position，记录 `entry_model`、`entry_hypothesis`、`invalidation_price`、`factor_snapshot_id`。
7. 建回测工具，先覆盖 2026-09-18 至 2026-09-21。
8. 页面展示市场分型、模型候选、gate 拦截、模型级 PnL。
9. 通过回测后再部署真实开仓逻辑。

