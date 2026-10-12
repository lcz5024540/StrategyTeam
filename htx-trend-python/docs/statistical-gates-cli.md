# Statistical gates CLI (`pf-bootstrap`, `permutation`)

Formal acceptance stats for R0002+ (see prereg-batch1). These commands do **not** count as trial runs. Seeds and outputs are persisted for audit.

## `pf-bootstrap`

Resample completed trade `net_pnl` with replacement (default **10 000** draws). Reports observed profit factor and a **percentile bootstrap** confidence interval (default **90%**). The gate uses **`ci_lower`** (90% CI lower bound).

```bash
htx-trend-research --workspace /path/to/ws pf-bootstrap \
  --run-id R0001 \
  --n-bootstrap 10000 \
  --confidence 0.9 \
  --seed 42 \
  --group-by coin_group \
  --group-by trend_state
```

Inputs:

- `--run-id` — reads `runs/<run_id>/return_series.parquet` (from a completed `run`).
- `--trades` — explicit parquet with columns `symbol`, `entry_time`, `side`, `net_pnl`.

Outputs:

- `runs/<target_run_id>/stats/pf_bootstrap.json`
- Ledger `run_metrics.statistical_gates.pf_bootstrap` (seed, slice CI summaries) unless `--no-ledger`.

## `permutation`

Random-entry null: per symbol, keep actual trade count and long/short sequence; shuffle entry times among eligible bars (excludes `non_comparable` / `bad_tick`; C02 exits and conservative costs via the research backtest engine). Primary metric: **mean net PnL per trade**; p-value vs H₀ (strategy ≤ random entry), gate **p < 0.05**.

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
