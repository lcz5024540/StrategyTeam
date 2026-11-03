# COS evidence archive
Python archive and explicit replay retrieval, independent of trading workers.

2026-11-03 · Codex · implementation branch `codex/cos-evidence-archive` from `support_htx_python@2502786d`.

The archive contains exact original files, compressed into content-addressed packs, a compressed SQLite catalogue and a hash-addressed JSON manifest. It uses private STANDARD storage with SSE-COS. The catalogue maps relative source paths to pack offsets, encodings, lengths, original and stored SHA256 hashes. CAS rows include semantic digests and explicit dependency edges. A full restore downloads each referenced pack once; a selected restore uses Range GET. Neither requires cold-storage restoration.

[Data flow and state contract](../docs/exec-plans/evidence/cos-archive-20261103/design.md).

## Install and configure

Use an independent Python >=3.11 environment, `pip install '.[cos]'`, from this directory. The optional official SDK is not loaded by normal trading execution. Source entries in `config/archive/cos-server-sources.json` are the user-approved server paths. Credential JSON must be owned by the executing user with mode0600 and contain `SecretId` / `SecretKey`; its values must never enter config, Git, logs or commands. On the authorized host the existing file is `/home/ubuntu/coinstrategy-cos-pilot/credentials.json`.

Create a private workspace outside all source directories. Each immutable job name denotes one snapshot. Keep the workspace's `blobs.sqlite3`, per-job compressed catalogue, source index snapshot and receipt for efficient resume/incremental archive. Unfinished jobs also require their uncompressed SQLite inventory. Never use the original evidence outbox for COS delivery. The global blob cache grows with distinct file bytes. Each job also retains a full inventory and catalogue, so frequent new snapshots add metadata even when payloads are reused. No automatic staging cleanup is installed; measure this overhead before selecting a continuous schedule, and never create a full snapshot per strategy poll.

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

## Authorized host convenience command

The isolated server tool lives at `/home/ubuntu/coinstrategy-cos-archive` (directory0700). `/home/ubuntu/coinstrategy-cos-archive/run list --manifest manifests/<receipt-hash>.json` and `run restore --manifest manifests/<receipt-hash>.json --destination /home/ubuntu/replay-restored/<dataset> --cas <root-hash>` reuse the existing restricted credentials without putting their values in a command. `run archive --source <configured-source> --job <new-snapshot-name>` is an explicit future snapshot; no timer is installed.

`tools/archive/verify_migration.py` reproduces real archive validation against source snapshots: chosen CAS roots plus closure, temporary SQLite quick-check, original-file hashes, and capture read_reference using real event hashes. Its selections are integrity checks, not a profitability replay.

## Release completed staging copies

`run compact-staging --job paper-15m-20261103` verifies the published manifest/catalogue through COS and compares the local gzip catalogue against the raw `snapshot.sqlite3`, then releases only that generated raw work copy. It retains the compressed catalogue, receipt, frozen `index.sqlite3`, global blob cache and every source evidence file. Repeating the original archive command returns the same receipt without rebuilding the released inventory. Unfinished jobs or differing copies fail closed. This is an explicit archive-workspace operation, not production evidence cleanup; no automatic retention schedule is installed.

## Continuous archive (2026-11-03 follow-up)

The **independent** `run-once` command implements persistent scheduling. It does not run from any trading worker, import its lock, or alter its evidence/ledger. Network failures affect only archive jobs. Use a separate `continuous-work` directory, never an evidence root. Installation and activation are separate from merging or upgrading a trading package.

Configuration defaults: `schedule.enabled=false`, interval21600s (6h), full audit604800s (7d); minimum interval900s. These are conservative configuration defaults, not measured production throughput guarantees. A systemd timer dispatches every15min; only due sources run. Per-source durable pending job survives failures and restarts. Backoff starts60s, doubles, capped at the source interval. Failure output contains exception type, first failure and count; no signed URLs/credentials. Unchanged source/namespace identity is required; after intentionally changing source configuration use a new workspace. A tree fingerprint change raises SnapshotChanged and marks the source BLOCKED. After investigating, `reset-pending --source NAME --reason "investigation record"` supersedes only that pending identity, preserving failed inventory and a reset record. The next tick creates a new boundary; transient network failures retain the same job. Do not clear missing-data or corruption errors without investigation.

