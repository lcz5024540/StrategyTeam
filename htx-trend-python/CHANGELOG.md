# Changelog

All notable changes to the HTX Trend Research System will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.1-probe] - 2026-10-12

### Added
- Separate one-shot live connectivity probe: `python -m htx_trend.probe_1contract open|close|status`.
- Hard qty ≤ 1, `LIVE_1CONTRACT_PROBE` gate, BTC-USDT default (buy open / sell close). Not the formal strategy worker.
- Probe PRIMARY credential loader (`account_role='PRIMARY' AND enabled=1 AND trend_enabled=1`), ENC-only RSA-OAEP, no env key fallback, no MIRROR rows.
- systemd `htx-trend-probe-1contract@.service` (oneshot). Formal R0001 risk params unchanged (5 / 2% / 10%).

## [0.1.0-rc.13] - 2026-10-12

### Changed
- Reverse-check allows only `src/coinstrategy_htx_trend.egg-info/` with the pip 3.12 editable file set (`PKG-INFO`, `SOURCES.txt`, `top_level.txt`, `dependency_links.txt`, `requires.txt`, `entry_points.txt`, optional `not_zip_safe`). Other egg-info dirs, extra `.py`/`.pth`, or subdirs fail. `.venv` is not excluded. Package-root `.pytest_cache/` is excluded.
- CLI entry (`python -m htx_trend.research`) verifies package integrity before importing `cli`. Deleting `cli.py` reports `missing`.
- `apply_bad_ticks` raises if `incomplete`/`non_comparable`/`bad_tick` are absent (reachable official freeze P1).
- Freeze `--funding-gaps` is required and is the file hashed into the manifest. A claimed hash that differs from that file raises. Runner loads the registry explicitly, recomputes sha256, and refuses a mismatch or a missing manifest hash (including SMOKE).
- Deploy layout: `releases/<version>` + `current` symlink, venv `/opt/coinstrategy/htx-trend-py-venv`, state `/opt/coinstrategy/htx-trend-py-state`. Install is `pip install --require-hashes -r requirements.lock` with no `pip install -e`.

### Testing
- Counterexamples for evil egg-info, extra `.py` in egg-info, in-package `.venv`, deleted `cli.py` → missing, apply_bad_ticks missing columns, claimed/tampered funding-gaps hash, layout verify gate.

## [0.1.0-rc.12] - 2026-10-12

### Changed
- Package integrity now reverse-scans the entire package root. Any file not in `PACKAGE_MANIFEST` fails as `unexpected file`. The only exclusions are a hardcoded constant: `__pycache__` / `*.pyc`, plus runtime write paths `runs/`, `logs/`, `run/`, `data/raw/`, `data/datasets/`, `data/klines/`, `ledger.db`.
- `_build_info.py` is listed in `PACKAGE_MANIFEST` and stores `PACKAGE_CONTENT_HASH` (the manifest `content_hash`). The listed SHA256 of `_build_info.py` excludes that one line so the cycle can close. Runtime recomputes the hash and refuses a consistent rewrite of file hashes. Packaged trees require `PACKAGE_MANIFEST`; git mode requires `git` toplevel == package root.
- `require_runtime_integrity` is the first statement of `BacktestRunner.run()`. SMOKE records `package_content_hash=null` and `integrity_verified=false`.
- Funding gaps: consecutive settlements whose interval is not 8h must match `data/quality/funding_gaps.yaml` (`symbol`,`prev`,`next`). Registry rows in-range without a matching interval raise. The file SHA256 enters the frozen manifest hash. Missing file raises.
- `--stitch-point` accepts only repeatable `SYMBOL=TIME`. Bare timestamps and unknown symbols raise. Runner stitch points come only from the frozen manifest.

### Testing
- Counterexamples for added files, shadow package dirs, rewritten manifests, nested git, extracted-package e2e, 10-day funding holes, registered gaps, and stitch-point CLI forms.

## [0.1.0-rc.11] - 2026-10-12

