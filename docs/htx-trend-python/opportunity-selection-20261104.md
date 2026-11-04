# Opportunity-first candidate, independent Python implementation

Baseline: `support_htx_python@5fd066de4b44c1e6de77120abfb163150bebd006`.
Branch: `codex/opportunity-selection`. No deployment or merge is implied.

## Frozen protocol

The runtime lifecycle, IOC execution, ledger, reconciliation, position ownership,
linked trend exits and settlement remain shared. The default candidate and its
fingerprint remain unchanged. Select `OPPORTUNITY_SELECTION_V1` explicitly in an
independent PAPER instance; candidate/version fingerprints follow each position.

Discovery uses closed 15m candles only: all 20/32-bar boundaries and confirmed
two-left/two-right pivots, both sides. Breakout/retest uses resistance/support;
pullback uses a higher-low/lower-high pivot sequence; remaining support/resistance
rejections form the range family. Aliases share one economic identity. No future
pivot, screenshot average entry, or unavailable revision is an input.

Targets are the nearest visible opposing structural level beyond the entry zone.
An outer-boundary breakout without such a level uses the width of its own frozen
32-bar box as an explicitly modeled projection. Rejections without an opposing
level are recorded as NO_TARGET. Targets, ATR and geometry freeze when registered;
data revisions invalidate the old opportunity, without creating a new identity.
Stops retain the shared retest-extreme/band and 1.25 ATR floor. Two distinct trades
and a contemporaneous executable quote confirm recovery. No single-keyword trigger.

Direction is a soft feature from complete, point-in-time 4H EMA20/60 data;
unavailable 4H is UNKNOWN (no 1H substitution). Book imbalance uses current public
depth within the same symmetric ATR band; it is neither a fill prediction nor
evidence that displayed liquidity will remain. Missing features never imply zero
confidence or a fabricated probability.

Initial minimal economic filter: positive net reward; configurable min_net_r=0
for this candidate only. Existing operational safety, freshness, exposure, spread,
funding, depth, slippage, chase, sizing and account limits remain mandatory.
Structure/direction/book/both variants share discovery, costs and exit policy.
Without an independently fitted model, ranking is net R followed by selected
feature tie-breaks, labeled UNCALIBRATED; it is not a win-probability estimate.
Optional empirical ranking uses mature fully costed closed-trade net-R outcomes,
minimum sample support, Wilson uncertainty and a frozen training cutoff. It may
not use open outcomes, target-first labels, incomplete costs, or future labels.

All opportunities, rejected geometry, trigger states, features, selection and
execution decisions are retained. Ranking competes only within the same observed
worker batch, with no artificial wait. Every submit rechecks live budget/depth.
Calibration alone cannot authorize production or prove future profitability.

## Verification sequence (WIP=1)

- [x] M1 baseline, isolated branch and protocol.
- [x] M2 discovery, selection, calibration and deterministic tests.
- [x] M3 opt-in lifecycle integration and backwards compatibility.
- [x] M4 identical-framework ablation replay and explicit data limitations.
- [x] M5 full Python regression, review, runbook and delivery evidence.

The existing committed real research prefix excludes execution depth and cannot
prove net performance. Synthetic tapes are technical tests only. Formal selection
requires original point-in-time depth, contract metadata, funding schedule, costs,
latency and configuration; retrieve sealed COS data through the verified offline
cache, not through the transaction loop.

## Delivery evidence

Full Python: 1205 passed, 8 optional MySQL skips. Real public ETH:12 candidates,6 raw/window CAS objects verified. Synthetic four-way replay includes entry and hard-stop settlement; no profit approval. See [review](evidence/opportunity-selection-20261104/review.md) and [runbook](opportunity-selection-runbook-20261104.md).
