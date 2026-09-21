# HTX trend strategy-level backtest 2026-09-01T02:00 to 2026-09-18

This replay regenerates signals from historical 15m/60m klines. The recovery profile matches the deployed recovery package: direction-confidence gating remains enabled, adverse-shock/no-extension exits are disabled, and sizing is capped at 190 USDT with a 1.5 USDT risk budget. Funding-carry entries and historical order-book depth are not simulated.

## Daily comparison

| mode | day | trades | net win | gross PnL | fees | net PnL | PF | avg win | avg loss | max loss |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| baseline_chase_only | 2026-09-01 | 3 | 33.33% | -0.8596 | 0.5108 | -1.3704 | 0.47 | 1.2097 | 1.2901 | -1.8551 |
| baseline_chase_only | 2026-09-02 | 12 | 33.33% | 0.3306 | 1.5078 | -1.1772 | 0.76 | 0.9264 | 0.6104 | -1.1187 |
| baseline_chase_only | 2026-09-03 | 6 | 66.67% | 7.4799 | 0.7930 | 6.6869 | 5.95 | 2.0094 | 0.6754 | -0.7494 |
| baseline_chase_only | 2026-09-04 | 11 | 9.09% | -8.4341 | 1.0422 | -9.4763 | 0.04 | 0.4282 | 0.9904 | -3.2229 |
| baseline_chase_only | 2026-09-05 | 4 | 25.00% | -0.3636 | 0.4829 | -0.8465 | 0.40 | 0.5581 | 0.4682 | -0.8907 |
| baseline_chase_only | 2026-09-06 | 6 | 16.67% | -1.1630 | 0.6837 | -1.8467 | 0.37 | 1.0969 | 0.5887 | -0.7186 |
| baseline_chase_only | 2026-09-07 | 10 | 20.00% | -1.7061 | 1.1869 | -2.8930 | 0.24 | 0.4468 | 0.4733 | -0.9569 |
| baseline_chase_only | 2026-09-08 | 11 | 9.09% | -3.1125 | 1.2992 | -4.4117 | 0.05 | 0.2311 | 0.4643 | -1.0439 |
| baseline_chase_only | 2026-09-09 | 8 | 25.00% | -1.7322 | 0.8982 | -2.6304 | 0.08 | 0.1168 | 0.4773 | -1.0933 |
| baseline_chase_only | 2026-09-10 | 12 | 25.00% | -0.5242 | 1.4972 | -2.0214 | 0.54 | 0.7940 | 0.4893 | -1.1370 |
| baseline_chase_only | 2026-09-11 | 7 | 28.57% | 2.3702 | 0.9327 | 1.4376 | 1.47 | 2.2488 | 0.6120 | -0.8771 |
| baseline_chase_only | 2026-09-12 | 2 | 0.00% | -0.8560 | 0.1420 | -0.9980 | 0.00 | 0.0000 | 0.4990 | -0.5439 |
| baseline_chase_only | 2026-09-13 | 3 | 33.33% | -0.8125 | 0.3423 | -1.1549 | 0.32 | 0.5364 | 0.8456 | -1.1273 |
| baseline_chase_only | 2026-09-14 | 9 | 0.00% | -3.6240 | 1.0422 | -4.6663 | 0.00 | 0.0000 | 0.5185 | -0.7612 |
| baseline_chase_only | 2026-09-15 | 10 | 40.00% | 2.5225 | 1.2228 | 1.2996 | 1.48 | 0.9998 | 0.4499 | -1.0443 |
| baseline_chase_only | 2026-09-16 | 6 | 16.67% | -0.8355 | 0.5264 | -1.3619 | 0.43 | 1.0242 | 0.4772 | -0.7126 |
| baseline_chase_only | 2026-09-17 | 10 | 30.00% | -0.2830 | 1.1107 | -1.3938 | 0.68 | 1.0050 | 0.6298 | -1.1956 |
| recovery_20260921 | 2026-09-01 | 2 | 50.00% | -0.2653 | 0.3801 | -0.6454 | 0.65 | 1.2097 | 1.8551 | -1.8551 |
| recovery_20260921 | 2026-09-02 | 11 | 36.36% | 1.2469 | 1.6513 | -0.4044 | 0.90 | 0.9116 | 0.5787 | -1.1187 |
| recovery_20260921 | 2026-09-03 | 5 | 60.00% | 5.7126 | 0.7242 | 4.9885 | 4.69 | 2.1131 | 0.6754 | -0.7494 |
| recovery_20260921 | 2026-09-04 | 4 | 0.00% | -2.4552 | 0.4316 | -2.8868 | 0.00 | 0.0000 | 0.7217 | -0.8484 |
| recovery_20260921 | 2026-09-05 | 2 | 0.00% | -0.9942 | 0.3384 | -1.3326 | 0.00 | 0.0000 | 0.6663 | -0.8907 |
| recovery_20260921 | 2026-09-06 | 3 | 0.00% | -1.7637 | 0.3983 | -2.1620 | 0.00 | 0.0000 | 0.7207 | -0.8621 |
| recovery_20260921 | 2026-09-07 | 4 | 0.00% | -2.7406 | 0.5906 | -3.3312 | 0.00 | 0.0000 | 0.8328 | -1.6352 |
| recovery_20260921 | 2026-09-08 | 5 | 40.00% | 0.4549 | 0.8548 | -0.3999 | 0.78 | 0.7117 | 0.6078 | -0.8398 |
| recovery_20260921 | 2026-09-09 | 4 | 25.00% | -0.8521 | 0.6265 | -1.4786 | 0.05 | 0.0723 | 0.5170 | -1.0933 |
| recovery_20260921 | 2026-09-10 | 8 | 12.50% | -1.7661 | 1.1741 | -2.9402 | 0.32 | 1.3818 | 0.6174 | -1.1370 |
| recovery_20260921 | 2026-09-11 | 9 | 22.22% | -1.7068 | 1.3311 | -3.0379 | 0.44 | 1.1861 | 0.7729 | -1.5107 |
| recovery_20260921 | 2026-09-13 | 2 | 0.00% | -1.2300 | 0.2285 | -1.4585 | 0.00 | 0.0000 | 0.7293 | -1.1273 |
| recovery_20260921 | 2026-09-14 | 5 | 0.00% | -2.1849 | 0.5901 | -2.7750 | 0.00 | 0.0000 | 0.5550 | -0.7612 |
| recovery_20260921 | 2026-09-15 | 8 | 25.00% | 3.6502 | 1.2119 | 2.4383 | 1.89 | 2.5920 | 0.4576 | -1.0443 |
| recovery_20260921 | 2026-09-16 | 2 | 0.00% | -0.9087 | 0.2220 | -1.1307 | 0.00 | 0.0000 | 0.5654 | -0.6825 |
| recovery_20260921 | 2026-09-17 | 6 | 16.67% | -2.5756 | 0.9383 | -3.5139 | 0.10 | 0.4044 | 0.7837 | -1.1956 |