### Changed
- Deleted `DEFAULT_STITCH_POINT` / `DEFAULT_STITCH_POINTS`. Every freeze/stitch symbol must be declared in stitch config (`ISO timestamp` or `none`). A missing symbol raises. `none` keeps every HTX bar and marks zero `non_comparable`.
- Declared stitch points are written into the hashed frozen manifest. Row-count accounting (`in`, `dropped` by registered reason, `out`) is recorded on stitch stats and the written freeze manifest/audit. A count change without a registered reason raises.
- `source` missing/NA raises at funding frame, funding stitch, funding timeline, stitcher sides, and runner load. No `or default_source` / `df.get("source", "htx")`.
- Empty/missing funding raises unless `allow_missing_funding` is explicitly true (then flagged). Bars before the first settlement get `funding_rate=0` and `pre_first_funding=True`; any other funding NaN raises.
- `window_bars` / `non_comparable_window_bars` must be passed explicitly; missing raises. A stitch point after the last bar raises `ValueError` instead of marking a trailing window.
- Runner no longer copies `incomplete` onto `non_comparable`. Flags stay independent; signal skip is `incomplete or non_comparable or bad_tick` at decision time only.
- Non-SMOKE runs from a package verify every `PACKAGE_MANIFEST` SHA256 on disk (mismatch/missing lists the files and refuses). A git checkout without `PACKAGE_MANIFEST` requires a clean `git status --porcelain`. Packaged runs prefer `_build_info` over `git rev-parse`.

### Testing
- Counterexamples from the rc.10 review script: HTX-only 400→400 with declared `none`; undeclared SOL raises; stitch-after-end raises; source/funding/window/runner-flag/package-integrity cases.

## [0.1.0-rc.10] - 2026-10-12

### Changed
- Freeze quality flags: if an upstream flag column exists, it is strictly validated (bool dtype, no NA) and kept as-is; the same column is also recomputed; mismatch raises `ValueError` (no overwrite, no OR-merge). Only absent columns are derived.
- 4h `non_comparable` uses the index window of 30 bars before and after an **actual** stitch point (61 bars). HTX-only symbols with no stitch point have 0 `non_comparable` bars. No default stitch point.
- 1d `non_comparable` uses a fixed UTC 00:00 daily cut (UTC16 raises). A daily bar is `non_comparable` if it contains the stitch point, mixes sources, or is synthesized from any `non_comparable` 4h bar.
- `external` is derived from `source`; missing/NA `source` raises. `stitch_funding_history` no longer fillna's `source`.
- Frozen dataset hashed manifest always records `non_comparable_window_bars: 30`.
- Strategy manifest hash excludes `git_commit` and `package_version` (code_sha256 + params + risk/dataset identity bind the run). C02 requires explicit `enabled: true`. Non-SMOKE `run` refuses unless `config.strategy_manifest_sha256` matches. `package.py` runs `--check` and writes `PACKAGE_MANIFEST` (per-file sha256 + content hash). Ledger stores `_build_info` commit/version/branch and `package_content_hash`.

### Testing
- Fixture freeze (stitched BTC/ETH 4h covering `bad_ticks.yaml`): 61 `non_comparable` per symbol; 11 `bad_tick` rows total. HTX-only 4h: 0 `non_comparable`. Official 10:00 trial freeze lives on the server; ops verifies real data.

| symbol | timeframe | column | rc.9 (re-derive + default stitch / drop upstream) | rc.10 |
| --- | --- | --- | --- | --- |
| BTC-USDT stitched | 4hour | non_comparable | ~61 (time window) or dropped stitch-window | 61 |
| ETH-USDT stitched | 4hour | non_comparable | ~61 | 61 |
| BTC-USDT HTX-only | 4hour | non_comparable | 61 around default 2020-11-01 if that data is present | 0 |
| BTC-USDT + ETH-USDT | 4hour | bad_tick | 11 yaml matches | 11 |
| 1d mixed-source day | 1day | non_comparable | lost if freeze dropped upstream flags | True |
| 1d stitch day UTC00 | 1day | non_comparable | True only if `bar_time == stitch` | True (UTC00 day + 4h window days) |

## [0.1.0-rc.9] - 2026-10-12

### Added
- `strategy_manifest.yaml`: C01–C09 catalog with implemented/enabled/params/code_sha256, risk params, and `manifest_sha256`
- CLI `strategy-manifest --config ... [--write] [--check]`; committed-file drift test
- Ledger columns `strategy_manifest_sha256` / `strategy_manifest` (run fails if the manifest cannot be built)
- `scripts/tag_message.py` prints a v0.1.0 tag-message summary (does not create tags)
- Package notes and `_build_info.STRATEGY_MANIFEST_SHA256`

## [0.1.0-rc.8] - 2026-10-12

### Changed
- Freeze derives the four quality flags (`incomplete`, `non_comparable`, `bad_tick`, `external`) from closed-bar logic, stitch-point window, `bad_ticks.yaml`, and `source`; missing/NA after derivation raises. No `fillna` / default `False`.
- A bar is normal only when each quality flag is strictly boolean `False` (`bool` / `numpy.bool_`); `None`, `NaN`, `"False"`/`"false"`, integer `0`/`1` (int dtype or object mixed with bool), and a missing column all raise.

