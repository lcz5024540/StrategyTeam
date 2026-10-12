# LIVE hotfix — PyMySQL `LIKE` percent in `latest_breadth_stats` (P0)

## Symptom

Formal LIVE worker (`htx-trend-worker@htx-trend-live-primary`) stays **active** but every tick fails; `strategy_runtime_heartbeat` shows **FAILED** with:

```text
ValueError: unsupported format character 't' (0x74) at index 132
```

No candidates or fills.

## Root cause

`latest_breadth_stats` SQL includes literal `LIKE '%trend=LONG%'` (and similar). PyMySQL applies Python `%` formatting when binding parameters; `%t` in `%trend=` is interpreted as an invalid placeholder.

## Fix

Double literal percent signs in the query string (`%%` → `%` after binding) for all breadth `LIKE` patterns.

## Ops handoff

1. Install tarball from `artifacts/htx-trend-python/` (see `HANDOFF.md` SHA256).
2. **No new env keys** — same `htx-python.env` / formal LIVE template as `0.1.1-live`.
3. Redeploy `current` symlink per standard procedure; restart `htx-trend-worker@htx-trend-live-primary`.
4. Confirm heartbeats return to **OK** and breadth-driven sizing still runs.

## Rollback

Redeploy package built from commit `05395708e496e490aa2c0f5a03bf0e94c43214e7` (pre-hotfix baseline on this lineage).

## Build (maintainers)

```bash
cd htx-trend-python
SKIP_PACKAGE_VERIFY=1 python3 scripts/package.py --version 0.1.1-live --branch cursor/pymysql-breadth-like-escape-38f9
cp dist/htx-trend-python-0.1.1-live-<short-sha>.tar.gz* ../artifacts/htx-trend-python/
```
