package com.metaverse.coin.strategy.common.model.arbitrage.entity;

/**
 * 手动触发命令动作常量。
 */
public final class StrategyManualTriggerAction {

    /** 触发指定策略的 runCycle */
    public static final String RUN_CYCLE = "RUN_CYCLE";

    /** 触发指定策略的 runExitScan */
    public static final String EXIT_SCAN = "EXIT_SCAN";

    /** 全局：触发一次告警检查 */
    public static final String ALERT_CHECK = "ALERT_CHECK";

    /** 全局：触发一次健康快照采集 */
    public static final String SNAPSHOT_COLLECT = "SNAPSHOT_COLLECT";

    /** 全局：执行一次 Binance 单交易所资金费率套利（payload 内含参数）。 */
    public static final String FUNDING_ARB_EXECUTE = "FUNDING_ARB_EXECUTE";

    /** SmartWallet：更新单地址人工 override；payload={walletAddress, request{...}}；result_json=SmartWalletProfileEntity */
    public static final String SMART_WALLET_PROFILE_OVERRIDE_UPDATE = "SMART_WALLET_PROFILE_OVERRIDE_UPDATE";

    /** SmartWallet：批量更新地址 override；payload={walletAddresses[], ...}；result_json=SmartWalletProfileBatchOverrideResultView */
    public static final String SMART_WALLET_PROFILE_OVERRIDE_BATCH = "SMART_WALLET_PROFILE_OVERRIDE_BATCH";

    /** SmartWallet：全量同步所有 watchlist 地址持仓（异步长任务，payload={batchSize}） */
    public static final String SMART_WALLET_PROFILES_SYNC_ALL_POSITIONS = "SMART_WALLET_PROFILES_SYNC_ALL_POSITIONS";

    /** SmartWallet：重建 watchlist 画像 metrics（异步长任务，payload={scope, limit}） */
    public static final String SMART_WALLET_PROFILES_METRICS_REBUILD = "SMART_WALLET_PROFILES_METRICS_REBUILD";

    // ── SmartWallet follow-tasks/follow-trades 命令模式接入点 ─────────────────

    /** GET /smart-wallet/follow-tasks/executable；payload={limit, scanLimit}；result_json=List<SmartWalletFollowTaskView> */
    public static final String SMART_WALLET_FOLLOW_TASKS_EXECUTABLE = "SMART_WALLET_FOLLOW_TASKS_EXECUTABLE";

    /** GET /smart-wallet/follow-tasks/manual-allowlist；payload={limit, includeBlocked}；result_json=List<SmartWalletFollowTaskView> */
    public static final String SMART_WALLET_FOLLOW_TASKS_MANUAL_ALLOWLIST = "SMART_WALLET_FOLLOW_TASKS_MANUAL_ALLOWLIST";

    /** GET /smart-wallet/follow-tasks/single-wallet-override；payload=请求参数 Map；result_json=List<SmartWalletFollowTaskView> */
    public static final String SMART_WALLET_FOLLOW_TASKS_SINGLE_WALLET_OVERRIDE = "SMART_WALLET_FOLLOW_TASKS_SINGLE_WALLET_OVERRIDE";

    /** GET /smart-wallet/follow-tasks/opportunities；payload={limit, maxBlockers, includeExecutable}；result_json=List<SmartWalletFollowTaskView> */
    public static final String SMART_WALLET_FOLLOW_TASKS_OPPORTUNITIES = "SMART_WALLET_FOLLOW_TASKS_OPPORTUNITIES";

    /** GET /smart-wallet/follow-tasks/coverage-report；payload={walletAddress, limit}；result_json=List<SmartWalletFollowCoverageReportView> */
    public static final String SMART_WALLET_FOLLOW_TASKS_COVERAGE_REPORT = "SMART_WALLET_FOLLOW_TASKS_COVERAGE_REPORT";

    /** GET /smart-wallet/follow-trades/attribution；payload={groupBy, limit}；result_json=List<FollowTradeAttributionView> */
    public static final String SMART_WALLET_FOLLOW_TRADES_ATTRIBUTION = "SMART_WALLET_FOLLOW_TRADES_ATTRIBUTION";