### Testing
- Freeze raises if derivation leaves NA; freeze output matches stitch / bad-tick / binance-source fixtures
- Parametrized runner tests over all four flag columns for the P0 non-bool cases, plus parquet NaN→None roundtrip

## [0.1.0-rc.7] - 2026-10-12

### Changed
- Quality flags include `external`; runner requires bool dtype and refuses missing/NA/non-bool instead of filling `False`

## [0.1.0-rc.6] - 2026-10-12

### Changed
- rc.6: R0001 risk params changed to 300U / 2% / 10% / 5 positions per user decision; replaces rc.5's 1%/2%/2
- Heartbeat bar-staleness refuses a missing/blank interval instead of defaulting to `4hour`
- Total-risk skip reason is `total_risk_cap_exceeded` (distinct from `max_positions_reached` and `min_contract_exceeds_risk_budget`)

## [0.1.0] - 2026-10-11

### Added
- Initial release of HTX Trend Research System MVP-1
- Complete backtest engine with event-driven architecture (t close → t+1 fill)
- HTX and Binance market data adapters with data stitching (>0.3% deviation flagging)
- Dataset freezing with manifest + SHA256 hash, 60/20/20 train/val/test split
- Test set lock preventing multiple accesses
- Technical indicators: ATR(14/20), EMA(20/60/120), Donchian(42/84/126/168), ADX(14)
- Regime labeling: trend_state × vol_state (9 combinations)
- Condition plugin system: C01 Donchian ensemble, C02 ATR stop
- Strategy state machine: FLAT → ENTRY_PENDING → OPEN → EXIT_PENDING
- Position sizing: 1% risk per trade (300U, max 3U loss), ≤2% total risk, ≤2 positions
- Single-position liquidation check (1.5× stop distance buffer)
- Account-level liquidation check for CROSS margin with all-stops-hit scenario
- Non-strategy position identification in account status
- Four cost scenarios: zero/optimistic/base/conservative
- Per-period funding rate integration
- SQLite ledger: runs, registrations, trials, test_set_locks, approvals
- Approval gate system: PRE_PAPER, PRE_LIVE, BREAKER_RESTART with version checks
- Registration gate: refuses runs without hypothesis/keep_rule/drop_rule
- Trial counter with automatic increment
- Markdown report generation with parent comparison and regime slices
- Fee provider interface with pluggable implementations
- Default fee provider: maker 0.02%/taker 0.06% (HTX Prime 0 base)
- HTX authenticated fee provider stub (requires API key implementation)
- Fee source recording in every run (clearly labeled in reports)
- Ops monitoring: structured JSON logging (run_id/component/event/level)
- Heartbeat system with timestamp + last_processed_bar_time
- Health check CLI: `health` command returns non-zero if stale
- Status interface: positions, PnL, liquidation distance, circuit breaker, approvals, fees
- Version information embedded: version, branch, commit exposed in CLI
- CLI commands: collect, freeze, run, health, status, approve, check-approval
- Reproducible packaging: `make package` produces versioned tarball with SHA256
- Requirements lock file generation
- DELIVERY.md template with rollback instructions

### Testing
- 27 pytest tests covering all core functionality
- Strict determinism test: identical parquet hashes for same config
- Hash change detection: modified bar changes dataset hash
- Dataset refusal test: old dataset_id rejected after data change
- No-lookahead test: truncating future bars doesn't change decision at time t
- All indicator tests with no-lookahead verification
- Fee provider tests
- Approval system tests (create/query/gate check/version validation)
- Account-level liquidation tests
- Ops system tests (logging/heartbeat/status)

### Security
- API keys managed through environment variables (not in repository)
- Fee source clearly labeled in reports (default vs authenticated)
- Approval records require explicit approver identity
- Version checks prevent unauthorized promotion with outdated approvals

### Documentation
- Complete RESEARCH_SYSTEM.md with architecture and usage guide
- MVP1_DELIVERY_REPORT.md with detailed implementation notes
- Inline code documentation with type hints
- Test documentation demonstrating usage patterns
- CHANGELOG.md for version tracking
- Makefile with common tasks

### Infrastructure
- Semantic versioning starting at 0.1.0
- Single source of truth: src/htx_trend/research/_version.py
- Git information embedded in package (commit/branch/dirty flag)
- Automated packaging script with integrity verification
- Makefile: test, package, clean targets

### Notes
- MVP-1 scope: backtest only, no live trading
- BTC/ETH 4h, conditions C01+C02
- HTX MAIN account (CROSS margin), separate sub-account for arbitrage
- Future enhancements: C03-C09 conditions, DSR/PBO, PAPER execution
