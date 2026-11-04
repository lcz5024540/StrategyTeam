# JD source handoff — 2026-11-04

Preserves seven public-market research Python scripts and one proposed strategy design. The monetary amounts in code/design are hypothetical backtest parameters, not actual account balances. No market-result CSV/JSON, account records, logs, credentials or runtime state is included. Scripts that fetch public market data were not executed. Scheme B/C are preserved on separate migration branches.

These files are inert review snapshots with a `.source` suffix. Bytes are preserved except where MANIFEST.json explicitly records replacing a potential service-header literal with a synthetic test fixture. The suffix prevents accidental inclusion in builds, test discovery or instruction loading. This branch does not change active application code, workflows, services, deployment settings or installed hooks. No deployment is requested.

`MANIFEST.json` records each original relative path, baseline, source SHA256 and published SHA256. To continue work, fetch this branch on Mac mini, inspect a snapshot, verify its hash, and copy selected code into a separate working branch after comparing it with the current application. Do not bulk-copy historical files over current main. Private data and local credentials remain outside this Git handoff. References to intentionally withheld local files may be unresolved.
