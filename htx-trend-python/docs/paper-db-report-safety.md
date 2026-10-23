# PAPER MySQL mirror / report safety

JSON remains the authoritative ledger. `PaperFileLedger.checkpoint` first fsyncs
and atomically replaces JSON, then calls the optional mirror with a decoded copy.
A mirror exception is logged without rolling back JSON or changing execution.
Every subsequent checkpoint retries the committed history, including old fills.
No exchange requests, LIVE executor, lifecycle/risk reservation writes, DDL,
installation or deployment are part of this adapter.

## Configuration and backfill

Opt in using a dedicated `HTX_PAPER_DB_HOST`, `HTX_PAPER_DB_PORT` (default 3306),
`HTX_PAPER_DB_DATABASE`, `HTX_PAPER_DB_USER`, `HTX_PAPER_DB_PASSWORD`. No host means
no connection. Incomplete settings/connection errors are logged and retried on
later checkpoints. Existing LIVE DB environment variables are deliberately not
inherited. No environment was changed and no MySQL was contacted in this task.

`backfill_paper_state_to_mysql(state_dict | json_text | Path, repo)` accepts an
explicit repository and never connects itself. The tested historical window is
synthetic, generated using the real PaperFileLedger and WorkerPaperExchange:
6 fills, 6 FILLED orders, 3 JSON CLOSED positions, 0 OPEN. Database status DONE
is the LIVE schema equivalent of CLOSED. Unfilled/expired orders without a
position are not trade facts and are omitted. Ambiguous/orphan fills fail closed.

Accepted identities are the allowlist pairs, not a single hardcoded primary.
`strategy_name` and the ledger account string follow state `identity`:
`HTX_TREND_15M_V2` + `paper:htx-trend-paper-primary`, and
`HTX_TREND_4H_V2` + `paper:htx-trend-paper-4h`. Any other pair fails before
writes. Module constants `STRATEGY` / `ACCOUNT` remain the 15m primary default
for existing imports. The shared `account_label='paper'` row is created
idempotently and disabled for LIVE discovery; its note records
`paper:{instance}` for the inserting identity and is not rewritten later.
Existing enabled paper accounts are rejected rather than modified. All three
mirror tables have paper account id/label; position/client keys use `paper-`.
Existing identities owned by another account or another allowed strategy name
are rejected. The account lock serializes writers;
transaction rollback includes intents, fills and final PnL. Existing schema
unique keys are required. There is no runtime schema migration.

Arithmetic follows order_accounting: cumulative filled quantities and weighted
average prices, remaining = entered - closed, total fee = entry fee + close fee,
net = gross - total fee. Each simulated fill uses amount = price * contracts *
contract_size; fee = amount * 0.0004 (the WorkerPaperExchange rate). Gross is
(close fill price - weighted entry fill price) * contracts * contract_size, with
sign reversed for shorts. JSON realized_pnl is never copied; its entry-price and
fee basis can differ. JSON fill fee already includes contract_size and must not
be multiplied a second time. Terminal source is always PAPER_SIMULATED. Existing
paper.py, runtime.py, signals, sizing and WorkerPaperExchange arithmetic are
unchanged; only post-checkpoint mirroring is added. This does not establish a
new live research admission or deployment authority.

## Default LIVE query and remaining migration inventory

`paper_db_persist.LIVE_POSITION_SUMMARY_SQL` explicitly filters
`account_label <> 'paper'`. `test_default_live_summary_excludes_paper` executes
that actual query on mixed primary/paper rows. The same filter must be applied
to default LIVE order and ledger summaries. Explicit PAPER reports select paper.

The F1–F5 source migration now applies that scope in `HtxTrendDashboardMapper`
for positions, orders, accounts and ledger events before aggregation, joins or
LIMIT. `HtxTrendPositionMapper.trendPerformanceSince` and `latestDoneAt` exclude
paper; `trendPerformanceForBucket` treats null accountLabel as LIVE, while an
explicit paper label remains supported. `findActive`, `findByRunId`,
`findLastDone` and `countOpenLike` already select primary only. Per-position
order-ledger aggregation is identity-scoped, not a default daily summary.
The observation runbook now excludes paper from position/order counts.

## Read-only API and dashboard contract (F1–F3)

