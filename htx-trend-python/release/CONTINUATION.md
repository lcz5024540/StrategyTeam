# 15m PAPER continuation candidate

Status: candidate implementation; **not approved for deployment**. Python only.
Base branch: support_htx_python, implementation base c6d7467d8f388d42c8325a2bb164b29dfeb2637c.

## Runtime switches and ownership

| Group | HTX_PAPER_CONTINUATION_VERSION | HTX_PAPER_COMPLETED_TRADE_COOLDOWN | HTX_LIVE_CONTINUATION_VERSION |
|---|---|---|---|
| A / existing default | baseline-v4 | false | baseline-v4 (default when unset) |
| B / cooldown control | baseline-v4 | true | — (LIVE has no cooldown env) |
| C / confirmation only | confirm-v1 | false | confirm-v1 |
| D / linked candidate | linked-v1 | false | linked-v1 |

These are explicit **15m** controls. PAPER reads `HTX_PAPER_*` only. **LIVE 15m** reads `HTX_LIVE_CONTINUATION_VERSION` (same allowed values: `baseline-v4`, `confirm-v1`, `linked-v1`). LIVE does **not** honor `HTX_PAPER_CONTINUATION_VERSION` alone. Non-default controls reject 4H before opening its ledger (PAPER) or reject LIVE worker startup when a non-baseline LIVE continuation is configured alongside enabled 4H LIVE entry.

**LIVE scope (this delivery):** exit-side confirm-v1 (and linked-v1 exit policy) on 15m `HTX_TREND_15M_V2` via `LivePositionManager` only. LIVE entry does **not** implement PAPER linked reentry / cooldown B; do not treat LIVE as full PAPER continuation parity.

New C/D positions save continuation_version in their context. Missing version means the old v4 policy. Management reads the position version, not the current entry switch; setting the switch back to baseline does not silently reinterpret existing candidate positions. New decision/version CAS includes the entry/execution/maker/continuation configuration and worker pool/order, without repeating windows. Exit CAS also retains the already-fetched book/spec; management does not add a funding API request or change cadence.

C/D count two distinct contiguous closed 15m candles beyond the **pre-evaluation** protection. First qualifying trigger needs armed protection, executable-price breach and >=1 ATR adverse move. Subsequent adjacent closes beyond protection continue the count. Recovery, gap or weakening resets it. The second close also needs the current executable quote beyond that pre-evaluation protection. Newly computed protection applies only to later evaluations. Hard stop/model invalidation/opposite signal and existing weakening exits retain priority.

D guards the next same-contract, same-side entry after a complete protective exit (including complete profit-at-risk exits). The new candidate bar must follow the exit bar. Long close and bid must exceed max(exit protection, exit-bar high); short close and ask must be below min(exit protection, exit-bar low). Existing entry gates still apply. Partial exits do not arm the reentry guard; model-name changes cannot bypass it. Missing historical boundary facts are recovered only from the exact full-close CAS/run/time/window; otherwise entry is refused with REENTRY_EVIDENCE_MISSING.

B uses the last fully closed trade from this instance, including all its partial exits, and subtracts actual opening fees from its exit-fee-net realized_pnl. Unknown costs produce COOLDOWN_COST_UNKNOWN with compact input evidence. Current threshold/configuration formulas are unchanged. D never uses B's time cooldown.

## Replay input and commands

The old scan_replay locked BTC/ETH study remains unchanged. New CLI:

```bash
python -m htx_trend.continuation_replay --audit-ledger /path/to/frozen-ledger.json.gz --out /path/to/audit
python -m htx_trend.continuation_replay --tape /path/to/tape.json.gz --sha256 ACTUAL_FILE_SHA256 --evidence-root /path/to/read-only-cas --out /path/to/comparison
```

The CLI never constructs a production ledger, API client or MySQL connection. Input failures produce an ineligible report. --out is a new local analysis directory, never a production evidence/state directory.

Tape schema `htx-continuation-tape-v1`:

