# 15m continuation implementation evidence

Implementation base c6d7467d; branch codex/15m-trend-continuation-optimization. No production mutation.

## Evidence map
- history-audit/report.json: real frozen-ledger coverage counts and NOT_ELIGIBLE blockers.
- assumption-tape.json.gz / tape.sha256: locked historical BTC/ETH candles with explicitly synthetic book/funding.
- assumption-replay/: actual four-worker replay; complete/segment metrics, all scans/rejections, fills and equity CSVs.
- pytest.txt: Python suite output (see last line for actual count).
- public-smoke.json: real public HTX API, candidate-enabled local PAPER worker; zero orders, HEALTHY.
- continuation.drawio: policy and durable-state flow.

## Read-only source review
No codegraph tool was exposed in this session (enabled-tool inventory returned no matches). Source-directed Explore review and call-chain audit used instead:
PaperTrendWorker.tick -> latest_completed / PaperTrendRuntime.scan -> reentry_gate + original evaluate_gate -> PaperExchange.
PaperTrendWorker.tick -> PaperTrendRuntime.manage -> advance_confirmation / decide_continuation -> unchanged decide_v4 -> reduce_position -> atomic checkpoint.
Tape replay -> four independent worker/ledger instances -> every evaluation equity -> full + diagnostic/holdout admission checks.

Review fixes: cross-split drawdown, full profit-at-risk protection reason, exact CAS close/run/time binding, frame inventory, reference-ledger binding, unique exact-time funding events, native-source verification. Historical gaps remain blockers.

## Truthful delivery boundary
Candidate behavior is implemented and tested. Historical net-return/drawdown acceptance has NOT passed: source coverage and settled funding are insufficient. The fixture cannot establish profitability or TSLAX/AVGO retention. No merge, push, deployment or policy enablement occurred.

Runtime and rollback details: ../../../../htx-trend-python/release/CONTINUATION.md
Full binary rollback while candidate positions remain is unsafe; disable new entries using the candidate-capable binary instead.

Final delivery: DELIVERY.md. Unknown funding is null; hypothetical curves explicitly exclude it.
