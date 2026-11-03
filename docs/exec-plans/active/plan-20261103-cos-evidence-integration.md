# COS evidence integration and migration

2026-11-03; Codex; active; WIP=1.

## Goal
Integrate COS on a new branch from latest support_htx_python and archive the user-approved existing datasets without moving authoritative orders out of MySQL.

## Success criteria
Explicit migration inventory approved; Python upload/download restores reference-closed replay datasets with verified hashes; repeat runs do not duplicate data; restart recovers progress; failure retains local data; tests and actual scoped COS API validation pass.

## Scope
New branch codex/cos-evidence-archive from origin/support_htx_python 2502786db1c7915018232d594d84d4eaab21c052. Isolated Git worktree /Users/lichangzheng.ray/Ray/CoinStrategy-cos-evidence-archive. Python only. Existing private STANDARD/SSE-COS bucket rayquant-strategy-1253235498; scoped user already connected. User explicitly asks to decide ambiguous data classes together after inventory. No source deletion, strategy change, live trading activation, database migration or widening credentials. Operational data transfer requires an approved dataset list. Deployment/automatic scheduling follows separately confirmed operational scope.

## Milestones
- [x] M1 Fetch latest target branch and create isolated branch/worktree; actual baseline recorded.
- [x] M2 Read-only inventory complete; user approved first five classes, retaining originals; excludes funding CSV, smoke, env backups and partial fragments.
- [x] M3 Implement approved Python COS archive/read/restore path with stable identity, compression, manifests and bounded buffering.
- [ ] M4 (in progress) Verify approved existing-data transfer and restore; retain originals.
- [ ] M5 Verification / self-check: compatibility, retry/failure/restart matrix, real API, documentation and governance checks.

## Risks and mitigation
Mutable evidence and SQLite WAL files cannot be copied as if frozen: classify separately and preserve snapshot/reference consistency. Secret-bearing environment/approval/account backups cannot be indiscriminately uploaded: inventory names/statistics only; exclude credentials and authoritative state. Upload is not local deletion: no claimed disk relief before separately approved and verified local retirement. Source growth may continue during scan: record scan timestamps and label totals point-in-time.

## Verification
Read-only low-priority filesystem inventory on49.51.250.174; Python source review maps writer/reader semantics to data categories. No retention threshold or source-directory deletion chosen without user decision. User approved scope on 2026-11-03. WIP=1 implementation; state contract/matrix recorded in business docs/exec-plans/evidence/cos-archive-20261103/design.md. Estimate duration only after actual file counts and throughput are known.

2026-11-03 17:35 CST: real COS first dataset271 files,8596109 original bytes, full restore271/271 SHA-equal. 23 targeted tests pass; full regression rerun in isolated venv with declared dependencies. One earlier run failed because shared old venv lacked declared websockets; no strategy code fix implied. M4 sequential migration running with5GiB free guard, bounded packs and original preservation.
