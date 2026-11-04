# Independent opportunity strategy review

Conclusion: Python candidate implementation accepted for branch delivery; no
production or profitability approval. Zero remaining code-review blockers.

## Scope

Base `5fd066de4b44c1e6de77120abfb163150bebd006`, branch
`codex/opportunity-selection`. The change exceeds 100 lines because discovery,
durable triggering, opt-in integration, outcome estimation and same-framework
replay must agree on identity and inputs. Protocol, implementation and verification
are separate checkpoints; unrelated business logic is not included.

## Five review axes

- Correctness: legacy direction gates remain on old engines only. New geometry
  uses its frozen 32-bar basis; revisions invalidate rather than revive identities.
  Public quote activation time and retired epochs are checked. New model ownership
  is registered for the shared exit path. Ranking/evidence failure blocks new risk
  after management has run. Submit-time account/depth checks remain mandatory.
- Tests: full Python suite 1205 passed, 8 skipped. Additional candidate coverage
  checks both sides/families, higher-low pullback, duplicate/late events, frozen
  revisions, restart, missing optional data, exact evidence closure, empirical
  sample restrictions, entry-off management, normal pullback exit evaluation and
  failed ranking persistence. Existing broker/partial/unknown/reconciliation,
  continuation, PAPER/4H/LIVE and package regressions are included in the full suite.
  The 8 opt-in integration tests were skipped without a disposable MySQL fixture;
  this is not a claim of fresh server/DB validation. No SQL changes were made.
- Design: the new candidate uses explicit `create_engine` dispatch. Original
  parameter payload/fingerprint remains unchanged. Direct legacy generator entry
  points reject the new candidate instead of silently applying old direction rules.
  Candidate version, family, variant and statistical artifact are carried in
  position context. All variants keep execution/holding/accounting constant.
- Style/naming: Python only, explicit opportunity/family names, no fake EMA
  candidate or score-to-probability conversion. Legacy `java_entry_contract.py`
  is an existing Python module name, not Java development. New runtime modules
  are isolated from research diagnostics except through the shared replay Actor.
- Performance/security: no network/credential call in the selector, no per-tick
  EMA/pivot recalculation; geometry is generated at frame refresh. Public smoke
  constructs only a credential-free public client. COS access remains outside the
  trading loop. Candidate LIVE is explicitly rejected; no production service,
  account, configuration, data retention or credential was changed.

## Defect regression

DL-082 (auxiliary failures must not skip holding management): management still
precedes capture/selection and failure-injection tests confirm entry blocking.
DL-085 (package input type filtering): release enumeration was not changed; full
package tests and candidate build/verify pass. No recurrence identified.

Closed findings:

- Required, `opportunity_replay.interval_metrics`: newly appended boundary and
  same-millisecond marks must not replace the final settlement mark. Fixed by
  `(timestamp, observation order)` selection; explicit regression and sum-of-periods
  assertions pass. Recorded as governance DL-086. Never deployed.
- Required, `worker._preflight` / ranking evidence: the 4H feature source and actual
  decision references must participate in the CAS closure, not just appear in
  payload text. Fixed and verified by `test_selection_reference_closure_includes_4h_and_actual_decision`.

## Financial evidence limits

`synthetic-ablation.json` is a deliberately assumed sequence including one entry
and one adverse hard-stop exit. All four predeclared variants discover the same
4 unique opportunities; each opens and closes once. Modeled net is −2.82788U,
fees 0.02288U, drawdown 4.75190U. These figures exercise accounting and same-time
event handling; they say nothing about historical or future strategy returns.

The real one-shot ETH public read produces 12 candidates: 6 breakout/retest,
3 trend pullback, 3 range rejection. Six original/normalized candle objects are
retained and hash-verified. It contains no execution outcomes. The older committed
research prefix excludes execution depth and its local temporary payload is absent.
No fake backtest or trained probability was substituted. The report is explicitly
RESEARCH_ONLY and LIVE admission remains unavailable for this candidate.

No empirical artifact is shipped: supplied data cannot establish out-of-sample
calibration. Cold-start direction/book only break equal-net-R ties. Future study
must collect/restore full original observations, isolate mature training outcomes,
and evaluate independent periods before changing this choice or promoting a model.