## Total comparison

| mode | trades | net win | gross PnL | fees | net PnL | PF | avg win | avg loss | max loss |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| baseline_chase_only | 130 | 23.85% | -11.6031 | 15.2212 | -26.8243 | 0.54 | 1.0274 | 0.5927 | -3.2229 |
| recovery_20260921 | 80 | 21.25% | -8.3787 | 11.6918 | -20.0705 | 0.52 | 1.2961 | 0.6683 | -1.8551 |

## Recovery sleeve decisions

| sleeve | trades | net PnL | PF | max loss | decision |
|---|---:|---:|---:|---:|---|
| BTC-USDT:Donchian | 19 | -2.1191 | 0.71 | -1.0933 | REJECT |
| BTC-USDT:Fibonacci | 6 | -2.1053 | 0.36 | -1.1187 | REJECT |
| ETH-USDT:Donchian | 28 | -5.0587 | 0.66 | -1.6352 | REJECT |
| ETH-USDT:Fibonacci | 8 | -4.2038 | 0.22 | -1.5107 | REJECT |
| XAUT-USDT:Donchian | 11 | -6.9467 | 0.05 | -1.8551 | REJECT |
| XAUT-USDT:Fibonacci | 8 | 0.3631 | 1.09 | -0.8002 | REJECT |

## Repaired skip counts

| skip bucket | count |
|---|---:|
| direction:Fibonacci:MIXED | 133 |
| direction:Donchian:MIXED | 22 |
| chase:Fibonacci:MIXED | 7 |
| direction:Fibonacci:BROAD_DOWN | 7 |
| direction:Fibonacci:BROAD_UP | 5 |
| chase:Donchian:BROAD_UP | 2 |
| chase:Donchian:BROAD_DOWN | 1 |
| chase:Donchian:MIXED | 1 |
| chase:Fibonacci:BROAD_UP | 1 |

## Boundary

This is a deterministic kline replay, not a perfect exchange simulator. It does not include historical funding-rate carry promotion, real order-book depth, partial fills, liquidation margin state, or intrabar polling order. It is useful for comparing strategy logic across market regimes before deploying real trading code.
