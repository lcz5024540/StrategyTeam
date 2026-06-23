package com.metaverse.coin.strategy.job.engine.control;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.dao.mapper.StrategyManualTriggerMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyManualTriggerAction;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyManualTriggerEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyManualTriggerStatus;
import com.metaverse.coin.strategy.job.engine.PolymarketStrategy;
import com.metaverse.coin.strategy.job.engine.StrategyRegistry;
import com.metaverse.coin.strategy.job.engine.model.StrategyCycleResult;
import com.metaverse.coin.strategy.job.engine.monitor.HealthSnapshotScheduler;
import com.metaverse.coin.strategy.job.engine.monitor.StrategyAlertService;
import com.metaverse.coin.strategy.job.service.BinanceSingleExchangeFundingRateArbitrageService;
import com.metaverse.coin.strategy.job.settlement.application.SettlementApplicationService;
import com.metaverse.coin.strategy.job.smartwallet.model.LocalSettlementConfirmRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.PrematureSettlementRepairRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletMarketIdRepairRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletMarketIdRepairSummary;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPositionExitRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfileBatchOverrideRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfileOverrideRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletReplayBacktestRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletRiskSimulationRequest;
import com.metaverse.coin.strategy.job.marketdata.application.PredictionMarketDataApplicationService;
import com.metaverse.coin.strategy.job.smartwallet.service.MarketSnapshotCollectorService;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketWalletDiscoveryService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletAccountingService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletClaimService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletDataResetService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletFollowTaskService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletFollowTasksOrchestrator;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletMarketRepairService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletMarketStateService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletProfileAsyncOpsService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletProfileStoreService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletRecoveryService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletSelectionService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletSettlementSyncService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletTradeOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 异步命令消费者。
 *
 * <p>从 strategy_manual_trigger 拉取 PENDING 命令并执行：</p>
 * <ol>
 *   <li>SELECT 最早的若干条 PENDING（按 requested_at）</li>
 *   <li>对每条做 CAS update（PENDING → RUNNING），失败说明被其他实例抢到，跳过</li>
 *   <li>按 action 调用 strategy / alertService / snapshotScheduler 对应方法</li>
 *   <li>写回 status=COMPLETED/FAILED + success + message + completed_at</li>
 * </ol>
 *
 * <p>并发安全：CAS update 保证同一行只会被一个 consumer 拾取。</p>
 */
@Component
@Slf4j
public class StrategyManualTriggerConsumer {

    private static final String LOG_PREFIX = "[MANUAL_TRIGGER]";

    /** 单轮最多拾取多少条命令 */
    private static final int BATCH_SIZE = 10;

    /** 单条 message 字段最大长度（TEXT 类型，但日志/截断保护） */
    private static final int MAX_MESSAGE_LEN = 4000;

    private final StrategyManualTriggerMapper triggerMapper;
    private final StrategyRegistry strategyRegistry;
    private final StrategyAlertService alertService;
    private final HealthSnapshotScheduler snapshotScheduler;
    /** funding-arb 服务受 @ConditionalOnExpression 控制，可能未注入。 */
    private final ObjectProvider<BinanceSingleExchangeFundingRateArbitrageService> fundingArbServiceProvider;
    private final SmartWalletProfileStoreService profileStoreService;
    private final SmartWalletProfileAsyncOpsService profileAsyncOpsService;
    private final SmartWalletFollowTasksOrchestrator followTasksOrchestrator;
    private final SmartWalletDataResetService dataResetService;
    private final SmartWalletRecoveryService recoveryService;
    private final SmartWalletAccountingService accountingService;
    private final SmartWalletMarketRepairService marketRepairService;
    private final SettlementApplicationService settlementApplicationService;
    private final SmartWalletSettlementSyncService settlementSyncService;
    private final SmartWalletClaimService claimService;
    private final SmartWalletTradeOrderService tradeOrderService;
    private final SmartWalletMarketStateService marketStateService;
    private final PredictionMarketDataApplicationService predictionMarketDataApplicationService;
    private final SmartWalletSelectionService selectionService;
    private final PolymarketWalletDiscoveryService polymarketWalletDiscoveryService;
    private final MarketSnapshotCollectorService marketSnapshotCollectorService;
    private final ObjectMapper objectMapper;