    /** POST /smart-wallet/follow-tasks/intelligence/reevaluate-deferred；payload={limit}；result_json={limit, updated} */
    public static final String SMART_WALLET_FOLLOW_TASKS_INTELLIGENCE_REEVALUATE_DEFERRED = "SMART_WALLET_FOLLOW_TASKS_INTELLIGENCE_REEVALUATE_DEFERRED";

    /** POST /smart-wallet/follow-tasks/intelligence/replay-recent；payload={limit, includeProcessed}；result_json=summary */
    public static final String SMART_WALLET_FOLLOW_TASKS_INTELLIGENCE_REPLAY_RECENT = "SMART_WALLET_FOLLOW_TASKS_INTELLIGENCE_REPLAY_RECENT";

    /** POST /smart-wallet/follow-tasks/simulate-risk；payload=SmartWalletRiskSimulationRequest；result_json=SmartWalletRiskSimulationResult */
    public static final String SMART_WALLET_FOLLOW_TASKS_SIMULATE_RISK = "SMART_WALLET_FOLLOW_TASKS_SIMULATE_RISK";

    /** POST /smart-wallet/follow-tasks/replay-backtest；payload=SmartWalletReplayBacktestRequest；result_json=SmartWalletReplayBacktestResult */
    public static final String SMART_WALLET_FOLLOW_TASKS_REPLAY_BACKTEST = "SMART_WALLET_FOLLOW_TASKS_REPLAY_BACKTEST";

    /** POST /smart-wallet/follow-tasks/replay-backtest/concentration-summary */
    public static final String SMART_WALLET_FOLLOW_TASKS_REPLAY_BACKTEST_CONCENTRATION = "SMART_WALLET_FOLLOW_TASKS_REPLAY_BACKTEST_CONCENTRATION";

    /** POST /smart-wallet/follow-tasks/process；result_json={processed} */
    public static final String SMART_WALLET_FOLLOW_TASKS_PROCESS = "SMART_WALLET_FOLLOW_TASKS_PROCESS";

    /** POST /smart-wallet/follow-tasks/{followTaskId}/dry-run；payload={followTaskId}；result_json=Map */
    public static final String SMART_WALLET_FOLLOW_TASKS_DRY_RUN = "SMART_WALLET_FOLLOW_TASKS_DRY_RUN";

    /** POST /smart-wallet/follow-tasks/{followTaskId}/execute-live；payload={followTaskId}；result_json=SmartWalletExecutionResult */
    public static final String SMART_WALLET_FOLLOW_TASKS_EXECUTE_LIVE = "SMART_WALLET_FOLLOW_TASKS_EXECUTE_LIVE";

    // ── SmartWallet admin / accounting / settlement / claim / position 命令模式接入点 ─────────────

    /** POST /smart-wallet/admin/reset-data；payload={confirm,includeProfiles,includeRiskArtifacts,includeRuntimeSeeds,includeMetadataCache}；result_json=Map */
    public static final String SMART_WALLET_ADMIN_RESET_DATA = "SMART_WALLET_ADMIN_RESET_DATA";

    /** POST /smart-wallet/admin/recover-state；payload={reason}；result_json=Map */
    public static final String SMART_WALLET_ADMIN_RECOVER_STATE = "SMART_WALLET_ADMIN_RECOVER_STATE";

    /** POST /smart-wallet/accounting/backfill；payload={limit}；result_json={imported} */
    public static final String SMART_WALLET_ACCOUNTING_BACKFILL = "SMART_WALLET_ACCOUNTING_BACKFILL";

    /** POST /smart-wallet/accounting/backfill-market-end-at；payload={limit,refreshCache}；result_json=Map */
    public static final String SMART_WALLET_ACCOUNTING_BACKFILL_MARKET_END_AT = "SMART_WALLET_ACCOUNTING_BACKFILL_MARKET_END_AT";

    /** POST /smart-wallet/accounting/backfill-token-links；payload={limit,refreshCache}；result_json=Map */
    public static final String SMART_WALLET_ACCOUNTING_BACKFILL_TOKEN_LINKS = "SMART_WALLET_ACCOUNTING_BACKFILL_TOKEN_LINKS";

