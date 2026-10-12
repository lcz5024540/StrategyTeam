# Formal automatic LIVE — acceptance & handoff (0.1.1-live)

**This package does not start live trading.** Installing or unpacking it does not set `live_trading_enabled`, enable a systemd unit, or flip `HTX_TREND_ENABLED`. Operators must complete every gate below before `python -m htx_trend.worker` runs in `LIVE` mode.

## Scope vs other packages

| Artifact | Purpose |
|----------|---------|
| **v0.1.0** `0e54ec24` | Research backtest / rc.13 integrity baseline |
| **0.1.1-probe** `2db66ae0` | One-shot `probe_1contract` (`LIVE_1CONTRACT_PROBE`); not this worker |
| **0.1.1-live** (this) | Signal-driven `htx_trend.worker` + `live_worker` auto open/close |

Diff review: `0e54ec24...<live-commit>` for research; `2db66ae0...<live-commit>` adds formal LIVE guards + docs (probe numeric `client_order_id` already on formal path via `execution_service._numeric_client_order_id`).

## First-cut order-path limits (R0001, 300U, BTC+ETH 4h)

Enforced at worker startup via `formal_live.validate_formal_live_deployment()`:

| Limit | Value | Env knob |
|-------|-------|----------|
| Universe | BTC-USDT, ETH-USDT only | `HTX_TREND_CONTRACTS` |
| Entry period | 4h | `HTX_TREND_PERIOD=4hour` |
| Max positions | ≤ 5 | `HTX_TREND_MAX_ACTIVE_POSITIONS` |
| Per-trade risk | ≤ 6 USDT (2% of 300U) | `HTX_TREND_RISK_PER_TRADE_USDT` |
| Total risk budget | ≤ 30 USDT (10% of 300U) | `HTX_TREND_MAX_TOTAL_RISK_USDT` |
| Same-direction cap | ≤ 3 | `HTX_TREND_MAX_SAME_DIRECTION_POSITIONS` |

MySQL `strategy_account_risk_limit` / reservations must align with these caps. Research `PositionSizer` (5 / 2% / 10%) is unchanged.

## Mandatory LIVE gates (worker)

All required before HTTP order placement on the **LIVE** trend path:

1. **Exit parity:** `HTX_PYTHON_EXIT_PARITY_APPROVED=true`
2. **Mixed-market policy (first cut):** `HTX_TREND_MIXED_MARKET_REQUIRES_STRONG_CANDIDATE=false`
3. **Runtime instance:** `execution_mode=LIVE`, `enabled=1`, strategy family `TREND`
4. **Account:** bound `PRIMARY` only; no enabled `MIRROR` accounts (`enabled_trend_accounts()` check)
5. **Credentials:** MySQL `ENC(...)` ciphers only; `APP_RSA_PRIVATE_KEY_PATH` set; **no** `HTX_ACCESS_KEY` / `HTX_SECRET_KEY` / `HTX_API_KEY` / `HTX_TREND_MIRROR_*` in environment (`materialize_live_primary`)
6. **Writer lock:** `TrendWriterLock` (`coinstrategy:htx:trend:writer:v1`)
7. **Strategy admission:** `HTX_TREND_STRATEGY_ADMISSION_PATH` for non-legacy strategy versions
8. **Formal risk envelope:** `validate_formal_live_deployment()` (table above)
9. **Client order ids:** numeric int64 via `_numeric_client_order_id()` on entry/exit (HTX v5)

**Not used on this path:** `LIVE_1CONTRACT_PROBE`, `probe_1contract`.

## Suggested env file (`deployment/htx-python.env.example`)

Copy to a protected host path (e.g. `/opt/coinstrategy/htx-trend-py-state/htx-python.env`). See example file for DB, RSA path, contracts, period, risk caps, and parity flags.

## systemd

Template: `deployment/systemd/htx-trend-worker@.service` — `EnvironmentFile` must supply gates; **do not** enable the unit until ops sign-off.

## Migrations / config

- No new SQL migrations in this package slice.
- Ensure existing platform schema (`validate_runtime_schema`) and `htx_trade_account` PRIMARY row with `ENC(...)` ciphers.
- Pin `strategy_account_risk_limit.max_active_positions ≤ 5` for the LIVE account.

## Rollback

1. `systemctl disable --now htx-trend-worker@<instance>`
2. Set runtime instance `execution_mode=OBSERVE` or `enabled=0` in MySQL
3. Redeploy previous release symlink under `/opt/coinstrategy/htx-trend-py/releases/`
4. Revoke `HTX_PYTHON_EXIT_PARITY_APPROVED` on the host if returning to paper/observe-only

## Verification (no live orders in CI)

```bash
cd htx-trend-python
PYTHONPATH=src python -m pytest \
  tests/test_formal_live.py tests/test_worker_cutover.py tests/test_live_worker.py \
  tests/test_probe_credentials.py tests/test_execution_service.py -q
```

Operator smoke (observe first): run instance in `OBSERVE`, then `LIVE` with minimal `--once` only after gates and manual approval.
