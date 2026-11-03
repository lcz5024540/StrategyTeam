# COS evidence archive
Python archive and explicit replay retrieval, independent of trading workers.

2026-11-03 · Codex · implementation branch `codex/cos-evidence-archive` from `support_htx_python@2502786d`.

The archive contains exact original files, compressed into content-addressed packs, a compressed SQLite catalogue and a hash-addressed JSON manifest. It uses private STANDARD storage with SSE-COS. The catalogue maps relative source paths to pack offsets, encodings, lengths, original and stored SHA256 hashes. CAS rows include semantic digests and explicit dependency edges. A full restore downloads each referenced pack once; a selected restore uses Range GET. Neither requires cold-storage restoration.

[Data flow and state contract](../docs/exec-plans/evidence/cos-archive-20261103/design.md).

## Install and configure

Use an independent Python >=3.11 environment, `pip install '.[cos]'`, from this directory. The optional official SDK is not loaded by normal trading execution. Source entries in `config/archive/cos-server-sources.json` are the user-approved server paths. Credential JSON must be owned by the executing user with mode0600 and contain `SecretId` / `SecretKey`; its values must never enter config, Git, logs or commands. On the authorized host the existing file is `/home/ubuntu/coinstrategy-cos-pilot/credentials.json`.

Create a private workspace outside all source directories. Each immutable job name denotes one snapshot. Keep the workspace's `blobs.sqlite3`, per-job SQLite, source index snapshot and receipt for efficient resume/incremental archive. Never use the original evidence outbox for COS delivery. Workspace metadata grows with distinct files; approximately one indexed record per archived version rather than per polling cycle. No automatic cleanup is installed.

## Archive an approved source

```bash
htx-cos-archive --config config/archive/cos-server-sources.json \
  --credentials /home/ubuntu/coinstrategy-cos-pilot/credentials.json \
  --workspace /home/ubuntu/coinstrategy-cos-archive/work \
  archive --source paper-15m --job paper-15m-20261103
```

Same job resumes the exact frozen inventory and acknowledges only fully GET-verified packs. A completed job returns its stored receipt, not a new remote audit. Use a new job name for later source growth; already archived file bytes reuse the cache after their referenced packs are rechecked by full GET. Processing uses an OS single-writer lock, bounded pack buffers, a default5GiB free-space guard,512MiB per-original-file cap and64MiB decoded semantic-object cap. Run with `ionice -c3 nice -n10` on the server. A failed job emits one redacted error and exits; rerun the same command after correcting availability/capacity. There is no background daemon, cron, restart of trading services or automatic deletion.

The final line supplies a `manifests/<hash>.json` key under `coinstrategy/archive/v1/`. Retain this key or the local receipt as the replay dataset identifier. Publishing a manifest means all its files and explicit CAS references are present and verified. It does not assert historical market completeness or profitability.

## Retrieve for replay

```bash
htx-cos-archive --config config/archive/cos-server-sources.json \
  --credentials /home/ubuntu/coinstrategy-cos-pilot/credentials.json \
  --workspace /home/ubuntu/coinstrategy-cos-archive/work \
  list --manifest manifests/REPLACE_WITH_RECEIPT_HASH.json --limit 20

htx-cos-archive --config config/archive/cos-server-sources.json \
  --credentials /home/ubuntu/coinstrategy-cos-pilot/credentials.json \
  --workspace /home/ubuntu/coinstrategy-cos-archive/work \
  restore --manifest manifests/REPLACE_WITH_RECEIPT_HASH.json \
  --destination /home/ubuntu/replay-restored/paper-15m --all
```

For selected files replace `--all` with repeatable `--path objects/ab/<digest>.json.gz`. `--cas <digest>` automatically restores that CAS object and its dependency closure, compatible with `research.store.read_object` / `FileEvidenceStore.verify`. A selective CAS restore deliberately does not install an index that claims absent files. For catalogue-driven dataset queries or an entire capture, restore its complete snapshot before using the existing replay reader. A partial capture is suitable only for specifically selected segment references, not a complete-capture audit. Restores reject mismatching existing files, unsafe paths and symlinks, and publish verified bytes atomically. Whole datasets are not atomically renamed: do not start replay until the command succeeds and emits `.cos-restore-complete-<manifest-hash>.json` with `scope=full_snapshot` for a full restore. Selected restores have `scope=selection`. Interrupted restores can be resumed; do not treat partial directories as complete. Use a separate research directory with sufficient disk space; avoid restoring millions of files onto the constrained production disk just to inspect a few objects.

## Source boundaries and growth

- CAS: SQLite backup API, then immutable objects including valid unindexed historical objects. No copying live WAL/SHM. Missing referenced files prevent manifest publication. Source index/checkpoints and orphan status retain their original meaning.
- Capture: newline-complete `segments.jsonl` prefix and its sealed segments; config/status and explicitly named public pool provenance. Later appends are outside this snapshot. Sealed segments not in the frozen index are also retained and listed as unindexed; the original incomplete-coverage meaning is preserved, not silently upgraded. No smoke/.partial or arbitrary siblings.
- History and Parquet: explicit roots/extensions, inventory fingerprints plus per-file stability checks. Changed source files require a new job. They are not advertised as a transactional multi-file database snapshot.
- Orders remain in MySQL and authoritative PAPER state remains local; both are outside migration. Credentials, env, current releases, admissions and backups are excluded.

Already-gzipped members retain exact bytes; uncompressed JSON and SQLite are gzip-compressed. Duplicate file bytes reuse pack locations across snapshots. Compact-v2 reduces upstream repetition; this tool does not change collector frequency, retention or strategy semantics. New market data and new revisions still increase COS use. Snapshot frequency should be chosen from measured retained growth and replay needs, not every strategy poll.

**Uploading alone does not free local disk.** This delivery retains all source files as authorized. No local retirement or scheduled offload has been enabled. A later separately approved cleanup must verify references, restore/rollback requirements and active readers before removing named local objects. No COS DeleteObject permission is used or needed.

## Rollback and limits

Stop only the archive command to pause migration. Existing trading processes/packages, MySQL, PAPER JSON and local evidence readers are unaffected. Keep published packs/catalogues; an unfinished upload has no final manifest and the same job can recover. Do not change the namespace while reusing a workspace. Empty packs are not published. A corrupt source or oversized file fails closed; do not lower validation to force a successful receipt. Hash integrity is not a substitute for market-data quality checks.

Grouped capture compatibility: the approved long capture uses `htx-realtime-grouped-v1` from source commit `aaf3352c`, while the chosen baseline initially read only v1 event arrays. This branch adds the lossless grouped decoder to the existing replay readers; writer behavior and trading evaluation are unchanged. Original grouping, event order, raw fields and segment hashes survive retrieval.
