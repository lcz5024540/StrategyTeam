# Review and remaining-work audit
2026-11-03 · Codex · Python only · scope: previous COS integration plus continuous follow-up.

Verdict pending final gate/merge. Review is across correctness, tests, design, style, performance/security. No Java/TS changes or regressions are claimed. The change is larger than100lines because scheduler, cache and retirement form one end-to-end delivery; modules and tests keep responsibilities separate.

## Correctness / tests
Resolved pre-delivery findings: sealed SQLite plan read (WAL rejection + private immutable copy); each retirement member bound to the remote manifest; stale source inventory/new-file and metadata checks; backoff remains DEGRADED; invalid tree snapshot becomes BLOCKED with explicit reset; child replay inherits lock FD; old3-field fingerprints do not qualify for inheritance. Dedicated tests reproduce each. SQLite backup/read connections close explicitly so stale local WAL/SHM cannot defeat bounded staging cleanup.

## Design / style
Standalone Python archive imports no worker; cache lease wraps offline command/API. v1 read compatibility; v2 bounded delta chain instead of uploading hundreds of MiB of full refs every period. Existing transaction rules, PAPER recovery, MySQL mirrors and strategy code are unchanged. Operator attestation is explicit, never inferred from age/outbox/done positions. No strategy or original-data deletion scheduler is installed.

## Performance / security
Initial scan and periodic full audit remain O(all source files); incremental avoids unchanged payload reads and full metadata publication. Complete ref closure can require large temporary catalogues, constrained by free-space guard. Scheduler cleans only generated work, limits rounds to6h default, full checkpoint at7d/32generations; no broad blob/archive deletion. Cache quota applies to logical data, physical overhead also needs free space. Secrets remain in server0600 file and errors redact SDK details. Service source filesystem is read-only. No new credential scope.

## Defect regression gate
DL-077 snapshot identities/orphans remains covered by original37tests; new DL-078/079/080 record review findings and associated regressions. No unresolved finding from Explore review after old pending fingerprint guard was added. Explore also ran34rounds proving30→31→32→0 and legacy3-field full→delta readability.

## Remaining work classification
- Required for this code merge: full Python suite, package contents/verify, real COS integration, governance gate and remote commit verification. Results appended in report.
- Production rollout, enabling timer and approving a specific frozen-source deletion list remain explicit operational actions, not implied by merge.
- Active source growth is not solved by archive upload. Active CAS/capture stays protected; producer seal/rotation/pin protocol would be a separately scoped task. Do not claim automatic active-source eviction.
- Profitability backtesting and historical data/fee completeness are independent from storage verification.