    /** POST /smart-wallet/accounting/repair-premature-settlements；payload={limit,refreshCache}；result_json=PrematureSettlementRepairSummary */
    public static final String SMART_WALLET_ACCOUNTING_REPAIR_PREMATURE_SETTLEMENTS = "SMART_WALLET_ACCOUNTING_REPAIR_PREMATURE_SETTLEMENTS";

    /** POST /smart-wallet/accounting/repair-premature-settlements/by-ids；payload=PrematureSettlementRepairRequest；result_json=PrematureSettlementRepairSummary */
    public static final String SMART_WALLET_ACCOUNTING_REPAIR_PREMATURE_SETTLEMENTS_BY_IDS = "SMART_WALLET_ACCOUNTING_REPAIR_PREMATURE_SETTLEMENTS_BY_IDS";

    /** POST /smart-wallet/accounting/repair-market-ids/by-positions；payload={request,syncAfterRepair,refreshCache}；result_json=Map */
    public static final String SMART_WALLET_ACCOUNTING_REPAIR_MARKET_IDS_BY_POSITIONS = "SMART_WALLET_ACCOUNTING_REPAIR_MARKET_IDS_BY_POSITIONS";

    /** POST /smart-wallet/settlement/sync；payload={positionLimit,marketLimit,refreshCache}；result_json=SettlementSyncSummary */
    public static final String SMART_WALLET_SETTLEMENT_SYNC = "SMART_WALLET_SETTLEMENT_SYNC";

    /** POST /smart-wallet/settlement/review-paper-history；payload={limit,refreshCache}；result_json=HistoricalPaperSettlementReviewView */
    public static final String SMART_WALLET_SETTLEMENT_REVIEW_PAPER = "SMART_WALLET_SETTLEMENT_REVIEW_PAPER";

    /** POST /smart-wallet/settlement/retries/process；payload={limit,refreshCache}；result_json=SettlementSyncSummary */
    public static final String SMART_WALLET_SETTLEMENT_RETRIES_PROCESS = "SMART_WALLET_SETTLEMENT_RETRIES_PROCESS";

    /** POST /smart-wallet/settlement/manual-confirm-local；payload={request,refreshCache}；result_json=SettlementSyncSummary */
    public static final String SMART_WALLET_SETTLEMENT_MANUAL_CONFIRM = "SMART_WALLET_SETTLEMENT_MANUAL_CONFIRM";

    /** POST /smart-wallet/claim/process；payload={limit}；result_json=SmartWalletClaimSummary */
    public static final String SMART_WALLET_CLAIM_PROCESS = "SMART_WALLET_CLAIM_PROCESS";

    /** POST /smart-wallet/claim/retries/process；payload={limit}；result_json=SmartWalletClaimSummary */
    public static final String SMART_WALLET_CLAIM_RETRIES_PROCESS = "SMART_WALLET_CLAIM_RETRIES_PROCESS";

    /** POST /smart-wallet/positions/{positionId}/claim；payload={positionId,confirmExternalClaim}；result_json=SmartWalletClaimResultView */
    public static final String SMART_WALLET_POSITION_CLAIM = "SMART_WALLET_POSITION_CLAIM";

    /** POST /smart-wallet/positions/{positionId}/exit；payload={positionId,request}；result_json=SmartWalletPositionExitResultView */
    public static final String SMART_WALLET_POSITION_EXIT = "SMART_WALLET_POSITION_EXIT";

    // ── SmartWallet trade-orders / trade-events / markets 写命令 ─────────────────

    /** POST /smart-wallet/trade-orders/backfill；payload={limit}；result_json={rebuilt} */
    public static final String SMART_WALLET_TRADE_ORDERS_BACKFILL = "SMART_WALLET_TRADE_ORDERS_BACKFILL";

    /** POST /smart-wallet/trade-orders/repair-stale；payload={}；result_json={repaired} */
    public static final String SMART_WALLET_TRADE_ORDERS_REPAIR_STALE = "SMART_WALLET_TRADE_ORDERS_REPAIR_STALE";

    /** POST /smart-wallet/trade-events/{sourceEventId}/effect-label；payload={sourceEventId}；result_json=SmartWalletTradeEffectLabelEntity */
    public static final String SMART_WALLET_TRADE_EVENTS_EFFECT_LABEL = "SMART_WALLET_TRADE_EVENTS_EFFECT_LABEL";