    public StrategyManualTriggerConsumer(StrategyManualTriggerMapper triggerMapper,
                                         StrategyRegistry strategyRegistry,
                                         StrategyAlertService alertService,
                                         HealthSnapshotScheduler snapshotScheduler,
                                         ObjectProvider<BinanceSingleExchangeFundingRateArbitrageService> fundingArbServiceProvider,
                                         SmartWalletProfileStoreService profileStoreService,
                                         SmartWalletProfileAsyncOpsService profileAsyncOpsService,
                                         SmartWalletFollowTasksOrchestrator followTasksOrchestrator,
                                         SmartWalletDataResetService dataResetService,
                                         SmartWalletRecoveryService recoveryService,
                                         SmartWalletAccountingService accountingService,
                                         SmartWalletMarketRepairService marketRepairService,
                                         SettlementApplicationService settlementApplicationService,
                                         SmartWalletSettlementSyncService settlementSyncService,
                                         SmartWalletClaimService claimService,
                                         SmartWalletTradeOrderService tradeOrderService,
                                         SmartWalletMarketStateService marketStateService,
                                         PredictionMarketDataApplicationService predictionMarketDataApplicationService,
                                         SmartWalletSelectionService selectionService,
                                         PolymarketWalletDiscoveryService polymarketWalletDiscoveryService,
                                         MarketSnapshotCollectorService marketSnapshotCollectorService,
                                         ObjectMapper objectMapper) {
        this.triggerMapper = triggerMapper;
        this.strategyRegistry = strategyRegistry;
        this.alertService = alertService;
        this.snapshotScheduler = snapshotScheduler;
        this.fundingArbServiceProvider = fundingArbServiceProvider;
        this.profileStoreService = profileStoreService;
        this.profileAsyncOpsService = profileAsyncOpsService;
        this.followTasksOrchestrator = followTasksOrchestrator;
        this.dataResetService = dataResetService;
        this.recoveryService = recoveryService;
        this.accountingService = accountingService;
        this.marketRepairService = marketRepairService;
        this.settlementApplicationService = settlementApplicationService;
        this.settlementSyncService = settlementSyncService;
        this.claimService = claimService;
        this.tradeOrderService = tradeOrderService;
        this.marketStateService = marketStateService;
        this.predictionMarketDataApplicationService = predictionMarketDataApplicationService;
        this.selectionService = selectionService;
        this.polymarketWalletDiscoveryService = polymarketWalletDiscoveryService;
        this.marketSnapshotCollectorService = marketSnapshotCollectorService;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${strategy-engine.manual-trigger-poll-ms:2000}")
    public void poll() {
        try {
            List<StrategyManualTriggerEntity> pending = triggerMapper.selectList(
                    new LambdaQueryWrapper<StrategyManualTriggerEntity>()
                            .eq(StrategyManualTriggerEntity::getStatus, StrategyManualTriggerStatus.PENDING)
                            .orderByAsc(StrategyManualTriggerEntity::getRequestedAt)
                            .last("LIMIT " + BATCH_SIZE));
            for (StrategyManualTriggerEntity entity : pending) {
                process(entity);
            }
        } catch (Exception e) {
            log.error("{} 轮询异常: {}", LOG_PREFIX, e.getMessage(), e);
        }
    }

    private void process(StrategyManualTriggerEntity entity) {
        Date now = new Date();
        // CAS: 仅当当前 status 仍为 PENDING 时升级到 RUNNING
        int casUpdated = triggerMapper.update(null,
                new LambdaUpdateWrapper<StrategyManualTriggerEntity>()
                        .eq(StrategyManualTriggerEntity::getId, entity.getId())
                        .eq(StrategyManualTriggerEntity::getStatus, StrategyManualTriggerStatus.PENDING)
                        .set(StrategyManualTriggerEntity::getStatus, StrategyManualTriggerStatus.RUNNING)
                        .set(StrategyManualTriggerEntity::getProcessedAt, now));
        if (casUpdated == 0) {
            // 已被其他实例抢到，或状态已不是 PENDING
            return;
        }

        boolean ok = false;
        TriggerDispatchResult result;
        try {
            result = dispatch(entity);
            ok = true;
        } catch (Exception e) {
            log.error("{} 执行命令异常: id={} action={} strategy={} err={}",
                    LOG_PREFIX, entity.getId(), entity.getAction(), entity.getStrategyId(),
                    e.getMessage(), e);
            result = TriggerDispatchResult.text(truncate("异常: " + e.getMessage()));
        }
        finalize(entity.getId(), ok, result);
    }

    private TriggerDispatchResult dispatch(StrategyManualTriggerEntity entity) {
        String action = entity.getAction();
        String strategyId = entity.getStrategyId();
        if (StrategyManualTriggerAction.RUN_CYCLE.equals(action)) {
            PolymarketStrategy strategy = strategyRegistry.getStrategy(strategyId);
            if (strategy == null) return TriggerDispatchResult.text("策略不存在: " + strategyId);
            if (!strategy.isEnabled() || !strategy.getMode().isActive()) {
                return TriggerDispatchResult.text(String.format("策略未启用或模式为OFF: enabled=%s mode=%s",
                        strategy.isEnabled(), strategy.getMode()));
            }
            StrategyCycleResult cycle = strategy.runCycle();
            return TriggerDispatchResult.text(truncate(String.format("runCycle 完成: signals=%d orders=%d errors=%d",
                    cycle.getSignalsFound(), cycle.getOrdersPlaced(), cycle.getErrors())));
        }
        if (StrategyManualTriggerAction.EXIT_SCAN.equals(action)) {
            PolymarketStrategy strategy = strategyRegistry.getStrategy(strategyId);
            if (strategy == null) return TriggerDispatchResult.text("策略不存在: " + strategyId);
            strategy.runExitScan();
            return TriggerDispatchResult.text("runExitScan 完成");
        }
        if (StrategyManualTriggerAction.ALERT_CHECK.equals(action)) {
            int alerts = alertService.runAlertCheck();
            return TriggerDispatchResult.text("本轮告警: " + alerts);
        }
        if (StrategyManualTriggerAction.SNAPSHOT_COLLECT.equals(action)) {
            int collected = snapshotScheduler.collectSnapshots();
            return TriggerDispatchResult.text("本轮快照: " + collected);
        }
        if (StrategyManualTriggerAction.FUNDING_ARB_EXECUTE.equals(action)) {
            return runFundingArb(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_PROFILE_OVERRIDE_UPDATE.equals(action)) {
            return runProfileOverrideUpdate(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_PROFILE_OVERRIDE_BATCH.equals(action)) {
            return runProfileOverrideBatch(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_PROFILES_SYNC_ALL_POSITIONS.equals(action)) {
            return runSyncAllPositions(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_PROFILES_METRICS_REBUILD.equals(action)) {
            return runMetricsRebuild(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_FOLLOW_TASKS_EXECUTABLE.equals(action)) {
            return runFollowTasksExecutable(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_FOLLOW_TASKS_MANUAL_ALLOWLIST.equals(action)) {
            return runFollowTasksManualAllowlist(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_FOLLOW_TASKS_SINGLE_WALLET_OVERRIDE.equals(action)) {
            return runFollowTasksSingleWalletOverride(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_FOLLOW_TASKS_OPPORTUNITIES.equals(action)) {
            return runFollowTasksOpportunities(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_FOLLOW_TASKS_COVERAGE_REPORT.equals(action)) {
            return runFollowTasksCoverageReport(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_FOLLOW_TRADES_ATTRIBUTION.equals(action)) {
            return runFollowTradesAttribution(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_FOLLOW_TASKS_INTELLIGENCE_REEVALUATE_DEFERRED.equals(action)) {
            return runFollowTasksIntelligenceReevaluateDeferred(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_FOLLOW_TASKS_INTELLIGENCE_REPLAY_RECENT.equals(action)) {
            return runFollowTasksIntelligenceReplayRecent(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_FOLLOW_TASKS_SIMULATE_RISK.equals(action)) {
            return runFollowTasksSimulateRisk(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_FOLLOW_TASKS_REPLAY_BACKTEST.equals(action)) {
            return runFollowTasksReplayBacktest(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_FOLLOW_TASKS_REPLAY_BACKTEST_CONCENTRATION.equals(action)) {
            return runFollowTasksReplayBacktestConcentration(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_FOLLOW_TASKS_PROCESS.equals(action)) {
            return runFollowTasksProcess(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_FOLLOW_TASKS_DRY_RUN.equals(action)) {
            return runFollowTasksDryRun(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_FOLLOW_TASKS_EXECUTE_LIVE.equals(action)) {
            return runFollowTasksExecuteLive(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_ADMIN_RESET_DATA.equals(action)) {
            return runAdminResetData(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_ADMIN_RECOVER_STATE.equals(action)) {
            return runAdminRecoverState(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_ACCOUNTING_BACKFILL.equals(action)) {
            return runAccountingBackfill(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_ACCOUNTING_BACKFILL_MARKET_END_AT.equals(action)) {
            return runAccountingBackfillMarketEndAt(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_ACCOUNTING_BACKFILL_TOKEN_LINKS.equals(action)) {
            return runAccountingBackfillTokenLinks(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_ACCOUNTING_REPAIR_PREMATURE_SETTLEMENTS.equals(action)) {
            return runAccountingRepairPrematureSettlements(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_ACCOUNTING_REPAIR_PREMATURE_SETTLEMENTS_BY_IDS.equals(action)) {
            return runAccountingRepairPrematureSettlementsByIds(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_ACCOUNTING_REPAIR_MARKET_IDS_BY_POSITIONS.equals(action)) {
            return runAccountingRepairMarketIdsByPositions(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_SETTLEMENT_SYNC.equals(action)) {
            return runSettlementSync(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_SETTLEMENT_REVIEW_PAPER.equals(action)) {
            return runSettlementReviewPaper(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_SETTLEMENT_RETRIES_PROCESS.equals(action)) {
            return runSettlementRetriesProcess(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_SETTLEMENT_MANUAL_CONFIRM.equals(action)) {
            return runSettlementManualConfirm(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_CLAIM_PROCESS.equals(action)) {
            return runClaimProcess(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_CLAIM_RETRIES_PROCESS.equals(action)) {
            return runClaimRetriesProcess(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_POSITION_CLAIM.equals(action)) {
            return runPositionClaim(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_POSITION_EXIT.equals(action)) {
            return runPositionExit(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_TRADE_ORDERS_BACKFILL.equals(action)) {
            return runTradeOrdersBackfill(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_TRADE_ORDERS_REPAIR_STALE.equals(action)) {
            return runTradeOrdersRepairStale(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_TRADE_EVENTS_EFFECT_LABEL.equals(action)) {
            return runTradeEventEffectLabel(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_TRADE_EVENTS_EFFECT_LABELS_BACKFILL.equals(action)) {
            return runTradeEventEffectLabelsBackfill(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_MARKETS_RECATEGORIZE.equals(action)) {
            return runMarketsRecategorize(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_POLYMARKET_SELECT.equals(action)) {
            return runPolymarketSelect(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_POLYMARKET_ANALYZE.equals(action)) {
            return runPolymarketAnalyze(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_POLYMARKET_WATCHLIST_ADMIT.equals(action)) {
            return runPolymarketWatchlistAdmit(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_POLYMARKET_SYNC_EVENTS.equals(action)) {
            return runPolymarketSyncEvents(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_POLYMARKET_CLOSED_POSITIONS_BACKFILL_LOCAL.equals(action)) {
            return runPolymarketClosedPositionsBackfillLocal(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_POLYMARKET_MARKET_DATA_COLLECT_SNAPSHOT.equals(action)) {
            return runPolymarketMarketDataCollectSnapshot(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_CLOB_MARKET_SNAPSHOT.equals(action)) {
            return runClobMarketSnapshot(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_CLOB_MARKETS_SNAPSHOTS_BACKFILL.equals(action)) {
            return runClobMarketsSnapshotsBackfill(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_WALLET_FEATURES_DAILY.equals(action)) {
            return runWalletFeaturesDaily(entity);
        }
        if (StrategyManualTriggerAction.SMART_WALLET_WALLET_FEATURES_DAILY_BACKFILL.equals(action)) {
            return runWalletFeaturesDailyBackfill(entity);
        }
        return TriggerDispatchResult.text("未知 action: " + action);
    }

    @SuppressWarnings("unchecked")
    private TriggerDispatchResult runProfileOverrideUpdate(StrategyManualTriggerEntity entity) {
        String payload = entity.getPayload();
        if (payload == null || payload.isBlank()) {
            return TriggerDispatchResult.text("缺少 payload");
        }
        Map<String, Object> params;
        try {
            params = objectMapper.readValue(payload, Map.class);
        } catch (Exception e) {
            return TriggerDispatchResult.text("payload 解析失败: " + e.getMessage());
        }
        String walletAddress = asString(params.get("walletAddress"));
        if (walletAddress == null || walletAddress.isBlank()) {
            return TriggerDispatchResult.text("缺少 walletAddress");
        }
        Object requestNode = params.get("request");
        SmartWalletProfileOverrideRequest request = objectMapper.convertValue(
                requestNode == null ? Map.of() : requestNode,
                SmartWalletProfileOverrideRequest.class);
        var updated = profileStoreService.updateManualOverride(walletAddress, request);
        return TriggerDispatchResult.of(
                "已更新地址 " + walletAddress + " 的人工 override",
                updated);
    }

    @SuppressWarnings("unchecked")
    private TriggerDispatchResult runProfileOverrideBatch(StrategyManualTriggerEntity entity) {
        String payload = entity.getPayload();
        if (payload == null || payload.isBlank()) {
            return TriggerDispatchResult.text("缺少 payload");
        }
        SmartWalletProfileBatchOverrideRequest request;
        try {
            request = objectMapper.readValue(payload, SmartWalletProfileBatchOverrideRequest.class);
        } catch (Exception e) {
            return TriggerDispatchResult.text("payload 解析失败: " + e.getMessage());
        }
        var result = profileStoreService.batchUpdateManualOverrides(request);
        return TriggerDispatchResult.of(
                String.format("批量更新：requested=%s updated=%s missing=%s",
                        result.requestedCount(), result.updatedCount(), result.missingCount()),
                result);
    }

    @SuppressWarnings("unchecked")
    private TriggerDispatchResult runSyncAllPositions(StrategyManualTriggerEntity entity) {
        String payload = entity.getPayload();
        Map<String, Object> params = Map.of();
        if (payload != null && !payload.isBlank()) {
            try {
                params = objectMapper.readValue(payload, Map.class);
            } catch (Exception e) {
                return TriggerDispatchResult.text("payload 解析失败: " + e.getMessage());
            }
        }
        Integer batchSize = asInteger(params.get("batchSize"));
        if (batchSize == null) batchSize = 50;
        Map<String, Object> result = profileAsyncOpsService.syncAllPositions(batchSize);
        return TriggerDispatchResult.of(
                String.format("全量同步完成（batchSize=%d）", batchSize),
                result);
    }

    @SuppressWarnings("unchecked")
    private TriggerDispatchResult runMetricsRebuild(StrategyManualTriggerEntity entity) {
        String payload = entity.getPayload();
        Map<String, Object> params = Map.of();
        if (payload != null && !payload.isBlank()) {
            try {
                params = objectMapper.readValue(payload, Map.class);
            } catch (Exception e) {
                return TriggerDispatchResult.text("payload 解析失败: " + e.getMessage());
            }
        }
        String scope = asString(params.get("scope"));
        if (scope == null || scope.isBlank()) scope = "candidates";
        Integer limit = asInteger(params.get("limit"));
        if (limit == null) limit = 30;
        Map<String, Object> result = profileAsyncOpsService.rebuildProfileMetrics(scope, limit);
        return TriggerDispatchResult.of(
                String.format("画像 metrics 重建完成（scope=%s, limit=%d）", scope, limit),
                result);
    }

    @SuppressWarnings("unchecked")
    private TriggerDispatchResult runFundingArb(StrategyManualTriggerEntity entity) {
        BinanceSingleExchangeFundingRateArbitrageService service = fundingArbServiceProvider.getIfAvailable();
        if (service == null) {
            return TriggerDispatchResult.text("funding-arb 服务未启用（未配置 binance.api.api-key/secret-key）");
        }
        String payload = entity.getPayload();
        if (payload == null || payload.isBlank()) {
            return TriggerDispatchResult.text("缺少 payload");
        }
        Map<String, Object> params;
        try {
            params = objectMapper.readValue(payload, Map.class);
        } catch (Exception e) {
            return TriggerDispatchResult.text("payload 解析失败: " + e.getMessage());
        }
        String symbols = asString(params.get("symbols"));
        BigDecimal amount = asBigDecimal(params.get("amount"));
        BigDecimal minRate = asBigDecimal(params.get("minRate"));
        Integer leverage = asInteger(params.get("leverage"));
        if (amount == null) {
            return TriggerDispatchResult.text("缺少 amount");
        }
        if (minRate == null) {
            minRate = new BigDecimal("0.0001");
        }
        if (leverage == null || leverage <= 0) {
            leverage = 3;
        }
        return TriggerDispatchResult.text(truncate(service.executeArbitrage(symbols, amount, minRate, leverage)));
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    private static BigDecimal asBigDecimal(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Number num) return new BigDecimal(num.toString());
        try {
            return new BigDecimal(o.toString());
        } catch (Exception ignore) {
            return null;
        }
    }

    private static Integer asInteger(Object o) {
        if (o == null) return null;
        if (o instanceof Integer i) return i;
        if (o instanceof Number num) return num.intValue();
        try {
            return Integer.parseInt(o.toString());
        } catch (Exception ignore) {
            return null;
        }
    }

    private void finalize(Long id, boolean success, TriggerDispatchResult result) {
        Date now = new Date();
        String resultJson = null;
        if (result.payload() != null) {
            try {
                resultJson = objectMapper.writeValueAsString(result.payload());
            } catch (Exception e) {
                log.warn("{} payload 序列化失败: id={} err={}", LOG_PREFIX, id, e.getMessage());
            }
        }
        triggerMapper.update(null,
                new LambdaUpdateWrapper<StrategyManualTriggerEntity>()
                        .eq(StrategyManualTriggerEntity::getId, id)
                        .set(StrategyManualTriggerEntity::getStatus,
                                success ? StrategyManualTriggerStatus.COMPLETED : StrategyManualTriggerStatus.FAILED)
                        .set(StrategyManualTriggerEntity::getSuccess, success)
                        .set(StrategyManualTriggerEntity::getMessage, result.message())
                        .set(StrategyManualTriggerEntity::getResultJson, resultJson)
                        .set(StrategyManualTriggerEntity::getCompletedAt, now));
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > MAX_MESSAGE_LEN ? s.substring(0, MAX_MESSAGE_LEN) : s;
    }

    // ── SmartWallet follow-tasks / follow-trades 命令分支 ───────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> readPayloadMap(StrategyManualTriggerEntity entity) {
        String payload = entity.getPayload();
        if (payload == null || payload.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(payload, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private TriggerDispatchResult runFollowTasksExecutable(StrategyManualTriggerEntity entity) {
        Map<String, Object> params = readPayloadMap(entity);
        Integer limit = asInteger(params.get("limit"));
        Integer scanLimit = asInteger(params.get("scanLimit"));
        var data = followTasksOrchestrator.listExecutable(limit, scanLimit);
        return TriggerDispatchResult.of(String.format("executable=%d", data.size()), data);
    }

    private TriggerDispatchResult runFollowTasksManualAllowlist(StrategyManualTriggerEntity entity) {
        Map<String, Object> params = readPayloadMap(entity);
        Integer limit = asInteger(params.get("limit"));
        Object includeBlockedRaw = params.get("includeBlocked");
        Boolean includeBlocked = includeBlockedRaw == null ? Boolean.FALSE
                : Boolean.valueOf(includeBlockedRaw.toString());
        var data = followTasksOrchestrator.listManualAllowlist(limit, includeBlocked);
        return TriggerDispatchResult.of(String.format("manualAllowlist=%d", data.size()), data);
    }

    private TriggerDispatchResult runFollowTasksSingleWalletOverride(StrategyManualTriggerEntity entity) {
        Map<String, Object> params = readPayloadMap(entity);
        Integer limit = asInteger(params.get("limit"));
        String preset = asString(params.get("preset"));
        SmartWalletRiskSimulationRequest request = objectMapper.convertValue(
                params.get("request") == null ? Map.of() : params.get("request"),
                SmartWalletRiskSimulationRequest.class);
        var data = followTasksOrchestrator.listSingleWalletOverride(request, limit, preset);
        return TriggerDispatchResult.of(String.format("singleWalletOverride=%d", data.size()), data);
    }

    private TriggerDispatchResult runFollowTasksOpportunities(StrategyManualTriggerEntity entity) {
        Map<String, Object> params = readPayloadMap(entity);
        Integer limit = asInteger(params.get("limit"));
        Integer maxBlockers = asInteger(params.get("maxBlockers"));
        Object includeExecutableRaw = params.get("includeExecutable");
        Boolean includeExecutable = includeExecutableRaw == null ? Boolean.FALSE
                : Boolean.valueOf(includeExecutableRaw.toString());
        var data = followTasksOrchestrator.listOpportunities(limit, maxBlockers, includeExecutable);
        return TriggerDispatchResult.of(String.format("opportunities=%d", data.size()), data);
    }

    private TriggerDispatchResult runFollowTasksCoverageReport(StrategyManualTriggerEntity entity) {
        Map<String, Object> params = readPayloadMap(entity);
        String walletAddress = asString(params.get("walletAddress"));
        Integer limit = asInteger(params.get("limit"));
        var data = followTasksOrchestrator.coverageReport(walletAddress, limit);
        return TriggerDispatchResult.of(String.format("coverageReport=%d", data.size()), data);
    }

    private TriggerDispatchResult runFollowTradesAttribution(StrategyManualTriggerEntity entity) {
        Map<String, Object> params = readPayloadMap(entity);
        String groupBy = asString(params.get("groupBy"));
        Integer limit = asInteger(params.get("limit"));
        var data = followTasksOrchestrator.attribution(groupBy, limit);
        return TriggerDispatchResult.of(String.format("attribution groupBy=%s n=%d",
                groupBy == null ? "EXIT_REASON" : groupBy, data.size()), data);
    }

    private TriggerDispatchResult runFollowTasksIntelligenceReevaluateDeferred(StrategyManualTriggerEntity entity) {
        Map<String, Object> params = readPayloadMap(entity);
        Integer limit = asInteger(params.get("limit"));
        int actualLimit = limit == null ? 50 : limit;
        int updated = followTasksOrchestrator.reevaluateDeferred(actualLimit);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("limit", actualLimit);
        result.put("updated", updated);
        return TriggerDispatchResult.of(String.format("reevaluateDeferred limit=%d updated=%d",
                actualLimit, updated), result);
    }

    private TriggerDispatchResult runFollowTasksIntelligenceReplayRecent(StrategyManualTriggerEntity entity) {
        Map<String, Object> params = readPayloadMap(entity);
        Integer limit = asInteger(params.get("limit"));
        Object includeProcessedRaw = params.get("includeProcessed");
        boolean includeProcessed = includeProcessedRaw != null
                && Boolean.parseBoolean(includeProcessedRaw.toString());
        int actualLimit = limit == null ? 100 : limit;
        SmartWalletFollowTaskService.IntelligenceReplaySummary summary =
                followTasksOrchestrator.replayRecentIntelligence(actualLimit, includeProcessed);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("limit", actualLimit);
        result.put("includeProcessed", includeProcessed);
        result.put("scanned", summary.getScanned());
        result.put("changed", summary.getChanged());
        result.put("skipped", summary.getSkipped());
        return TriggerDispatchResult.of(String.format("replayRecent scanned=%d changed=%d skipped=%d",
                summary.getScanned(), summary.getChanged(), summary.getSkipped()), result);
    }

    private TriggerDispatchResult runFollowTasksSimulateRisk(StrategyManualTriggerEntity entity) {
        String payload = entity.getPayload();
        SmartWalletRiskSimulationRequest request;
        if (payload == null || payload.isBlank()) {
            request = new SmartWalletRiskSimulationRequest();
        } else {
            try {
                request = objectMapper.readValue(payload, SmartWalletRiskSimulationRequest.class);
            } catch (Exception e) {
                return TriggerDispatchResult.text("payload 解析失败: " + e.getMessage());
            }
        }
        var data = followTasksOrchestrator.simulateRisk(request);
        return TriggerDispatchResult.of("simulateRisk 完成", data);
    }

    private TriggerDispatchResult runFollowTasksReplayBacktest(StrategyManualTriggerEntity entity) {
        SmartWalletReplayBacktestRequest request = parseReplayBacktestRequest(entity);
        if (request == null) {
            return TriggerDispatchResult.text("payload 解析失败");
        }
        var data = followTasksOrchestrator.replayBacktest(request);
        return TriggerDispatchResult.of("replayBacktest 完成", data);
    }

    private TriggerDispatchResult runFollowTasksReplayBacktestConcentration(StrategyManualTriggerEntity entity) {
        SmartWalletReplayBacktestRequest request = parseReplayBacktestRequest(entity);
        if (request == null) {
            return TriggerDispatchResult.text("payload 解析失败");
        }
        var data = followTasksOrchestrator.replayConcentrationSummary(request);
        return TriggerDispatchResult.of("replayConcentrationSummary 完成", data);
    }

    private SmartWalletReplayBacktestRequest parseReplayBacktestRequest(StrategyManualTriggerEntity entity) {
        String payload = entity.getPayload();
        if (payload == null || payload.isBlank()) {
            return new SmartWalletReplayBacktestRequest();
        }
        try {
            return objectMapper.readValue(payload, SmartWalletReplayBacktestRequest.class);
        } catch (Exception e) {
            return null;
        }
    }

    private TriggerDispatchResult runFollowTasksProcess(StrategyManualTriggerEntity entity) {
        int processed = followTasksOrchestrator.processPendingTasksNow();
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("processed", processed);
        return TriggerDispatchResult.of("processPendingTasks 完成 processed=" + processed, result);
    }

    private TriggerDispatchResult runFollowTasksDryRun(StrategyManualTriggerEntity entity) {
        Map<String, Object> params = readPayloadMap(entity);
        Long followTaskId = asLong(params.get("followTaskId"));
        if (followTaskId == null) {
            return TriggerDispatchResult.text("缺少 followTaskId");
        }
        var data = followTasksOrchestrator.dryRunFollowTask(followTaskId);
        return TriggerDispatchResult.of("dryRunFollowTask 完成 followTaskId=" + followTaskId, data);
    }

    private TriggerDispatchResult runFollowTasksExecuteLive(StrategyManualTriggerEntity entity) {
        Map<String, Object> params = readPayloadMap(entity);
        Long followTaskId = asLong(params.get("followTaskId"));
        if (followTaskId == null) {
            return TriggerDispatchResult.text("缺少 followTaskId");
        }
        var data = followTasksOrchestrator.executeFollowTaskLive(followTaskId);
        return TriggerDispatchResult.of("executeFollowTaskLive 完成 followTaskId=" + followTaskId, data);
    }

    private static Long asLong(Object o) {
        if (o == null) return null;
        if (o instanceof Long l) return l;
        if (o instanceof Number num) return num.longValue();
        try {
            return Long.parseLong(o.toString());
        } catch (Exception ignore) {
            return null;
        }
    }

    private static Boolean asBoolean(Object o, Boolean defaultValue) {
        if (o == null) return defaultValue;
        if (o instanceof Boolean b) return b;
        return Boolean.parseBoolean(o.toString());
    }

    // ── SmartWallet admin / accounting / settlement / claim / position 命令分支 ───────────────

    private TriggerDispatchResult runAdminResetData(StrategyManualTriggerEntity entity) {
        Map<String, Object> params = readPayloadMap(entity);
        boolean confirm = Boolean.TRUE.equals(asBoolean(params.get("confirm"), Boolean.FALSE));
        boolean includeProfiles = asBoolean(params.get("includeProfiles"), Boolean.TRUE);
        boolean includeRiskArtifacts = asBoolean(params.get("includeRiskArtifacts"), Boolean.TRUE);
        boolean includeRuntimeSeeds = asBoolean(params.get("includeRuntimeSeeds"), Boolean.TRUE);
        boolean includeMetadataCache = asBoolean(params.get("includeMetadataCache"), Boolean.TRUE);
        if (!confirm) {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("executed", false);
            body.put("message", "Pass confirm=true to execute the smart-wallet data reset.");
            body.put("includeProfiles", includeProfiles);
            body.put("includeRiskArtifacts", includeRiskArtifacts);
            body.put("includeRuntimeSeeds", includeRuntimeSeeds);
            body.put("includeMetadataCache", includeMetadataCache);
            return TriggerDispatchResult.of("reset-data 未执行（confirm=false）", body);
        }
        Map<String, Object> result = dataResetService.reset(
                includeProfiles, includeRiskArtifacts, includeRuntimeSeeds, includeMetadataCache);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("executed", true);
        body.put("result", result);
        return TriggerDispatchResult.of("reset-data 完成", body);
    }

    private TriggerDispatchResult runAdminRecoverState(StrategyManualTriggerEntity entity) {
        Map<String, Object> params = readPayloadMap(entity);
        String reason = asString(params.get("reason"));
        Map<String, Object> result = recoveryService.recoverState(reason);
        return TriggerDispatchResult.of("recover-state 完成", result);
    }

    private TriggerDispatchResult runAccountingBackfill(StrategyManualTriggerEntity entity) {
        Map<String, Object> params = readPayloadMap(entity);
        Integer limit = asInteger(params.get("limit"));
        int actualLimit = limit == null ? 2000 : limit;
        int imported = accountingService.backfillFromFollowTasks(actualLimit);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("imported", imported);
        return TriggerDispatchResult.of("backfill 完成 imported=" + imported, result);
    }

    private TriggerDispatchResult runAccountingBackfillMarketEndAt(StrategyManualTriggerEntity entity) {
        Map<String, Object> params = readPayloadMap(entity);
        Integer limit = asInteger(params.get("limit"));
        int actualLimit = limit == null ? 500 : limit;
        boolean refreshCache = asBoolean(params.get("refreshCache"), Boolean.FALSE);
        Map<String, Object> result = accountingService.backfillMissingMarketEndAt(actualLimit, refreshCache);
        return TriggerDispatchResult.of("backfill-market-end-at 完成", result);
    }

    private TriggerDispatchResult runAccountingBackfillTokenLinks(StrategyManualTriggerEntity entity) {
        Map<String, Object> params = readPayloadMap(entity);
        Integer limit = asInteger(params.get("limit"));
        int actualLimit = limit == null ? 500 : limit;
        boolean refreshCache = asBoolean(params.get("refreshCache"), Boolean.FALSE);
        Map<String, Object> result = accountingService.backfillMissingTokenAndPositionLinks(actualLimit, refreshCache);
        return TriggerDispatchResult.of("backfill-token-links 完成", result);
    }

    private TriggerDispatchResult runAccountingRepairPrematureSettlements(StrategyManualTriggerEntity entity) {
        Map<String, Object> params = readPayloadMap(entity);
        Integer limit = asInteger(params.get("limit"));
        int actualLimit = limit == null ? 500 : limit;
        boolean refreshCache = asBoolean(params.get("refreshCache"), Boolean.TRUE);
        var result = accountingService.repairPrematureResolvedPaperPositions(actualLimit, refreshCache);
        return TriggerDispatchResult.of("repair-premature-settlements 完成", result);
    }

    private TriggerDispatchResult runAccountingRepairPrematureSettlementsByIds(StrategyManualTriggerEntity entity) {
        PrematureSettlementRepairRequest request;
        String payload = entity.getPayload();
        if (payload == null || payload.isBlank()) {
            request = new PrematureSettlementRepairRequest();
        } else {
            try {
                request = objectMapper.readValue(payload, PrematureSettlementRepairRequest.class);
            } catch (Exception e) {
                return TriggerDispatchResult.text("payload 解析失败: " + e.getMessage());
            }
        }
        var result = (request.getPositionIds() != null && !request.getPositionIds().isEmpty())
                ? accountingService.repairPrematureResolvedPaperPositionsByIds(request.getPositionIds())
                : accountingService.repairPrematureResolvedPaperPositionsByItems(request.getItems());
        return TriggerDispatchResult.of("repair-premature-settlements/by-ids 完成", result);
    }

    private TriggerDispatchResult runAccountingRepairMarketIdsByPositions(StrategyManualTriggerEntity entity) {
        Map<String, Object> params = readPayloadMap(entity);
        Object requestNode = params.get("request");
        SmartWalletMarketIdRepairRequest request = objectMapper.convertValue(
                requestNode == null ? Map.of() : requestNode,
                SmartWalletMarketIdRepairRequest.class);
        boolean syncAfterRepair = asBoolean(params.get("syncAfterRepair"), Boolean.TRUE);
        boolean refreshCache = asBoolean(params.get("refreshCache"), Boolean.TRUE);

        SmartWalletMarketIdRepairSummary repairSummary = marketRepairService.repairPositionMarketIds(request);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("repair", repairSummary);
        if (syncAfterRepair && repairSummary.getRepairedMarketIds() != null && !repairSummary.getRepairedMarketIds().isEmpty()) {
            result.put("settlementSync",
                    accountingService.syncSettlementsForMarkets(repairSummary.getRepairedMarketIds(), refreshCache));
        }
        return TriggerDispatchResult.of("repair-market-ids/by-positions 完成", result);
    }

    private TriggerDispatchResult runSettlementSync(StrategyManualTriggerEntity entity) {
        Map<String, Object> params = readPayloadMap(entity);
        Integer positionLimit = asInteger(params.get("positionLimit"));
        Integer marketLimit = asInteger(params.get("marketLimit"));
        boolean refreshCache = asBoolean(params.get("refreshCache"), Boolean.FALSE);
        var result = settlementApplicationService.runNow(
                positionLimit == null ? 500 : positionLimit,
                marketLimit == null ? 100 : marketLimit,
                refreshCache);
        return TriggerDispatchResult.of("settlement/sync 完成", result);
    }

    private TriggerDispatchResult runSettlementReviewPaper(StrategyManualTriggerEntity entity) {
        Map<String, Object> params = readPayloadMap(entity);
        Integer limit = asInteger(params.get("limit"));
        boolean refreshCache = asBoolean(params.get("refreshCache"), Boolean.TRUE);
        var result = accountingService.reviewHistoricalPaperSettlements(
                limit == null ? 1000 : limit, refreshCache);
        return TriggerDispatchResult.of("settlement/review-paper-history 完成", result);
    }

    private TriggerDispatchResult runSettlementRetriesProcess(StrategyManualTriggerEntity entity) {
        Map<String, Object> params = readPayloadMap(entity);
        Integer limit = asInteger(params.get("limit"));
        boolean refreshCache = asBoolean(params.get("refreshCache"), Boolean.FALSE);
        var result = settlementSyncService.processRetriesNow(limit, refreshCache);
        return TriggerDispatchResult.of("settlement/retries/process 完成", result);
    }

    private TriggerDispatchResult runSettlementManualConfirm(StrategyManualTriggerEntity entity) {
        Map<String, Object> params = readPayloadMap(entity);
        Object requestNode = params.get("request");
        LocalSettlementConfirmRequest request = requestNode == null
                ? null
                : objectMapper.convertValue(requestNode, LocalSettlementConfirmRequest.class);
        boolean refreshCache = asBoolean(params.get("refreshCache"), Boolean.TRUE);
        List<String> marketIds = request == null ? List.of() : request.getMarketIds();
        var result = settlementApplicationService.confirmLocalMarkets(
                marketIds == null ? List.of() : marketIds, refreshCache);
        return TriggerDispatchResult.of("settlement/manual-confirm-local 完成", result);
    }

    private TriggerDispatchResult runClaimProcess(StrategyManualTriggerEntity entity) {
        Map<String, Object> params = readPayloadMap(entity);
        Integer limit = asInteger(params.get("limit"));
        var result = claimService.processNow(limit);
        return TriggerDispatchResult.of("claim/process 完成", result);
    }

    private TriggerDispatchResult runClaimRetriesProcess(StrategyManualTriggerEntity entity) {
        Map<String, Object> params = readPayloadMap(entity);
        Integer limit = asInteger(params.get("limit"));
        var result = claimService.processRetriesNow(limit);
        return TriggerDispatchResult.of("claim/retries/process 完成", result);
    }

    private TriggerDispatchResult runPositionClaim(StrategyManualTriggerEntity entity) {
        Map<String, Object> params = readPayloadMap(entity);
        Long positionId = asLong(params.get("positionId"));
        if (positionId == null) {
            return TriggerDispatchResult.text("缺少 positionId");
        }
        boolean confirmExternalClaim = asBoolean(params.get("confirmExternalClaim"), Boolean.FALSE);
        var result = claimService.claimPosition(positionId, confirmExternalClaim);
        return TriggerDispatchResult.of("positions/" + positionId + "/claim 完成", result);
    }

    private TriggerDispatchResult runPositionExit(StrategyManualTriggerEntity entity) {
        Map<String, Object> params = readPayloadMap(entity);
        Long positionId = asLong(params.get("positionId"));
        if (positionId == null) {
            return TriggerDispatchResult.text("缺少 positionId");
        }
        Object requestNode = params.get("request");
        SmartWalletPositionExitRequest request = requestNode == null
                ? null
                : objectMapper.convertValue(requestNode, SmartWalletPositionExitRequest.class);
        var result = accountingService.exitPosition(positionId, request);
        return TriggerDispatchResult.of("positions/" + positionId + "/exit 完成", result);
    }

    // ── SmartWallet trade-orders / trade-events / markets 命令分支 ───────────────

    private TriggerDispatchResult runTradeOrdersBackfill(StrategyManualTriggerEntity entity) {
        Map<String, Object> params = readPayloadMap(entity);
        Integer limit = asInteger(params.get("limit"));
        int actualLimit = limit == null ? 10000 : limit;
        int rebuilt = tradeOrderService.rebuildAll(actualLimit);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("rebuilt", rebuilt);
        return TriggerDispatchResult.of("trade-orders/backfill 完成 rebuilt=" + rebuilt, result);
    }

    private TriggerDispatchResult runTradeOrdersRepairStale(StrategyManualTriggerEntity entity) {
        int repaired = tradeOrderService.repairStaleTradeOrders();
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("repaired", repaired);
        return TriggerDispatchResult.of("trade-orders/repair-stale 完成 repaired=" + repaired, result);
    }

    private TriggerDispatchResult runTradeEventEffectLabel(StrategyManualTriggerEntity entity) {
        Map<String, Object> params = readPayloadMap(entity);
        Long sourceEventId = asLong(params.get("sourceEventId"));
        if (sourceEventId == null) {
            return TriggerDispatchResult.text("缺少 sourceEventId");
        }
        var result = predictionMarketDataApplicationService.buildTradeEffectLabel(sourceEventId);
        return TriggerDispatchResult.of("trade-events/" + sourceEventId + "/effect-label 完成", result);
    }

    private TriggerDispatchResult runTradeEventEffectLabelsBackfill(StrategyManualTriggerEntity entity) {
        Map<String, Object> params = readPayloadMap(entity);
        Integer limit = asInteger(params.get("limit"));
        boolean missingOnly = asBoolean(params.get("missingOnly"), Boolean.TRUE);
        var result = predictionMarketDataApplicationService.buildRecentTradeEffectLabels(
                limit == null ? 50 : limit, missingOnly);
        return TriggerDispatchResult.of("trade-events/effect-labels/backfill 完成 n=" + result.size(), result);
    }

    private TriggerDispatchResult runMarketsRecategorize(StrategyManualTriggerEntity entity) {
        int updated = marketStateService.recategorizeAll();
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("updated", updated);
        return TriggerDispatchResult.of("markets/recategorize 完成 updated=" + updated, result);
    }

    // ── SmartWallet polymarket / clob / wallet-features 命令分支 ───────────────

    private TriggerDispatchResult runPolymarketSelect(StrategyManualTriggerEntity entity) {
        var result = selectionService.refreshQualifiedWallets();
        return TriggerDispatchResult.of("polymarket/select 完成", result);
    }

    private TriggerDispatchResult runPolymarketAnalyze(StrategyManualTriggerEntity entity) {
        var result = predictionMarketDataApplicationService.analyzeSourceWallets();
        return TriggerDispatchResult.of("polymarket/analyze 完成", result);
    }

    private TriggerDispatchResult runPolymarketWatchlistAdmit(StrategyManualTriggerEntity entity) {
        Map<String, Object> params = readPayloadMap(entity);
        String walletAddress = asString(params.get("walletAddress"));
        if (walletAddress == null || walletAddress.isBlank()) {
            return TriggerDispatchResult.text("缺少 walletAddress");
        }
        boolean admitted = polymarketWalletDiscoveryService.analyzeWalletAndAdmitWatchlist(walletAddress);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("walletAddress", walletAddress);
        result.put("admitted", admitted);
        result.put("message", admitted
                ? "Wallet admitted to watchlist and persisted successfully."
                : "Wallet did not pass admission checks, skipped persistence.");
        return TriggerDispatchResult.of("polymarket/watchlist/admit 完成 admitted=" + admitted, result);
    }

    private TriggerDispatchResult runPolymarketSyncEvents(StrategyManualTriggerEntity entity) {
        int imported = predictionMarketDataApplicationService.syncWatchlistEvents();
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("imported", imported);
        return TriggerDispatchResult.of("polymarket/sync-events 完成 imported=" + imported, result);
    }

    private TriggerDispatchResult runPolymarketClosedPositionsBackfillLocal(StrategyManualTriggerEntity entity) {
        Map<String, Object> params = readPayloadMap(entity);
        String directory = asString(params.get("directory"));
        if (directory == null || directory.isBlank()) directory = "/tmp/poly_api";
        Integer limit = asInteger(params.get("limit"));
        boolean dryRun = asBoolean(params.get("dryRun"), Boolean.FALSE);
        var result = predictionMarketDataApplicationService.backfillLocalClosedPositions(directory, limit, dryRun);
        return TriggerDispatchResult.of("polymarket/closed-positions/backfill-local 完成", result);
    }

    private TriggerDispatchResult runPolymarketMarketDataCollectSnapshot(StrategyManualTriggerEntity entity) {
        Map<String, Object> params = readPayloadMap(entity);
        String marketId = asString(params.get("marketId"));
        if (marketId == null || marketId.isBlank()) {
            return TriggerDispatchResult.text("缺少 marketId");
        }
        String outcome = asString(params.get("outcome"));
        if (outcome == null || outcome.isBlank()) outcome = "YES";
        var result = marketSnapshotCollectorService.collectSnapshot(marketId, outcome);
        return TriggerDispatchResult.of("polymarket/market-data/collect-snapshot 完成", result);
    }

    private TriggerDispatchResult runClobMarketSnapshot(StrategyManualTriggerEntity entity) {
        Map<String, Object> params = readPayloadMap(entity);
        String conditionId = asString(params.get("conditionId"));
        if (conditionId == null || conditionId.isBlank()) {
            return TriggerDispatchResult.text("缺少 conditionId");
        }
        String outcome = asString(params.get("outcome"));
        if (outcome == null || outcome.isBlank()) outcome = "YES";
        var result = predictionMarketDataApplicationService.collectMarketSnapshot(conditionId, outcome);
        return TriggerDispatchResult.of("clob/markets/" + conditionId + "/snapshot 完成", result);
    }

    private TriggerDispatchResult runClobMarketsSnapshotsBackfill(StrategyManualTriggerEntity entity) {
        Map<String, Object> params = readPayloadMap(entity);
        Integer limit = asInteger(params.get("limit"));
        var result = predictionMarketDataApplicationService.collectRecentMarketSnapshots(
                limit == null ? 20 : limit);
        return TriggerDispatchResult.of("clob/markets/snapshots/backfill 完成 n=" + result.size(), result);
    }

    private TriggerDispatchResult runWalletFeaturesDaily(StrategyManualTriggerEntity entity) {
        Map<String, Object> params = readPayloadMap(entity);
        String walletAddress = asString(params.get("walletAddress"));
        if (walletAddress == null || walletAddress.isBlank()) {
            return TriggerDispatchResult.text("缺少 walletAddress");
        }
        String featureDateStr = asString(params.get("featureDate"));
        java.time.LocalDate featureDate = null;
        if (featureDateStr != null && !featureDateStr.isBlank()) {
            try {
                featureDate = java.time.LocalDate.parse(featureDateStr);
            } catch (Exception e) {
                return TriggerDispatchResult.text("featureDate 解析失败: " + e.getMessage());
            }
        }
        var result = predictionMarketDataApplicationService.buildWalletFeatureDaily(walletAddress, featureDate);
        return TriggerDispatchResult.of("wallet-features/daily 完成", result);
    }

    private TriggerDispatchResult runWalletFeaturesDailyBackfill(StrategyManualTriggerEntity entity) {
        Map<String, Object> params = readPayloadMap(entity);
        Integer walletLimit = asInteger(params.get("walletLimit"));
        Integer days = asInteger(params.get("days"));
        boolean missingOnly = asBoolean(params.get("missingOnly"), Boolean.TRUE);
        var result = predictionMarketDataApplicationService.buildRecentWalletFeatureDaily(
                walletLimit == null ? 30 : walletLimit,
                days == null ? 1 : days,
                missingOnly);
        return TriggerDispatchResult.of("wallet-features/daily/backfill 完成 n=" + result.size(), result);
    }
}
