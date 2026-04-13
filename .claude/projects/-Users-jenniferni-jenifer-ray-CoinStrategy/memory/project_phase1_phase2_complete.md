---
name: Polymarket Phase 1–4 Complete
description: All four implementation phases of POLYMARKET_SPEC_PLAN.md are complete and BUILD SUCCESS in worktree sharp-zhukovsky
type: project
---

All four phases from `polymarket/POLYMARKET_SPEC_PLAN.md` are implemented and compile successfully in worktree `sharp-zhukovsky` (branch `claude/sharp-zhukovsky`).

**Phase 1 — Entity Intelligence**: Wallet→Entity clustering (FundingSource + BehaviorSimilarity), WalletEntityMap, WalletEntityFeatureSnapshot, WalletFollowCandidate tables/entities/mappers, EntityAwareConsensusService, dedupedEntityCount replaces distinctWallets in consensus signals.

**Phase 2 — Market Features**: MarketFeatureSnapshot table, AVRCalculator, PriceJumpDetector, FreshWalletDetector, LiquidityGate, MarketFeatureCollector, MarketScannerJob (15-min scheduled).

**Phase 3 — Signal Scoring + Wash Trade + Alerting**:
- MarketSignalRecord table, RuleBasedSignalEngine (9 rules, score 0-10), CompositeSignalScorer (wired to AlertService)
- WashTradeFlag table, SelfTradeDetector, HighFreqPairDetector, EffectiveVolumeCalculator
- AlertService (Telegram), AlertThrottler, ResolutionRiskAnalyzer (heuristic + UMA), wired into MarketScannerJob

**Phase 4 — Backtesting + AVR Strategy + APIs**:
- backtest_run/trade/metrics tables, BacktestEngine (async thread), HistoricalDataLoader, SimulatedExecutor, BacktestMetricsCalculator
- AvrSignalStrategy (paper mode, 15-min scheduled, disabled by default)
- MarketScannerController: GET /anomalies, GET /history, POST /trigger, POST /backtest, GET /backtest/{runId}

**Why:** Core goal was fixing the "address inflation bug" where one entity's N wallets falsely inflated consensus signal strength.

**How to apply:** Phase 5 (on-chain integration + service extraction) is not yet started. Alert sending requires `smart-wallet.alert.telegram.enabled=true` + bot token config. AVR strategy requires `smart-wallet.signal.avr-strategy.enabled=true`.
