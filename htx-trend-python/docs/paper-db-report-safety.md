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

Only `paper:htx-trend-paper-primary` / `HTX_TREND_15M_V2` is accepted. The paper
account is created idempotently and disabled for LIVE discovery. Existing enabled
paper accounts are rejected rather than modified. All three mirror tables have
paper account id/label; position/client keys use `paper-`. Existing identities
owned by another account are rejected. The account lock serializes writers;
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

The following existing consumers still need review/migration before enabling a
shared-table mirror in any deployment; this delivery intentionally leaves their
business logic unchanged as allowed by P4:

- `coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/persistence/mapper/HtxTrendPositionMapper.java`:
  `trendPerformanceSince`, `trendPerformanceForBucket` (null account means all),
  `latestDoneAt`, and unscoped active/count queries. Add paper exclusions for
  default LIVE scope, including cooldown/performance consumers.
- `htx-trend/HTX_TREND_ENSEMBLE_OBSERVATION_RUNBOOK.md`: raw position observation
  query needs an explicit LIVE filter; do not copy its unfiltered query into a
  daily report.
- `htx-trend-python/src/htx_trend/mysql_store.py`: audit unscoped position readers
  separately from account/strategy-scoped execution queries before reuse in
  aggregate reports. Do not assume strategy_name alone excludes PAPER.
- `sql/htx/2026-09-24__htx_trend_execution_identity_ledger.sql`,
  `sql/htx/2026-09-24b__htx_trend_execution_identity_backfill_correction.sql`,
  `sql/htx/2026-10-07b__htx_trend_structure_lifecycle_and_final_pnl.sql`:
  historical data corrections must not be rerun indiscriminately over PAPER.

These are source findings, not evidence that deployed reports are already safe.
The account SQL beside this document is review-only and was not executed.