    /** POST /smart-wallet/trade-events/effect-labels/backfill；payload={limit,missingOnly}；result_json=List<SmartWalletTradeEffectLabelEntity> */
    public static final String SMART_WALLET_TRADE_EVENTS_EFFECT_LABELS_BACKFILL = "SMART_WALLET_TRADE_EVENTS_EFFECT_LABELS_BACKFILL";

    /** POST /smart-wallet/markets/recategorize；payload={}；result_json={updated} */
    public static final String SMART_WALLET_MARKETS_RECATEGORIZE = "SMART_WALLET_MARKETS_RECATEGORIZE";

    // ── SmartWallet polymarket / clob / wallet-features 写命令 ─────────────────

    /** POST /smart-wallet/polymarket/select；payload={}；result_json=SmartWalletSelectionSummary */
    public static final String SMART_WALLET_POLYMARKET_SELECT = "SMART_WALLET_POLYMARKET_SELECT";

    /** POST /smart-wallet/polymarket/analyze；payload={}；result_json=PolymarketDiscoverySummary */
    public static final String SMART_WALLET_POLYMARKET_ANALYZE = "SMART_WALLET_POLYMARKET_ANALYZE";

    /** POST /smart-wallet/polymarket/watchlist/admit；payload={walletAddress}；result_json={walletAddress,admitted,message} */
    public static final String SMART_WALLET_POLYMARKET_WATCHLIST_ADMIT = "SMART_WALLET_POLYMARKET_WATCHLIST_ADMIT";

    /** POST /smart-wallet/polymarket/sync-events；payload={}；result_json={imported} */
    public static final String SMART_WALLET_POLYMARKET_SYNC_EVENTS = "SMART_WALLET_POLYMARKET_SYNC_EVENTS";

    /** POST /smart-wallet/polymarket/closed-positions/backfill-local；payload={directory,limit,dryRun}；result_json=LocalBackfillResult */
    public static final String SMART_WALLET_POLYMARKET_CLOSED_POSITIONS_BACKFILL_LOCAL = "SMART_WALLET_POLYMARKET_CLOSED_POSITIONS_BACKFILL_LOCAL";

    /** POST /smart-wallet/polymarket/market-data/collect-snapshot；payload={marketId,outcome}；result_json=SmartWalletMarketSnapshotEntity */
    public static final String SMART_WALLET_POLYMARKET_MARKET_DATA_COLLECT_SNAPSHOT = "SMART_WALLET_POLYMARKET_MARKET_DATA_COLLECT_SNAPSHOT";

    /** POST /smart-wallet/clob/markets/{conditionId}/snapshot；payload={conditionId,outcome}；result_json=SmartWalletMarketSnapshotEntity */
    public static final String SMART_WALLET_CLOB_MARKET_SNAPSHOT = "SMART_WALLET_CLOB_MARKET_SNAPSHOT";

    /** POST /smart-wallet/clob/markets/snapshots/backfill；payload={limit}；result_json=List<SmartWalletMarketSnapshotEntity> */
    public static final String SMART_WALLET_CLOB_MARKETS_SNAPSHOTS_BACKFILL = "SMART_WALLET_CLOB_MARKETS_SNAPSHOTS_BACKFILL";

    /** POST /smart-wallet/wallet-features/daily；payload={walletAddress,featureDate}；result_json=SmartWalletWalletFeatureDailyEntity */
    public static final String SMART_WALLET_WALLET_FEATURES_DAILY = "SMART_WALLET_WALLET_FEATURES_DAILY";

    /** POST /smart-wallet/wallet-features/daily/backfill；payload={walletLimit,days,missingOnly}；result_json=List<SmartWalletWalletFeatureDailyEntity> */
    public static final String SMART_WALLET_WALLET_FEATURES_DAILY_BACKFILL = "SMART_WALLET_WALLET_FEATURES_DAILY_BACKFILL";

    /** 全局命令使用的占位 strategy_id */
    public static final String GLOBAL_STRATEGY_ID = "_GLOBAL_";

    private StrategyManualTriggerAction() {
    }
}