Incremental means **payload reuse**, not O(new files) discovery: each round still scans names/stats and takes a consistent index snapshot to retain orphan coverage. Unchanged inode/size/mtime/ctime and original path inherit verified hashes, pack locations and reference edges from the previous authenticated catalogue. Newly modified objects undergo full byte/semantic validation and PUT/GET verification. Metadata/index is freshly read. No-change rounds publish **zero new COS objects** and retain the old manifest. Changed rounds publish v2 delta catalogues (changed/new rows, their references, and removed-path tombstones), linking an immutable parent manifest. Reads materialize the authenticated chain. Weekly or32-generation full checkpoints bound chain depth; the reader rejects chains over128. Old v1 manifests remain readable. The pre-follow-up reader cannot open new v2 deltas; rollback of the archive tool must retain the new reader for those manifests. Catalogue metadata and the local `blobs.sqlite3` still grow with data. Weekly/32-generation full audits re-read sources and GET referenced packs; an explicit `archive` without `--previous` also does a full audit. Incremental success is not a fresh audit of every historical remote pack.

After success the scheduler retains its newest receipt per source and removes only its own derived inventory/catalogue/index copies; remote catalogues and original sources remain. The next round loads the previous manifest from COS. Pending/failed jobs are retained. `scheduler/state.json` is bounded by source count and supplies manifest, last_success, next_run, failures, first_failure, error_type and cleanup_error. Disk <5GiB reserve fails this process. Cleanup errors are separate from successful archive acknowledgement and require attention; unknown files are preserved.

### Deployment checklist (not executed by this delivery)

1. Build/freeze from the merged commit using the existing `release/package.py` flow. Packages include Python archive code, this document, disabled example source configuration, and `release/cos/` units. Install into a **separate** archive directory `/opt/coinstrategy/cos-archive/current`, create its independent venv and install `'.[cos]'`. Do not install COS credentials in a package.
2. Review source paths on that server and copy configuration to `/etc/coinstrategy/cos-archive.json`. Keep `retirement` entries ACTIVE. Create `/home/ubuntu/coinstrategy-cos-archive/continuous-work`, owned by ubuntu,0700. Retain the existing0600 scoped credential file. The service sandbox writes only the independent workspace; source trees are read-only. No runtime auto deletion is configured.
3. Run `htx-cos-archive --config ... --credentials ... --workspace ... run-once` with schedule enabled for a supervised tick; check verified receipts, source growth, free space and duration. Inspect logs/status for FAILED and cleanup_error. A permanently failing source remains pending/BLOCKED instead of silently skipping missing data. Waiting through backoff remains DEGRADED until success.
4. Only after deployment approval, install both supplied units, set `schedule.enabled=true`, create `/etc/coinstrategy/cos-archive.enabled` and enable the timer. Disabling the timer or removing the enable marker stops future dispatches without touching trading. Systemd resource controls limit archive CPU/memory/IO. Review memory limit against the largest capture before activation.
5. No trading service restart is necessary. Roll back by disabling this timer and using prior archive code in a separate workspace; v1 manifests remain readable. Never downgrade an active job in place or reuse a cache for a different namespace.

### Replay on demand with a bounded local cache

`cache-run` restores selected paths or a CAS root plus its full reference closure, holds a process lock for the **entire child command**, and passes the leased directory via literal `{data}` substitution. No shell expansion is used. Full capture replay requires `--all`; a selected segment is only suitable for selected-reference readers. The command below uses an existing offline reader, not a profitability backtest:

