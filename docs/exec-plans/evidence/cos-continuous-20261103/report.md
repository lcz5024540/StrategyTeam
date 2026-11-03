# COS continuous archive delivery
2026-11-03 · Python · implementation27a1ca8a · previous archive branch9bcbf669, target baseline2502786d.

Implemented independent periodic incremental upload, bounded on-demand replay cache, and exact-list verified retirement for frozen datasets. No trading process dependency, production timer activation or production original deletion.

## Delivered behavior
- Independent run-once + systemd service/timer templates; default disabled. Per-source persistent generation/retry/backoff/BLOCKED reset, bounded logs and derived workspace cleanup. Suggested initial interval6h; full audit7d or32delta generations.
- Unchanged files inherit previously verified pack locations only with4-field fingerprints; no-op publishes no new object. v2 delta catalogue avoids repeating the large full ref graph, while full checkpoints bound lookup chain. v1 stays readable. Periodic discovery still scans names/stats, with full audits rechecking historical payloads.
- cache-run / ReplayCache: select paths/CAS closure/full snapshot, Range GET, SHA checks, offline hit, LRU capacity, lifetime flock inherited by the replay child. Cache is separate from all trading roots and outputs.
- retire-preview generates SQLite exact list plus approval hash; active/unproven roots blocked. Frozen complete source must match the archive boundary. Apply reads hash-verified sealed plan bytes, binds each member to remote metadata, verifies every remote byte, rechecks source identities and journals every unlink. No timer runs this command.

## Verification
- [Full Python regression](python-regression.txt):1049 passed,7 skipped (isolated MySQL environment unavailable).
- [Archive tests](targeted-tests.txt):53 passed; retry, offline/corrupt cache, quota/LRU, surviving child lock, WAL tampering, unarchived member, stale source, revisions/removed-path delta composition, old pending fingerprints and depth checkpoint.
- [Real COS drill](real-cos-drill.json): first upload, no-change same manifest, revision v2 delta, offline cache, injected network outage and persistent DEGRADED, retry recovery, isolated two-file retirement309B and complete restoration. Scheduling timestamps are accelerated/injected; not a multi-hour production soak.
- Real archived grouped capture segment contains3967events; restored via cache and read_reference validates a real event against its digest. This is reader/integrity verification, not profitability replay.
- [Package checks](package-check.json):15m compact-v2 PAPER,4H compact-v2 PAPER and LIVE builds verify, include COS modules/config/docs/units, schedule disabled. Packages built from implementation27a1ca8a are validation artifacts, not installed production releases.
- compileall and git diff --check pass. Governance21 structural checks pass. [Review](review.md) records fixed findings, defect regression and scope.

## Capacity and limitations
Previous15m full catalogue331378816B prompted v2 deltas. Real small drill delta1434B is functional evidence, not a projection of production savings. Source growth remains a function of real data; no retention policy deletes remote history. Working full catalogues can be large, so free-space preflight remains necessary. Defaults are not an observed throughput SLA.

All production sources remain intact; only isolated generated samples were removed in the drill. Active CAS/capture must remain local while checkpoints/ledger/readers depend on them. Safe automatic active-root rotation/pins is a separate change, not quietly assumed by this implementation. This follow-up therefore does not claim server disk growth is fully bounded.

## Rollout / remaining actions
No unresolved code-merge blocker after review. User authorized conditional merge; final target hash is recorded in governance delivery trace. To deploy, follow [COS-ARCHIVE.md](../../../../htx-trend-python/COS-ARCHIVE.md), build from final merged commit, install the archive process separately, run a supervised capacity/receipt check, then explicitly enable its timer. Production original deletion requires approving the concrete frozen-source list. Disable the timer to roll back scheduling; retain the new reader for v2 manifests (the older reader only understands v1).
