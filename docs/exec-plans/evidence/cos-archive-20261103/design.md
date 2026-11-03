# COS archive v1 implementation contract
2026-11-03 · Python · user-approved five data classes · baseline 2502786db1c7915018232d594d84d4eaab21c052.

Purpose: preserve original replay bytes and explicit reference closure in immediately readable STANDARD COS. Existing strategy/store/ledger code is unchanged. Download is explicit into a separate research directory. No local retirement, lifecycle, daemon, production activation or key widening.

## Identity and states (before implementation)
File identity = SHA256 of exact original file bytes; gzip files retain their bytes; other files become independently compressed gzip members. Pack identity = SHA256 of concatenated members, with per-member offsets, stored and original hashes. Job = persistent snapshot directory, source root and source type immutable. CAS inventory boundary includes the index backup plus the scan interval; tree files are individually stable-read, not a transactional cross-file snapshot. Global cache is scoped to bucket/region/prefix. Manifest identity = SHA256 of canonical JSON; catalogue hash pins all paths, members and references. Index timestamp is observational, not source event time.

SNAPSHOT -> PACKING -> VERIFIED -> PUBLISHED. SQLite backup pins the indexed inventory; a bounded filesystem scan also retains unindexed historical CAS objects; files publish before source index commit, so indexed immutable objects are readable. Capture inventory freezes a newline-complete segments.jsonl prefix, preserves sealed unindexed segments with their incomplete-coverage semantics, never .partial files. Tree inventory pins inode/size/mtime; changes require a new snapshot. Missing references are recovered only from their explicit immutable hashes; absent or malformed dependencies stop publication. An interrupted snapshot is discarded only inside the tool's private staging and rebuilt; an interrupted pack retries from the last acknowledged batch. Uploaded-but-unacknowledged packs are addressed by their bytes and safely reused after full GET validation. PUBLISHED jobs resume idempotently. Fresh snapshots reuse acknowledged file blobs. OS flock rejects concurrent writers to one archive workspace. No TTL, no changing source outbox or checkpoints.

## Verification matrix
| Entry/state | Required observation |
|---|---|
| First archive / all types | exact restore bytes, consistent index, valid closure |
| Retry after failed PUT/GET | no manifest, source intact; same job resumes |
| Success / repeat | same manifest, no added data packs |
| New snapshot / unchanged data | blob reuse, snapshot metadata may grow |
| Concurrent archive | lock rejects second writer |
| Corrupt/missing/unknown input | fails closed, no publish |
| Appending capture | bounded complete prefix, records beyond boundary excluded |
| Restore / conflicting target / unsafe path | verified atomic writes; no overwrite or traversal |
| Remote corruption / missing range | digest failure, no published local member |
| Existing replay readers | CAS get/verify and capture references read restored bytes |

## Data scope
Three CAS roots (15m PAPER,4H PAPER,shared collector) plus consistent index.sqlite3 snapshots; two named formal capture roots (realtime-20261103,long-20261103-aaf3352c); htx-trade/research and htx-trend-py-state/data with explicit extension allowlist. Funding CSV, smoke roots, env backups, .partial, live WAL/SHM, account/state/credentials excluded. Running sources are point-in-time snapshots; later appends require another explicit archive run. Snapshot completion proves storage integrity, not historical market completeness or strategy profitability.

## Code navigation evidence
Explore source review: research/store.py FileEvidenceStore.put publishes fsynced CAS before SQLite; read_object supports .json/.json.gz and verifies uncompressed SHA; outbox is MySQL delivery and is not reused. realtime/storage.py SegmentRecorder.flush publishes immutable gzip before appending/fsyncing segments.jsonl; public_capture.iter_records validates segment SHA/count. PaperFileLedger owns local PAPER JSON recovery; MySQL remains its mirror. No changes to these paths. [Archive flow](diagrams/archive-flowchart.drawio).

Real source compatibility finding: capture-long uses htx-realtime-grouped-v1 from aaf3352c. Exact upstream decoder semantics are added to existing replay readers (read_reference, iter_records, SegmentRecorder.verify); no writer/strategy change. First attempt safely failed schema validation before publication; retry succeeds only after grouping/order/count validation. Full restore completion has an explicit local receipt, not a whole-directory atomicity claim.
