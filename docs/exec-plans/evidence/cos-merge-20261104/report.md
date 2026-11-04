# COS release merged and upload enabled
2026-11-04; completed; Codex.

Integrated feature codex/15m-cos-verified-release@9b784843 into the latest
support_htx_python@5fd066de. Merge commit28af5647 preserves both parents without
conflict. The only existing code diff is package.py regular-file filtering;
existing target SBOX behavior and archive/sealing/storage APIs are preserved.

## Validation
1196 Python tests passed,8 isolated/optional DB tests skipped,190.21seconds.
Candidate0.1.7-realtime-candidate-28af5647 built and verified PASS; tar SHA
2e4deeac47c296807487bb2f9fa65717ad31f72ded471624d281fa59fb05b536.
Upload CLI, archive/release units, timers and Python maintenance script are present;
bytecode directories excluded. Candidate was not deployed over any worker.
Five-axis review and DL084/085 regression checks passed, no blocker.

## Actual upload activation
The existing authorized public15m uploader was already active/enabled. Explicitly
ran systemctl enable --now coinstrategy-15m-cos-archive.timer and triggered its
service. At14:35:55 CST it uploaded and verified epoch164 (290 files) and165
(221 files), pending_generations=0, process exit0. The service is oneshot, so
inactive after success is expected; the enabled timer provides repeated execution.
At14:36:53 the capture PID2595135 remained active, data package869660ab,
trading=false, stop_reason=null, local165940401B (~158MiB). Maintenance tool stays
895663f9; release timer also enabled. This task neither explains nor changes the
operator restart that occurred since the midnight PID snapshot.

The existing cadence override remains5minutes after the previous upload completion,
with up to30seconds jitter. Release checks run separately after completion under
the existing root-bound approval. COS data remain. No source-scope enlargement,
new credential storage, production SQL or strategy activation.

## Subsequent deployments
Target packages now contain the merged maintenance tools. The current server's
external units, exact policies, protected credential path and enable marker are
already installed and survive repository merges. On another server they must be
explicitly configured; checkout/merge itself is not a deployment command.
Do not replace the pinned maintenance dependency or expand its root implicitly.

Evidence: python-regression.txt, package-verification.json, package-inclusion.json,
server-upload.json and review.md. Rollback uses the existing deployment runbook:
disable only the specific maintenance timer if needed; preserve COS objects,
control records and exact release plans. Previously released local copies require
verified restore before use by a reader that requires all-local history.
