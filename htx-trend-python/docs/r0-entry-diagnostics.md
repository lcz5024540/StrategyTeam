# R0 entry rejection diagnostics

R0 observes the existing entry pipeline. It does not enable 4hour candidates,
change policies, queue eligibility, execution, or decision hashes. In particular,
`candidates()` still requires `15min`. Its period check precedes the signal checks,
so an unsupported period is reported even when the ensemble signal is FLAT.
Signal/factor observation writes retain their existing behavior.

`sql/2026-10-14__htx_trend_entry_diagnosis.sql` defines the separate diagnostic
ledger. Applying that migration and deploying are separate operational actions;
the worker never executes DDL. Missing-table and other diagnostic write errors
log the exception class and continue. Rejected rows are not added to
`htx_trend_candidate_signal` by diagnostics.

The unique key is `(instance_key, contract_code, period, bar_time)`. `bar_time`
is the completed candle's **open epoch in seconds**. When scanning fails before
candle evidence is available, it is the expected completed candle's open epoch
from the existing close-grace scheduler. Insufficient-history scans use the last
available candle, or the expected epoch for an empty window.

A row initially without a rejection can acquire a queue or execution rejection.
Upserts preserve the first rejection, including across restarts. Capacity/window
codes describe the first blockage, not a claim that the candidate was removed:
pending retention and expiration remain governed by existing code. A selected
candidate with no blocking stage has NULL stage/code; an accepted execution has
stage EXEC and NULL reject_code. EXEC failures retain the executor's stage code.
Policy flags include a representative rejected model, exact confirmation string,
raw score and matching thresholds. JSON remains valid and at most 512 characters;
`truncated=true` marks omitted fields.

`timeframe_profile` stays NULL: R0 does not infer an R1 profile or change the
existing submit default. `package_version` is installed distribution metadata,
or NULL when running uninstalled source. `market_regime_source` is BREADTH only
when the existing breadth sample meets its minimum; otherwise UNCLASSIFIED.
R0 does not introduce an anchor-only ranking mode.

Heartbeat details preserve the existing prefix, then append `period`,
`rawCandidates`, `policySelected`, and `rej` (or aggregated `rejCounts` for more
than eight rejections or limited space). The final string is at most 1024
characters. Counts/rejections are retained on cached ticks until a new scan;
`candidate_count` retains its existing `len(ranked)` meaning. Diagnostic writes
on repeated ticks are idempotent. No retention job or production migration is
included; the 24-hour production observation acceptance remains an operations
step after separately authorized deployment.
