# COS verified local release deployment
2026-11-04; author Codex; deployed and verified.

User approved releasing server-local copies after completed upload verification,
including the proposed ongoing rule for the independent public15m capture root.
COS objects remain. No legacy evidence/MySQL/paper-state/trading changes.

## Verified outcome
At 2026-11-04T00:19:05.081073+08:00, cumulative 2329 server-local payload files / 746952628 bytes
(712.35 MiB) have been released. Capture remains PID2361464,
trading=false, no stop reason, 1230116204 local payload bytes.
Previously at00:05 the source had1468817260B before release; live collection
continued throughout. Zero pending mutations, recorder ready. Remaining verified
local data 1000778334B continue through the timer;
this is not a claim all eligible historical files have already disappeared.

The independent release service ran multiple times, handling real COS reads and
local deletion. First two completed ticks released27623074B and69292496B; later
progress brings the above cumulative total higher. Source-in-use/handoff/revision
conflicts defer work; partial progress is read from durable state. Service exit0
with DEFERRED means safely incomplete, not all files released. Root free space
14302310400B. Payload bytes continue to fluctuate with live input.

After local deletion, 3 actual epoch5 objects were downloaded from COS again:
1012906B,908 events (DEPTH361/BBO538/TRADE9), all hashes match. These files were
absent locally beforehand. No cold-source full-local audit was misreported.

## Version and deployment
Maintenance commit895663f95987d19d294a802ab878d9299199f771,
branch codex/15m-cos-verified-release, tag htx-cos-release-20261104-895663f9.
Tool tar SHA b0d46e26e897a84f73341c3a11e1bb1e74057ded3481242cb6a6e3e0ce544b7c.
Frozen /workspace/frozen/15m-cos-release-895663f9 contains tar/SHA/MANIFEST/install.
Independent current /opt/coinstrategy/15m-cos-maintenance/current.
Original data package0.1.7-realtime-candidate-869660ab unchanged. Maintenance
imports that exact release's sealing API. No strategy hot update or new entries.

Root-owned policy /etc/coinstrategy/15m-cos-release.json binds exact root, COS
namespace and user-20261104-upload-verified-server-local-release authorization.
Writable source is only /home/ubuntu/coinstrategy-data/15m-cos/capture/segments.
Work/control/plans /home/ubuntu/coinstrategy-cos-archive/15m-release-work.
Existing1-9 plans adopted with exact hashes. New plans persist before unlink.
Scope checks include internal schema/root/config/epoch/manifest/namespace.

Timer coinstrategy-15m-cos-release.timer enabled,1minute after last completion;
upload timer independently remains5minutes. One tick max12 generations/retries3,
180s soft budget between API calls/10min systemd cap, CPU50%, memory limits.
Active/pinned/unverified files remain; no deletion by file age.

## Validation and limits
1099 Python tests passed,7 isolated MySQL environment skips.17 wrapper tests plus
existing sealing/capture regression. Fixed pre-deployment scope-misbinding defect
DL-084 and package enumeration __pycache__ failure DL-085; no faulty tool deployed.
No core trading or sealing algorithm changed. Packaging filters regular supported
files so runtime bytecode directories cannot contaminate builds.

Local control DB, descriptors, exact plans and audit metadata still grow. Existing
2GiB capture and5GiB root free-space guard stay enabled. Network outages or
sustained contention can defer reclamation; this is not unlimited-storage assurance.
Formal strategy profitability backtest eligibility is outside this storage check.

## Rollback
Stop/disable the release timer, then stop its service; preserve policy, exact plans,
control journal and COS objects. Disabling maintenance does not recreate removed
files. Existing data/upload services may continue within their capacity guards.
Use existing segment-restore-local and validation before any old reader requiring
all-local history. Do not reset consumer guards or remove control/index files.

Evidence: installation.json, tool-package.json, status-20261104.json,
authorized-release-20261104.jsonl, post-release-read-verification.json,
python-regression.txt. The authoritative deletion history is source .sealing
control SQLite; exceptions can occur after partially committed deletion, so sum
of successful API return values alone undercounts released bytes.
