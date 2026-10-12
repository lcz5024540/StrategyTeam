# Statistical gates CLI (`pf-bootstrap`, `permutation`)

Formal acceptance stats for R0002+ (see prereg-batch1). These commands do **not** count as trial runs. Seeds and outputs are persisted for audit.

## `pf-bootstrap`

Resample completed trade `net_pnl` with replacement (default **10 000** draws). Reports observed profit factor and a **percentile bootstrap** confidence interval (default **90%**). The gate uses **`ci_lower`** (90% CI lower bound). For R0002 slice gates, compare each slice’s `ci_lower` to **≥ 0.8** (overall PF point gate remains separate in prereg).

```bash
htx-trend-research --workspace /path/to/ws pf-bootstrap \
  --run-id R0001 \
  --n-bootstrap 10000 \
  --confidence 0.9 \
  --seed 42 \
  --group-by coin_group \
  --group-by trend_state
```

### `--group-by trend_state` (R0002 three states)

Regime at entry (from `decisions.parquet`) is collapsed to **three** slice keys (not four):

| Slice key | Chinese (report) | Source regimes |
|-----------|------------------|----------------|
| `trend` | 趋势 | `TREND_UP_*`, `TREND_DOWN_*` |
| `range` | 震荡 | `RANGE_*` |
| `transition` | 过渡 | `TRANSITION_*` |

JSON slice names look like `trend_state:trend`, `trend_state:range`, `trend_state:transition`.

Inputs:

- `--run-id` — reads `runs/<run_id>/return_series.parquet` (from a completed `run`).
- `--trades` — explicit parquet with columns `symbol`, `entry_time`, `side`, `net_pnl`.

Outputs:

- `runs/<target_run_id>/stats/pf_bootstrap.json`
- Ledger `run_metrics.statistical_gates.pf_bootstrap` (seed, slice CI summaries) unless `--no-ledger`.

## `permutation`

Random-entry null: per symbol, keep actual trade count and long/short sequence; shuffle entry times among eligible bars (excludes `non_comparable` / `bad_tick`; C02 exits and conservative costs via the research backtest engine). While the null is running, **C01 and all strategy entry paths are disabled** — only forced-queue entries may open positions.

Primary metric: **mean net PnL per trade**; p-value vs H₀ (strategy ≤ random entry), gate **p < 0.05**.

**Null draw validity:** Each permutation draw resamples entry times until completed round-trip counts **per symbol and total** match the observed run. If a schedule would drop forced entries because a symbol is not `FLAT`, that resample is discarded and retried (up to **200** attempts per draw by default). Exhausting attempts fails the command. Invalid attempts are counted in `null_resample_policy.invalidated_resample_attempts` in the JSON output.

Source run should use the same **conservative** `cost_scenario` as formal acceptance.

```bash
htx-trend-research --workspace /path/to/ws permutation \
  --run-id R0001 \
  --n-permutations 1000 \
  --seed 99
```

Requires the source run in `ledger.db` (for config snapshot) or `--config path/to/run.yaml`. Writes `runs/<run_id>/stats/permutation.json` and ledger `statistical_gates.permutation`.

## Reproducibility

Same trades file + same `--seed` → identical JSON metrics. If `--seed` is omitted, a deterministic default is derived from the run id and stored in the output and ledger.

## 策略测试bot

1. After `run` completes, confirm `return_series.parquet` exists.
2. `pf-bootstrap --run-id <id> --group-by coin_group --group-by trend_state`
3. `permutation --run-id <id>`
4. Archive `runs/<id>/stats/*.json` with the run report.
