# HTX Trend 2026-09-18 to 2026-09-21 recovery replay

## Decision

No sleeve is approved for real trading or promoted into a new Paper whitelist. The 9/18–9/21 short-window result initially made BTC-USDT/Donchian a Paper candidate, but the independent 9/1–9/17 historical sample rejects it: 19 trades, net -2.1191U, PF 0.71. ETH-USDT and XAUT-USDT are also rejected on the same longer sample.

| Sleeve | Closed trades | Net PnL (USDT) | PF | Worst trade (USDT) | Decision |
|---|---:|---:|---:|---:|---|
| BTC-USDT / Donchian | 5 | +3.1359 | 4.80 | -0.5868 | `PAPER_CANDIDATE` |
| BTC-USDT / Fibonacci | 1 | +4.7257 | 999.00 | +4.7257 | `INSUFFICIENT_SAMPLE` |
| ETH-USDT / Donchian | 4 | -0.1035 | 0.95 | -1.3051 | `REJECT` |
| ETH-USDT / Fibonacci | 1 | -0.5250 | 0.00 | -0.5250 | `INSUFFICIENT_SAMPLE` |
| XAUT-USDT / all entry models | 0 | 0.0000 | 0.00 | 0.0000 | `INSUFFICIENT_SAMPLE` (no signal) |

The acceptance threshold is at least three closed trades, positive net PnL after fees, PF >= 1.20, and no individual net loss below -1.5U. A short-window `PAPER_CANDIDATE` is intentionally not a promotion; it must also pass an independent historical sample.

## Independent historical rejection: 2026-09-01 02:00 to 2026-09-18 00:00

| Sleeve | Closed trades | Net PnL (USDT) | PF | Worst trade (USDT) | Decision |
|---|---:|---:|---:|---:|---|
| BTC-USDT / Donchian | 19 | -2.1191 | 0.71 | -1.0933 | `REJECT` |
| BTC-USDT / Fibonacci | 6 | -2.1053 | 0.36 | -1.1187 | `REJECT` |
| ETH-USDT / Donchian | 28 | -5.0587 | 0.66 | -1.6352 | `REJECT` |
| ETH-USDT / Fibonacci | 8 | -4.2038 | 0.22 | -1.5107 | `REJECT` |
| XAUT-USDT / Donchian | 11 | -6.9467 | 0.05 | -1.8551 | `REJECT` |
| XAUT-USDT / Fibonacci | 8 | +0.3631 | 1.09 | -0.8002 | `REJECT` |

This sample uses the same recovery profile, net-of-fee accounting and fixed acceptance rule. It shows that the 9/18 breakout was regime-dependent, not sufficient evidence of a stable BTC sleeve.

## Replay provenance

- Window: 2026-09-18 00:00 through the final frozen 15-minute candle at 2026-09-21 21:30, Asia/Shanghai. The current date was still 9/21 when the inputs were captured, so this is not represented as a completed 9/21 trading day.
- Inputs: immutable public HTX linear-swap 15-minute and 60-minute K-line responses under `htx-trend/backtest-data/20260918-21/`.
- Runner: `htx-trend/tools/htx_trend_backtest.py`, recovery profile `recovery_20260921`.
- Recovery profile: direction-confidence gate enabled; adverse-shock and no-extension exits disabled; target/max notional 190U; risk budget 1.5U; existing initial stop, flat-loss and trailing rules retained.
- Accounting: next-bar-open fills and 0.04% taker fee on each leg of entry and exit.

The three-symbol recovery run produced 11 closed trades, +7.2331U net after 1.3752U estimated fees, PF 3.07. The comparison baseline produced 30 closed trades, +0.7926U net, PF 1.07. This comparison supports the recovery profile over the prior broad/chase profile; it does not establish exchange-execution performance.

## Scope and limits

This is a deterministic candle replay. It does not replay historical L2 depth, partial fills, exact order latency, funding-carry promotion, margin/liquidation state, or the service scheduler's intrabar ordering. The 30-minute and 4-hour captures are retained for the next multi-timeframe sleeve study but are not inputs to this 15-minute-entry/60-minute-trend runner, so this report does not claim a 30/60-minute sleeve result.

Before any live sleeve decision, add market-regime gates and daily net-loss/quality circuits, then repeat a longer Paper period with persisted order/fee evidence and L2/partial-fill replay coverage. Do not loosen Fibonacci thresholds or change production entry rules based on this report.