`GET /strategy-platform/arbitrage-dashboard/htx-trend?mode=PAPER` reads
`htx_trend_position`, `htx_trend_order` and `coin_strategy_ledger_event` with
`account_label='paper'`. The existing gateway prefix is `/strategy/api`.
Missing mode defaults to LIVE; LIVE uses `account_label <> 'paper'`.
Invalid modes return 400; POST/DELETE are not mapped (405). No trading dependency
or write endpoint is added. Account identity is authoritative: `paper-*` client
keys are returned unchanged as strings, not parsed as numeric exchange IDs.
PAPER mirror `strategy_name` follows identity (`HTX_TREND_15M_V2` or
`HTX_TREND_4H_V2`). LIVE retains `HTX_TREND_V1` and primary/TradeX account
compatibility. A paper row bearing the old LIVE strategy name still cannot
enter a LIVE report. Reports that read `account_label='paper'` must also filter
`strategy_name` when 15m and 4h rows share that account.

PAPER fees reside on OPEN_FILL/CLOSE_FILL events. Its ledger net sums gross minus
all fill fees, including entry fees; LIVE retains the existing FEE-event basis.
Positions expose finalGrossProfit, finalTotalFee, finalNetProfit, finalPnlSource
and finalPnlCalculatedAt. PAPER_SIMULATED labels simulated terminal facts.
PAPER does not display LIVE-only signal/candidate/runtime telemetry.

React's HTX page uses an explicit `executionMode=PAPER|LIVE` selector (LIVE by
default); its existing `mode=HTX_TREND` URL parameter selects the strategy tab.
The query cache includes executionMode. The static dashboard has a dedicated
HTX panel using its existing PAPER/LIVE toggle. Both show positions, orders and
fill events read-only; no smart-wallet paper-book logic was changed.

## mysql_store.py bypass audit (F4)

The Java Dashboard controller calls HtxTrendDashboardQueryService and its Java
mapper directly; neither invokes Python mysql_store.py. Python is an execution
repository, not an aggregate dashboard API. Strategy name alone is not an
account boundary, even when current PAPER and LIVE strategy names differ.

- `active_position_count` intentionally preserves Java's primary/non-mirror
  scope (the account_id parameter does not broaden that scope).
- `active_direction_position_count`, `active_contract_position`,
  `latest_done_trade`, `pending_orders`, `mirror_position`, and
  `managed_positions` have explicit account identity and now also exclude paper.
  Thus an accidental paper account ID or reused LIVE strategy name cannot return
  simulated execution rows through these LIVE readers.
- `record_position_intent` / `record_order_intent` read back by unique
  position_key/client_order_id; those are write-workflow identity lookups, not
  report readers. `exit_state` is keyed by position ID in its separate state
  table. None is reused by F1. Generic `_one`/`_all` are internal SQL helpers,
  not endpoints; callers must supply their own scope.
- Mirror persistence remains the dedicated paper_db_persist adapter. This patch
  does not install or start it and does not enable any executor/reservation.

Tests: `tests/test_mysql_store_platform.py::test_execution_readers_explicitly_exclude_paper_even_with_live_strategy_name`
checks every listed LIVE reader and confirms no commits. The existing
`tests/test_paper_db_persist.py::test_default_live_summary_excludes_paper` still
executes the reference LIVE summary on mixed rows.

## Local evidence and remaining deployment boundary (F5)

`coin-strategy-web/src/test/resources/paper-readonly-fixture.sql` is **H2-only**:
6 PAPER fills / 6 FILLED orders / 3 DONE positions, plus primary and TradeX
rows and an adversarial paper ledger row using LIVE's strategy name.
`HtxTrendPaperReadonlyTest` executes real mapper SQL and the GET controller,
checks fees/net/terminal source, rejects writes, and proves removing PAPER does
not change LIVE counts/PnL. `HtxTrendLiveScopeTest` checks actual performance,
cooldown and open-count SQL. Frontend contracts live in
`coin-strategy-frontend/tests/paper-readonly.test.mjs`.

Historical correction scripts under `sql/htx/2026-09-24*` and
`2026-10-07b__htx_trend_structure_lifecycle_and_final_pnl.sql` must not be rerun
indiscriminately over PAPER. Source tests do not establish deployed safety.
See [future acceptance checklist](paper-readonly-production-acceptance.md).
No production SSH, install, deployment, DDL or DML was performed.
