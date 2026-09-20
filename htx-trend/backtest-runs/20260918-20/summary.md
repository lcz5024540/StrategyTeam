# HTX trend strategy-level backtest 2026-09-18~20

This replay regenerates signals from historical 15m/60m klines. It compares the previous live-like path with the repaired path that adds direction-confidence gating and no-extension exits. Funding-carry entries and historical order-book depth are not simulated in this first offline pass.

## Daily comparison

| mode | day | trades | net win | gross PnL | fees | net PnL | PF | avg win | avg loss | max loss |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| baseline_chase_only | 2026-09-18 | 32 | 43.75% | 25.3250 | 2.6123 | 22.7128 | 2.12 | 3.0671 | 1.1237 | -2.5973 |
| baseline_chase_only | 2026-09-19 | 31 | 32.26% | -0.6752 | 2.4518 | -3.1270 | 0.79 | 1.1771 | 0.7094 | -2.1192 |
| baseline_chase_only | 2026-09-20 | 14 | 21.43% | -4.7974 | 1.2060 | -6.0034 | 0.47 | 1.7511 | 1.0233 | -4.2990 |
| repaired_confidence_no_extension | 2026-09-18 | 34 | 32.35% | 31.4617 | 4.5729 | 26.8889 | 2.17 | 4.5414 | 1.0029 | -2.5341 |
| repaired_confidence_no_extension | 2026-09-19 | 19 | 36.84% | 2.6320 | 2.2531 | 0.3789 | 1.03 | 2.0701 | 1.1760 | -2.7980 |
| repaired_confidence_no_extension | 2026-09-20 | 13 | 38.46% | 5.8179 | 1.7469 | 4.0710 | 1.33 | 3.3165 | 1.5640 | -4.2990 |

## Total comparison

| mode | trades | net win | gross PnL | fees | net PnL | PF | avg win | avg loss | max loss |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| baseline_chase_only | 77 | 35.06% | 19.8524 | 6.2700 | 13.5824 | 1.29 | 2.2209 | 0.9276 | -4.2990 |
| repaired_confidence_no_extension | 66 | 34.85% | 39.9116 | 8.5729 | 31.3387 | 1.63 | 3.5230 | 1.1556 | -4.2990 |

## Repaired skip counts

| skip bucket | count |
|---|---:|
| direction:Fibonacci:MIXED | 355 |
| direction:Donchian:MIXED | 343 |
| direction:Fibonacci:BROAD_DOWN | 41 |
| chase:Donchian:MIXED | 40 |
| chase:Donchian:BROAD_UP | 35 |
| direction:Fibonacci:BROAD_UP | 22 |
| chase:Fibonacci:MIXED | 20 |
| chase:Fibonacci:BROAD_UP | 5 |
| chase:Donchian:BROAD_DOWN | 3 |
| direction:Donchian:BROAD_DOWN | 3 |
| chase:Fibonacci:BROAD_DOWN | 2 |

## Boundary

This is a deterministic kline replay, not a perfect exchange simulator. It does not include historical funding-rate carry promotion, real order-book depth, partial fills, liquidation margin state, or intrabar polling order. It is useful for comparing strategy logic across market regimes before deploying real trading code.
