# HTX XAUT-USDT 合约趋势策略

## 结论

当前策略从移动网格切到合约趋势跟随：只在 XAUT-USDT 永续合约出现“高周期方向一致 + 低周期突破 + 波动/趋势强度足够”的场景开仓，持仓后用移动止损、反向信号和资金费成本退出。它借鉴 vn.py CTA 的分层方式，但每一层都按虚拟货币 7×24、永续合约、资金费、盘口滑点和交易所状态恢复来落地。

## 框架分层

| 层级 | 借鉴的框架思想 | 本项目实现 |
|---|---|---|
| 行情事件层 | K 线驱动策略回调 | `HtxTrendFollowingScheduler` 定时拉取 HTX USDT 本位永续 K 线 |
| 信号层 | CTA 策略把指标计算与交易执行分开 | `HtxTrendSignalCalculator` 只计算信号，不下单、不读 DB |
| 风控层 | 下单前统一 gate | `HtxTrendFollowingService` 检查资金费、盘口价差、杠杆、保证金、开放订单和外部仓位 |
| 执行层 | `buy/sell/short/cover` 语义映射到交易所订单 | 开多=`buy/long`，平多=`sell/long reduce_only`；开空=`sell/short`，平空=`buy/short reduce_only` |
| 状态层 | 策略自有持仓状态机 | `htx_trend_position` 只管理本策略开出的仓，不接管外部手工仓位 |
| 观测层 | 策略变量可审计 | `htx_trend_signal` 每轮记录信号、ATR、ADX、突破位、资金费和点差 |

## 信号逻辑

第一步，过滤未完成 K 线。虚拟货币是 7×24 连续交易，最新 K 线经常还在形成中；策略只使用 `bar_time + period + candle_close_grace_seconds <= now` 的 K 线，避免被盘中假突破带进场。

第二步，判断高周期方向。默认用 60 分钟 K 线，`trend_fast_ema=8`、`trend_slow_ema=21`：快线高于慢线只允许做多，快线低于慢线只允许做空，横盘不交易。

第三步，判断低周期突破。默认用 15 分钟 K 线，收盘价突破过去 20 根已完成 K 线的 Donchian 高点才考虑做多，跌破 Donchian 低点才考虑做空，同时要求低周期 EMA 快慢线同向。

第四步，过滤低波动和弱趋势。`ATR / close` 低于阈值时跳过，避免 XAUT 低波动阶段被手续费吃掉；ADX 低于阈值时跳过，避免震荡区间反复开平。

## 加密合约交易规则

1. 趋势策略和现货网格、资金费套利互斥。`htx.market-maker.enabled=true` 或 `htx.funding-arbitrage.enabled=true` 时，趋势策略直接跳过。
2. 开仓前检查当前合约是否有开放订单，若有则跳过，避免多个策略或多轮调度叠单。
3. 开仓前检查交易所真实持仓，若存在外部仓位则跳过，避免把手工仓位或旧策略仓位误当成本策略仓位管理。
4. 开仓前切换对应方向杠杆，并按 ATR 止损距离和单笔风险预算计算张数。
5. 资金费按方向处理：资金费为正时，多头付费、空头收；资金费为负时，空头付费、多头收。付费方向超过开仓阈值时不开仓，持仓超过持有阈值时触发退出。
6. 当前退出保护由调度轮询执行：移动止损、可选止盈、反向信号、资金费成本恶化都会发 reduce-only 市价平仓。HTX 交易所侧条件止损单尚未接入，长期实盘运行前应优先补齐。

## 默认参数

| 参数 | 默认值 | 用途 |
|---|---:|---|
| `HTX_TREND_PERIOD` | `15min` | 入场/退出触发周期 |
| `HTX_TREND_TREND_PERIOD` | `60min` | 大方向过滤周期 |
| `HTX_TREND_BREAKOUT_LOOKBACK` | `20` | Donchian 突破窗口 |
| `HTX_TREND_MIN_ADX` | `18` | 最小趋势强度 |
| `HTX_TREND_MIN_ATR_PCT` | `0.0010` | 最小波动率，低于则跳过 |
| `HTX_TREND_TARGET_QUOTE_NOTIONAL` | `190` | 目标名义本金 |
| `HTX_TREND_RISK_PER_TRADE_USDT` | `1.5` | 单笔风险预算 |
| `HTX_TREND_ATR_STOP_MULTIPLIER` | `2.5` | 初始止损距离 |
| `HTX_TREND_TRAILING_ATR_MULTIPLIER` | `2.0` | 移动止损距离 |
| `HTX_TREND_MAX_ENTRY_FUNDING_COST_RATE` | `0.0006` | 开仓资金费成本上限 |
| `HTX_TREND_MAX_HOLD_FUNDING_COST_RATE` | `0.0012` | 持仓资金费成本退出阈值 |
| `HTX_TREND_COOLDOWN_MS` | `3600000` | 完成一轮后冷却 1 小时 |

## 推荐上线步骤

第一步，观察模式启动。设置 `HTX_TREND_ENABLED=true`、`HTX_TREND_OBSERVE_ONLY=true`、`HTX_MM_ENABLED=false`、`HTX_FUNDING_ARB_ENABLED=false`，确认 `htx_trend_signal` 能稳定写入，并观察至少一个完整 60 分钟趋势周期。

第二步，核对信号质量。重点看 `signal`、`reason`、`atr_pct`、`adx`、`spread_rate`、`funding_rate`，如果长期都是 `ATR below threshold`，说明 XAUT 波动仍不足，应降低周期或换更活跃标的，而不是继续调低网格。

第三步，小额度实盘。设置 `HTX_TREND_OBSERVE_ONLY=false`，同时保持 `HTX_CONTRACT_ENABLED=true`、`HTX_CONTRACT_DRY_RUN=false`、`HTX_CONTRACT_LIVE_ENABLED=true`，目标名义先限制在 190U 左右，确认真实订单、DB 状态和交易所持仓一致。

第四步，补交易所侧保护单。当前版本的止损由服务调度轮询执行；如果网络、进程或交易所 API 临时异常，止损触发会延迟。长期实盘前应接入 HTX 条件止损/止盈单，做到服务异常时交易所仍能保护仓位。
