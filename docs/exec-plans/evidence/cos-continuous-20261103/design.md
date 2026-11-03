# COS continuous archive contract
2026-11-03 · Codex · Python-only follow-up to approved archive plan · base9bcbf669.

The approved design is an independent archive process, research-only cache, and verified frozen-source retirement. Trading imports, timing, local evidence and authoritative ledgers stay unchanged. Production timer activation and original deletion are outside this delivery; merge is authorized if checks pass.

## Identity / state / entry contract
- Schedule: namespace+exact sources; source+persisted generation is the job. IDLE→PENDING→VERIFIED→IDLE. PUT/GET or disk failures retain the same job, bounded exponential backoff and first/count/type; WAITING remains DEGRADED. SnapshotChanged is BLOCKED, explicit reset keeps failed inventory. Default6h, full audit7d or32delta generations. OS lock serializes ticks, separate archive workspace lock serializes uploads. No worker lock/outbox/credentials are imported.
- Catalogue: v1 full snapshots preserved. Incremental inheritance requires exact path/origin/semantic and inode/size/mtime/ctime; source index always freshly frozen. Existing semantic refs copied; missing explicit dependencies still discovered. No-op reuses manifest; v2 delta holds only new/changed rows+refs and deleted paths, with immutable parent. Reader validates namespace/source/count/closure/hash/length and bounded128 chain; applies oldest→newest. Scheduler checkpoints at32 or7d. Full checks scan all names/stats (orphan safety); not O(new files) discovery. Raw source bytes and old versions persist remotely.
- Cache: namespace+manifest+selection canonical hash. EMPTY→RESTORING→READY→LEASED→READY/EVICTED. Original SHA on hit, no remote call for a complete hit; max bytes+free-space reserve, LRU derived copies only. Whole replay lease uses flock; subprocess inherits FD. Results outside cache. Full vs selection scope remains explicit.
- Retirement: namespace+source+policy+manifest+approved SQLite bytes. Preview only by default. ACTIVE/unproven roots protected in full; frozen root fresh boundary must equal archive. Apply rejects sidecars, copies hash-verified plan and opens immutable SQLite; crosschecks eligible path/hash/size against COS catalogue; verifies all remote members; checks source fingerprints, metadata and new files; durable intent precedes unlink and fsync. Interrupted apply resumes known intents only. Protected index/state retained; retired root is not runnable. Operator attestation must prove all current/future recovery readers stopped; no automatic process-discovery claim.

## Interface map (Explore evidence)
archive.cli→Scheduler.tick→archive/prepare; archive→inherit_verified/delta_catalogue→CosBackend.publish; ReplayCache.lease→open_catalogue/restore→existing research/public_capture readers; retire-preview/apply→approved_plan/restore(verify_only). No reverse edge from paper_worker/runtime/LIVE into archive.

FileEvidenceStore.put recursively verifies references; CompactEvidence.capture reads checkpoint.window_ref; confirm_ledger reads EVIDENCE_RESULT; capture iter_records/SegmentRecorder.verify need indexed segment files. Thus active roots cannot be safely retired by file age or outbox.delivered. Producer rotation/pins would be a separately scoped architecture change.

## Verification matrix
| Case | Required |
|---|---|
| unchanged / revision / removed path / v1+v2 chain | no-op zero puts; old version retained; reconstructed closure correct |
| failure after PUT / restart / backoff / invalid snapshot | same job or explicit BLOCKED/reset; no false health |
| cache offline / corrupted / too large / concurrent / surviving child | offline hit; corruption reject/rebuild; quota; lease blocks eviction |
| active retirement / stale boundary / changed source / unknown member / WAL | no original unlink |
| frozen approved / outage / retry after unlink | exact restore first; no delete on outage; journal idempotent |
| packaging / full regression / actual COS | disabled templates in packages; no trading imports; true remote API evidence |

[Independent flow](archive-continuous.drawio). Standard codegraph service is not available in this task; direct Explore symbol/reader tracing is recorded above, no graph result fabricated.
