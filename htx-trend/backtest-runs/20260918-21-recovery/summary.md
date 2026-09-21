# HTX trend strategy-level backtest 2026-09-18 to 2026-09-21T21:45

This replay regenerates signals from historical 15m/60m klines. The recovery profile matches the deployed recovery package: direction-confidence gating remains enabled, adverse-shock/no-extension exits are disabled, and sizing is capped at 190 USDT with a 1.5 USDT risk budget. Funding-carry entries and historical order-book depth are not simulated.

## Daily comparison

| mode | day | trades | net win | gross PnL | fees | net PnL | PF | avg win | avg loss | max loss |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| baseline_chase_only | 2026-09-18 | 9 | 44.44% | 3.0183 | 1.2077 | 1.8106 | 1.51 | 1.3439 | 0.7130 | -1.3960 |
| baseline_chase_only | 2026-09-19 | 10 | 10.00% | -2.7940 | 1.0240 | -3.8180 | 0.07 | 0.2840 | 0.4558 | -0.8594 |
| baseline_chase_only | 2026-09-20 | 4 | 0.00% | -1.6601 | 0.4429 | -2.1030 | 0.00 | 0.0000 | 0.5257 | -0.6541 |
| baseline_chase_only | 2026-09-21 | 7 | 42.86% | 5.5084 | 0.6053 | 4.9030 | 3.58 | 2.2675 | 0.4749 | -0.8511 |
| recovery_20260921 | 2026-09-18 | 6 | 66.67% | 7.3052 | 0.8361 | 6.4691 | 6.82 | 1.8952 | 0.5559 | -0.5868 |
| recovery_20260921 | 2026-09-19 | 2 | 0.00% | -0.6734 | 0.1718 | -0.8452 | 0.00 | 0.0000 | 0.4226 | -0.4405 |
| recovery_20260921 | 2026-09-21 | 3 | 33.33% | 1.9765 | 0.3673 | 1.6092 | 2.04 | 3.1534 | 0.7721 | -1.3051 |

## Total comparison

| mode | trades | net win | gross PnL | fees | net PnL | PF | avg win | avg loss | max loss |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| baseline_chase_only | 30 | 26.67% | 4.0726 | 3.2800 | 0.7926 | 1.07 | 1.5578 | 0.5304 | -1.3960 |
| recovery_20260921 | 11 | 45.45% | 8.6083 | 1.3752 | 7.2331 | 3.07 | 2.1469 | 0.5835 | -1.3051 |

## Recovery sleeve decisions

| sleeve | trades | net PnL | PF | max loss | decision |
|---|---:|---:|---:|---:|---|
| BTC-USDT:Donchian | 5 | 3.1359 | 4.80 | -0.5868 | PAPER_CANDIDATE |
| BTC-USDT:Fibonacci | 1 | 4.7257 | 999.00 | 4.7257 | INSUFFICIENT_SAMPLE |
| ETH-USDT:Donchian | 4 | -0.1035 | 0.95 | -1.3051 | REJECT |
| ETH-USDT:Fibonacci | 1 | -0.5250 | 0.00 | -0.5250 | INSUFFICIENT_SAMPLE |
| XAUT-USDT:NoSignal | 0 | 0.0000 | 0.00 | 0.0000 | INSUFFICIENT_SAMPLE |

## Repaired skip counts

| skip bucket | count |
|---|---:|
| direction:Fibonacci:MIXED | 37 |
| direction:Donchian:MIXED | 11 |
| direction:Fibonacci:BROAD_DOWN | 1 |

## Boundary

This is a deterministic kline replay, not a perfect exchange simulator. It does not include historical funding-rate carry promotion, real order-book depth, partial fills, liquidation margin state, or intrabar polling order. It is useful for comparing strategy logic across market regimes before deploying real trading code.
