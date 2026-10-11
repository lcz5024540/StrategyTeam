# Changelog

All notable changes to the HTX Trend Research System will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
- Position sizing: 0.5% risk per trade, ≤1.5% total risk, ≤3 positions
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
