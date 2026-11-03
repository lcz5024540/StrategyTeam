# COS archive migration delivery
Approved snapshot transfer, retrieval verification and operating boundaries.

Author: Codex; date: 2026-11-03; status: complete for this migration batch.

Base: support_htx_python@2502786db1c7915018232d594d84d4eaab21c052. Branch: codex/cos-evidence-archive. Python implementation:32055415.

## Verified result

1,695,098 original files in7 snapshots -> 373 COS objects, 11,496,173,222 bytes (10.71 GiB), including catalogues/manifests. Source logical bytes: 15,370,138,959; total stored-byte reduction: 25.2%. Already-gzipped source files are counted at their actual compressed sizes.

Private bucket rayquant-strategy-1253235498, na-siliconvalley; prefix coinstrategy/archive/v1/. STANDARD + SSE-COS/AES256. PUT/full GET checksums precede acknowledgement; final manifests publish last.

| Dataset | Files | Source GiB | Member GiB | Catalogue MiB | Inventory end CST |
|---|---:|---:|---:|---:|---|
| paper-15m-20261103 | 633,912 | 3.060 | 2.892 | 316.03 | 11-03 18:03:48 |
| paper-4h-20261103 | 410,799 | 2.521 | 2.416 | 93.54 | 11-03 18:25:34 |
| shared-klines-20261103 | 637,885 | 4.382 | 1.105 | 146.60 | 11-03 18:39:51 |
| capture-realtime-20261103 | 6,554 | 1.999 | 1.998 | 1.46 | 11-03 17:37:35 |
| capture-long-20261103 | 1,852 | 1.644 | 1.644 | 0.39 | 11-03 17:42:11 |
| legacy-research-20261103 | 3,825 | 0.700 | 0.098 | 0.47 | 11-03 17:37:10 |
| parquet-datasets-20261103 | 271 | 0.008 | 0.008 | 0.03 | 11-03 17:35:06 |

Member bytes sum references and may include duplicates; actual COS total comes from listing objects. Shared evidence includes6 exact referenced objects recovered after the initial scan.

## Retrieval and regression

- Full271-file Parquet/supporting-file restore: all original SHA256 values match.
- Real15m/4H exit-decision, candle and dependency selections:327 files each; shared evidence:12 files. Source hashes and explicit reference closure verified. All3 restored source SQLite snapshots pass quick_check.
- Each formal capture verified9 real observation references through existing read_reference. Both event-array and grouped-v1 formats preserve original order, raw fields and hashes.
- Unsupported grouped data initially stopped publication. The decoder from actual capture source aaf3352c was added to readers, and the same job resumed successfully. No writer or trading behavior change.
- Python37 archive tests; full1033 passed/7 isolated MySQL environment cases skipped. compileall, diff-check and governance21 structural checks pass.

## Source retention and working space

All original evidence, MySQL, env, current releases and paper-state remain. Only generated raw snapshot.sqlite3 work copies were released after COS recoverability and local gzip/raw equality checks:3,081,908,224 bytes (2.87 GiB). Gzip catalogues, frozen source index.sqlite3 copies, receipts and blob cache remain. Same-job repeat does not rebuild released work copies; a fresh real shared-evidence restore passed afterwards.

Workspace decreased from about4.6 to1.7 GiB. At19:01 CST, free root disk was15,755,169,792 bytes (14.67 GiB). At20:25 reconnect, read-only df showed about75% used and15 GiB available. Original-data retirement has NOT been performed.

Excluded: funding CSV, smoke captures, env-bearing backups, .partial files, live WAL/SHM/locks and unclassified operational files. No production SQL migration, trading restart/deployment or automatic cleanup schedule.

## Snapshot and growth limits

This is a batch of time-bounded snapshots, not continuous synchronization. CAS uses SQLite backup, object scan and exact-reference completion; capture uses a complete-line index boundary; tree data retain stable file bytes. Missing historical data or unknown costs were not invented.

At18:02 the long capture had142 newer sealed segments outside its snapshot, and zero omitted segments created before the inventory boundary. Later appends require a new archive job. No periodic uploader was installed. Payload blocks deduplicate by exact bytes; each new job still adds a full catalogue. Measure metadata growth and select bounded snapshot/staging retention before enabling continuous operation; never snapshot per strategy poll.

## Retrieval and receipts

Server command: /home/ubuntu/coinstrategy-cos-archive/run. list queries a manifest; restore --path retrieves files; restore --cas retrieves an evidence root and its closure; restore --all retrieves a snapshot. Wait for the restore completion marker and correct full_snapshot/selection scope before replay.

[Usage](../../../../htx-trend-python/COS-ARCHIVE.md) | [Contract and diagram](design.md) | [Full receipts/object list](server/archive-summary-after-compact.json) | [Work-copy release](server/staging-compaction.jsonl) | [Python regression](python-regression.txt) | [Review](review.md)

| Dataset | Manifest key under the prefix |
|---|---|
| paper-15m-20261103 | `manifests/b18b4c4b12aba7842a3d59b2d276b5d7ffe0bf9f77c72748b079b4cd63d33392.json` |
| paper-4h-20261103 | `manifests/bee77d04f0b3cf701f7337b60e4e8666662d31379ec27ee62108af65c38bb42f.json` |
| shared-klines-20261103 | `manifests/f3684da210f909d11fc58408968cdfc6b90d2c3b104c7f31c72433c558138fdf.json` |
| capture-realtime-20261103 | `manifests/177007298121f5f37db5ac5370cb14b9de62cee26703aa980b0bba36490c8165.json` |
| capture-long-20261103 | `manifests/3deec6a61a768448ea6bd3a7edc4fcc877ea5d58b48babd0d1654828a6448192.json` |
| legacy-research-20261103 | `manifests/8419ec1c23b4841d366928ed720817c7cb66300f88c5e09cde3874802b6a4623.json` |
| parquet-datasets-20261103 | `manifests/5aaae82f676ac6a1f36e00e63b1dcc56205f2b397df2da5a2e493b30cd4cfbed.json` |

These checks establish storage integrity and readable inputs, not a profitability backtest. Existing historical-availability and cost-completeness gates still apply.