```bash
htx-cos-archive --config config/archive/cos-server-sources.json \
  --credentials /path/to/0600-credentials.json --workspace /research/archive-work \
  cache-run --cache /research/cos-cache --max-cache-gib 2 \
  --manifest manifests/RECEIPT_HASH.json --cas CAS_ROOT_HASH -- \
  python -c 'import sys; from pathlib import Path; from htx_trend.research.store import read_object; print(read_object(Path(sys.argv[1]), sys.argv[2])["kind"])' '{data}' CAS_ROOT_HASH
```

Python integration: `with ReplayCache(backend, root, max_bytes=...).lease(manifest, cas=[root_hash]) as data: ...`. Keep all replay reads inside the context; if spawning a subprocess, pass `pass_fds=(cache.lease_fd,)` so a surviving child retains the lease. The cache-run CLI does this automatically. The cache has a private ownership marker, namespace/manifest/selection identity, original-file SHA verification on every hit, capacity check before download and LRU eviction. Ready cache hits work with COS disconnected. Concurrent readers serialize rather than evict one another; a second process receives a lock error and can retry. Failed/partial downloads never become ready. Cached input is immutable: write research outputs to a separate directory. Capacity includes cached files and metadata, but filesystem inode/allocated-block overhead and temporary catalogues/packs are additionally constrained by the free-space reserve. Choose the research host/cache size for the selected dataset; do not restore10GiB onto a2GiB cache.

### Verified historical release rules

`retire-preview --source NAME --manifest KEY --output /research/retirement-plan.sqlite3` is read-only for the source. It reports eligible bytes, blocked reasons and a SHA256 approval key. The SQLite plan enumerates every archived path, raw hash, size and current file fingerprint; inspect it with `SELECT path,size,blocked FROM files`.

**ACTIVE/unproven CAS and captures are fully protected.** Current CAS checkpoints, ledger references, `put()->verify(refs)` and capture indexes require local files, even after positions close. No age threshold or outbox-delivered flag overrides that. MySQL, paper-state, credentials/env, index/WAL/SHM, locks, unfinished fragments and unclassified metadata are protected. This phase does not implement producer rotation/pins, so it cannot safely bound the growth of still-active source directories by deleting their old objects.

For an actually frozen, no-longer-read dataset only: an operator must verify all producers/readers/recovery consumers have stopped using that root, remove it from the continuous archive schedule (use a new schedule workspace for changed configuration), then set its separate retirement policy to `{"state":"FROZEN","no_local_readers":true,"attestation":"approved change/evidence identifier"}`. This is an operator declaration, **not automatic process discovery**. Preview checks a fresh complete source inventory against the archived boundary; new/unarchived files or changed indexes block release. Do not mark a currently used root FROZEN just because its old files are archived.

After a human approves that exact list, use `retire-apply --source NAME --plan ... --approval-sha256 ...`. The tool rejects SQLite sidecars and reads an immutable private copy of the exact approved plan bytes. It binds every eligible path/hash/size to the selected remote manifest and rechecks policy, all eligible file identities, protected metadata fingerprints and newly created files; verifies **all remote original bytes** (one pack/member at a time); records durable unlink intents; then removes only eligible approved files. A crash after unlink resumes using the exact intent. Source changes or COS corruption stop the operation. `.cos-retired.json` and a workspace receipt retain the restore mapping. Protected index files remain for forensics but do **not** make the retired directory usable. This marker is advisory: legacy workers do not enforce it, so never point one at a retired root. Restore `--all` to a fresh directory and validate existing readers before reusing the dataset. Restore validation covers byte fidelity, not market-data or fee completeness.

No timer invokes retire-apply. No production original-file retirement has been performed in this follow-up.

## 活跃源分段封存

新增显式、默认关闭的代成员/pin/lease机制，见 [SEGMENT-SEALING.md](SEGMENT-SEALING.md)。只有已封存、远端校验成功且无热依赖的具体批准清单可释放。timer仍仅上传，不按文件年龄删除。旧CAS/LIVE未纳管目录继续完整保护。