- `provenance`: OBSERVED or ASSUMPTION_ONLY; a label alone is insufficient for acceptance.
- `initial_at`, `initial_ledger`, `initial_mark`: authoritative starting state and executable marks for every initial open position. Mark timestamps must match the snapshot.
- `configurations`: content-hash -> complete worker, entry, execution, maker dataclass fields. No hidden defaults. Each frame's `config_ref` selects the actual pool and its iteration order at that time.
- `frames`: strictly increasing aware `at`, `config_ref`, and `markets`. Each pool contract supplies contiguous 160-candle 15min/60min windows with observed_at, two-sided book with sizes/observed_at, spec with size/tick/status/observed_at, and funding quote/observed_at. Open positions outside the pool still require valuation quotes. No future revisions, forming candles, missing/future/stale executable quotes, or fictitious counterfactual funding quotes.
- `clock_start`: the original C-clock timestamp. State continues across 70/30 boundaries; no empty-account restart. The boundary is the first observed evaluation at/after 70% of the time span, explicitly reported.
- Optional verified settlements require unique id, exact observed evaluation at, contract, rate and mark price. Non-observed settlement-time exposure is not guessed. Funding enters economic equity/position PnL; decision-budget semantics remain those of the original PAPER worker.
- `reference_fills`: canonical natural-key fills, bound to `reference_source_ref`'s end-of-window ledger and derived with `events()`, not generated from variant A to claim historical agreement.

For a formal source check, `initial_source_ref` (`paper_replay_initial`), every frame's `source_ref` (`paper_replay_frame`), `inventory_source_ref` (`paper_replay_inventory`), `reference_source_ref` (`paper_replay_reference_ledger`) and any `funding_source_ref` (`paper_replay_funding_schedule`) must resolve through hash-verified CAS. Inventory lists all actual evaluation frames, so removing a frame fails verification. These wrappers alone do **not** prove original observation: frame `origin_refs[contract]` must also match original compact entry/exit decision, raw/window and strategy-version closures, with exact book/spec/funding/config/window values. Historical missing inputs cannot be filled from current metadata or later revisions. Use trusted native capture provenance; do not relabel test fixtures as observations.

Outputs: report.json (all candidate and gate outcomes, exact fill mismatches, per-group positions, sampled peak/giveback and checks), report.md, A/B/C/D equity/fill/position CSVs. Equity includes realized net, open executable-side PnL, paid fees, known funding and estimated exit costs. Both complete scopes and diagnostic/holdout segments must satisfy D net>=A, DD<=A, fees<A and short same-direction roundtrips<A. Frame, baseline, cost or provenance failures deny eligibility independently of performance.

## Actual evidence limits and result

The frozen 2026-10-29 23:22 ledger contains 15,797 ENTRY_SCAN records but only 3,194 compact links, and 1,187 EXIT_DECISION records but only 124 compact links. It has 178 fills and 86 positions. Historical exit inputs have only a one-sided executable reference and do not supply a complete counterfactual entry book/funding/configuration. The current 79-contract identity is not a pool history; settled funding is unknown.

The delivered historical audit is NOT_ELIGIBLE. The runnable locked-candle tape intentionally uses synthetic book/funding and is ASSUMPTION_ONLY; its four-group returns are mechanics tests, not evidence of improved historical profitability. No TSLAX/AVGO counterfactual profit is invented from incomplete data. Previously observed cases remain explicit mandatory targets once a complete tape is available.

## Deployment and rollback boundary

No production SQL, service restart, env update, historical evidence deletion or LIVE authorization is part of this delivery. An existing package build remains default-A; source availability is not approval to enable D.

Before any separately approved deployment, require the formal gates above, freeze a clean-commit package with source hash, and retain the original state/evidence/env backup and C-clock. Record new policy results separately from existing C-clock strategy performance. The current evidence gap means this candidate cannot be promoted yet.

To stop new D entries on a future approved installation, set the entry switch to baseline while running this candidate-capable binary. Existing candidate positions keep their version. Do not roll the binary back to c6d7467d while any C/D positions remain: that binary ignores the new version fields. Full binary rollback is allowed only after candidate positions have closed or a separately reviewed state migration; never overwrite current state with an old snapshot to simulate a rollback. No automatic rollback or trade closure is installed.
