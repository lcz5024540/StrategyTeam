package com.metaverse.coin.strategy.job.smartwallet.service;
import com.metaverse.coin.strategy.common.service.smartwallet.model.ConcentrationSnapshot;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletManualAllowlistService;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletMarketInfoService;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketSnapshotEntity;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.ConsensusSignalView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletExecutionResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPositionExitRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPositionExitResultView;
import com.metaverse.coin.strategy.job.strategy.application.StrategyFlowLogFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class SmartWalletPositionSyncService {

    private static final List<String> AUTO_EXIT_TRADABLE_POSITION_STATUSES =
            List.of("OPEN", "PARTIALLY_CLOSED");

    private final SmartWalletProperties properties;
    private final PolymarketApiClient polymarketApiClient;
    private final ExecutionWalletContextLoader walletContextLoader;
    private final SmartWalletPositionMapper positionMapper;
    private final SmartWalletOrderMapper orderMapper;
    private final SmartWalletAccountingService accountingService;
    private final PolymarketClobExecutionService clobExecutionService;
    private final SmartWalletRecoveryService recoveryService;
    private final SmartWalletMarketInfoService marketInfoService;
    private final ExitSignalProvider exitSignalProvider;
    private final SmartWalletTradeEventMapper tradeEventMapper;
    private final PolymarketConsensusSignalService consensusSignalService;
    private final MarketSnapshotCollectorService marketSnapshotCollectorService;
    private final MarketConcentrationService marketConcentrationService;
    private final WalletBehaviorProfileService walletBehaviorProfileService;
    private final PolymarketMarketDataCacheService marketDataCacheService;
    private final SmartWalletManualAllowlistService manualAllowlistService;
    // F-P1-5: 退出阈值扣除双边 taker fee
    private final PolymarketFeeScheduleService feeScheduleService;
    // F-P1-6: 分批出场 (auto exit 时按 partial-exit-ratios 拆 SELL 单)
    private final StagedExitPlanner stagedExitPlanner;
    private volatile Date cooldownUntil;
    private volatile String cooldownReason;
    private final AtomicInteger consecutiveRpcFailures = new AtomicInteger(0);

    @Scheduled(fixedDelayString = "${smart-wallet.polymarket.position-sync-fixed-delay-ms:15000}")
    public void syncPositions() {
        if (!properties.isEnabled() || properties.getPolymarket() == null || !properties.getPolymarket().isEnabled()) {
            return;
        }
        if (!shouldSyncLiveWalletState()) {
            log.debug("Skip live wallet position sync because only PAPER flows are active and no live positions are tracked locally.");
            return;
        }
        if (isCoolingDown()) {
            log.debug("Skip live wallet position sync during cooldown until {}: {}",
                    cooldownUntil,
                    StringUtils.hasText(cooldownReason) ? cooldownReason : "unknown");
            return;
        }

        ExecutionWalletContextLoader.ExecutionWalletContext context;
        try {
            context = walletContextLoader.requireLiveContext();
        } catch (Exception e) {
            // Live wallet is not configured, skip sync
            return;
        }

        if (context.getAddress() == null || context.getAddress().isBlank()) {
            return;
        }

        try {
            BigDecimal balance = polymarketApiClient.fetchBalance(context.getAddress());
            log.debug("Current USDC balance for {}: {}", context.getAddress(), balance);

            List<JsonNode> positions = polymarketApiClient.fetchPositions(context.getAddress(), null);
            int positionCount = positions.size();
            log.debug("Current positions count for {}: {}", context.getAddress(), positionCount);

            // 安全阀：如果 API 返回的仓位数超出预期上限，截断并告警防止下游 OOM
            final int maxPositions = 5000;
            if (positionCount > maxPositions) {
                log.warn("fetchPositions returned {} positions for {}, truncating to {} to prevent OOM",
                        positionCount, context.getAddress(), maxPositions);
                positions = positions.subList(0, maxPositions);
            }

            clearCooldown();
            consecutiveRpcFailures.set(0);

            // Further process positions if needed...
        } catch (Exception e) {
            applyCooldown(e);
            maybeRecoverFromRpcFailure(e, context.getAddress());
            log.warn("Failed to sync positions and balance for {}: {}{}",
                    context.getAddress(),
                    e.getMessage(),
                    cooldownUntil == null ? "" : ", cooldownUntil=" + cooldownUntil);
        }
    }

    private boolean shouldSyncLiveWalletState() {
        if (properties.isAutoExecutionEnabled()) {
            return true;
        }
        SmartWalletProperties.LiveValidation liveValidation = properties.getLiveValidation();
        if (liveValidation != null && liveValidation.isEnabled()) {
            return true;
        }
        Long livePositionCount = positionMapper.selectCount(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .ne(SmartWalletPositionEntity::getExecutionAddress, "paper-book")
                .in(SmartWalletPositionEntity::getStatus, List.of("OPEN", "PARTIALLY_CLOSED", "RESOLVED_UNCLAIMED")));
        return livePositionCount != null && livePositionCount > 0;
    }

    private boolean isCoolingDown() {
        Date until = cooldownUntil;
        if (until == null) {
            return false;
        }
        if (until.after(new Date())) {
            return true;
        }
        clearCooldown();
        return false;
    }

    private void clearCooldown() {
        cooldownUntil = null;
        cooldownReason = null;
    }

    private void applyCooldown(Exception e) {
        long cooldownMs = resolveCooldownMs(e);
        if (cooldownMs <= 0) {
            return;
        }
        cooldownUntil = new Date(System.currentTimeMillis() + cooldownMs);
        cooldownReason = isNotFoundError(e)
                ? "Polymarket live wallet balance/positions API returned 404 for the configured address."
                : "Polymarket live wallet sync request failed.";
    }

    private void maybeRecoverFromRpcFailure(Exception e, String walletAddress) {
        SmartWalletProperties.PolymarketMonitor monitor = properties.getPolymarket();
        if (monitor == null || !monitor.isPositionSyncAutoRecoveryEnabled() || !isRecoverableRpcFailure(e)) {
            return;
        }
        int failures = consecutiveRpcFailures.incrementAndGet();
        int threshold = monitor.getPositionSyncRecoveryFailureThreshold() == null
                ? 2
                : Math.max(1, monitor.getPositionSyncRecoveryFailureThreshold());
        if (failures < threshold) {
            return;
        }
        consecutiveRpcFailures.set(0);
        recoveryService.recoverState("position-sync-rpc-error wallet="
                + (walletAddress == null ? "unknown" : walletAddress)
                + " message=" + e.getMessage());
        clearCooldown();
    }

    private long resolveCooldownMs(Exception e) {
        SmartWalletProperties.PolymarketMonitor monitor = properties.getPolymarket();
        if (monitor == null) {
            return 0L;
        }
        if (isNotFoundError(e)) {
            Long cooldownMs = monitor.getPositionSyncNotFoundCooldownMs();
            return cooldownMs == null || cooldownMs <= 0 ? 1_800_000L : cooldownMs;
        }
        Long cooldownMs = monitor.getPositionSyncErrorCooldownMs();
        return cooldownMs == null || cooldownMs <= 0 ? 300_000L : cooldownMs;
    }

    // -------------------------------------------------------------------------
    // P2 Auto stop-loss: runs every 5 minutes, closes paper positions whose
    // unrealized ROI has fallen below the configured loss threshold.
    // -------------------------------------------------------------------------
    @Scheduled(fixedDelay = 300_000)
    public void checkStopLoss() {
        SmartWalletProperties.SignalRisk risk = properties.getSignalRisk();
        if (!properties.isEnabled() || risk == null || !risk.isEnableAutoStopLoss()) {
            return;
        }
        scanPaperPositionsForExitWithDynamicThreshold(AutoExitMode.STOP_LOSS);
        scanLivePositionsForStopLoss();
    }

    // -------------------------------------------------------------------------
    // Auto take-profit: runs every 5 minutes, closes paper/live positions
    // whose current unrealized return has reached the configured threshold so
    // capital can be recycled before oracle settlement.
    // -------------------------------------------------------------------------
    @Scheduled(fixedDelay = 300_000)
    public void checkTakeProfit() {
        SmartWalletProperties.SignalRisk risk = properties.getSignalRisk();
        if (!properties.isEnabled() || risk == null || !risk.isEnableAutoTakeProfit()) {
            return;
        }
        scanPaperPositionsForTakeProfit();
        scanLivePositionsForTakeProfit();
    }

    private boolean isNotFoundError(Exception e) {
        String message = collectExceptionText(e);
        return message.contains("status=404");
    }

    private boolean isRecoverableRpcFailure(Exception e) {
        String message = collectExceptionText(e);
        return message.contains("timeout")
                || message.contains("connection refused")
                || message.contains("connection reset")
                || message.contains("broken pipe")
                || message.contains("eof")
                || message.contains("socket")
                || message.contains("rpc")
                || message.contains("502")
                || message.contains("503")
                || message.contains("504")
                || message.contains("tls")
                || message.contains("ssl");
    }

    /**
     * 过滤掉来自白名单钱包的仓位，这些仓位不参与自动退出流程。
     * 通过批量查询仓位的入场订单，获取信号源钱包地址，检查是否在白名单中。
     */
    private List<SmartWalletPositionEntity> filterOutAllowlistPositions(List<SmartWalletPositionEntity> positions) {
        if (positions == null || positions.isEmpty()) {
            return positions;
        }
        Set<Long> positionIds = positions.stream()
                .map(SmartWalletPositionEntity::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (positionIds.isEmpty()) {
            return positions;
        }
        // 批量查询所有仓位的入场订单(BUY侧)
        List<SmartWalletOrderEntity> entryOrders = orderMapper.selectList(
                new LambdaQueryWrapper<SmartWalletOrderEntity>()
                        .in(SmartWalletOrderEntity::getPositionId, positionIds)
                        .eq(SmartWalletOrderEntity::getSide, "BUY"));
        // 收集每个仓位的信号源钱包地址
        Map<Long, String> positionWallets = new HashMap<>();
        for (SmartWalletOrderEntity order : entryOrders) {
            if (order.getPositionId() != null && order.getWalletAddress() != null) {
                positionWallets.putIfAbsent(order.getPositionId(), order.getWalletAddress());
            }
        }
        // 检查哪些仓位的信号源钱包在白名单中
        Set<Long> allowlistPositionIds = new HashSet<>();
        Set<String> checkedWallets = new HashSet<>();
        Map<String, Boolean> walletAllowlistCache = new HashMap<>();
        for (Map.Entry<Long, String> entry : positionWallets.entrySet()) {
            String wallet = entry.getValue().toLowerCase();
            if (!checkedWallets.contains(wallet)) {
                walletAllowlistCache.put(wallet, manualAllowlistService.findActive(entry.getValue()).isPresent());
                checkedWallets.add(wallet);
            }
            if (Boolean.TRUE.equals(walletAllowlistCache.get(wallet))) {
                allowlistPositionIds.add(entry.getKey());
            }
        }
        if (allowlistPositionIds.isEmpty()) {
            return positions;
        }
        log.info("[ALLOWLIST_EXIT_SKIP] 跳过白名单仓位自动退出 positionIds={}", allowlistPositionIds);
        return positions.stream()
                .filter(p -> !allowlistPositionIds.contains(p.getId()))
                .collect(Collectors.toList());
    }

    private void scanPaperPositionsForExit(BigDecimal threshold, AutoExitMode mode) {
        List<SmartWalletPositionEntity> openPositions = positionMapper.selectList(
                new LambdaQueryWrapper<SmartWalletPositionEntity>()
                        .eq(SmartWalletPositionEntity::getExecutionAddress, "paper-book")
                        .in(SmartWalletPositionEntity::getStatus, AUTO_EXIT_TRADABLE_POSITION_STATUSES));
        openPositions = filterAutoExitTradablePositions(openPositions);
        openPositions = filterOutAllowlistPositions(openPositions);
        // Populate positions with market info to enable delegate getters
        marketInfoService.populatePositions(openPositions);

        for (SmartWalletPositionEntity position : openPositions) {
            accountingService.markToMarket(position);
            refreshRealtimeMarkPrice(position);
            ExitDecision decision = buildExitDecision(position, threshold, mode);
            if (!decision.triggered()) {
                continue;
            }
            try {
                BigDecimal paperExitPrice = resolvePaperThresholdExitPrice(position, threshold, mode, decision.markPrice());
                SmartWalletPositionExitRequest exitRequest = new SmartWalletPositionExitRequest();
                exitRequest.setPrice(paperExitPrice);
                exitRequest.setQuantity(position.getQuantity());
                exitRequest.setReason(mode.reason());
                SmartWalletPositionExitResultView exitResult = accountingService.exitPosition(position.getId(), exitRequest);

                log.info("{} triggered positionId={} marketId={} outcome={} entry={} mark={} exitPrice={} threshold={}% actual={}% pnl={} currentStatus={}",
                        mode.logLabel(),
                        position.getId(),
                        position.getMarketId(),
                        position.getOutcome(),
                        position.getAverageEntryPrice(),
                        decision.markPrice(),
                        paperExitPrice,
                        threshold.multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP),
                        decision.rate().multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP),
                        safe(exitResult.getRealizedPnlDeltaUsdc()).setScale(2, RoundingMode.HALF_UP),
                        exitResult.getCurrentStatus());
                log.info("{}", flowPosition("S9_POSITION_EXIT", mode.logLabel(), "成功", position,
                        "mode", position.getExecutionAddress(),
                        "exitReason", mode.reason(),
                        "exitPrice", paperExitPrice,
                        "exitQuantity", position.getQuantity(),
                        "pnlRate", decision.rate(),
                        "realizedPnlDeltaUsdc", exitResult.getRealizedPnlDeltaUsdc(),
                        "reasonSummary", mode.reason()));
            } catch (Exception ex) {
                log.warn("{} exit failed for positionId={} marketId={}: {}",
                        mode.logLabel(),
                        position.getId(),
                        position.getMarketId(),
                        ex.getMessage(),
                        ex);
                log.info("{}", flowPosition("S9_POSITION_EXIT", mode.logLabel(), "失败", position,
                        "mode", position.getExecutionAddress(),
                        "exitReason", mode.reason(),
                        "firstRejectGate", "S9_EXIT_FAILED",
                        "blockerCategories", "EXIT_EXECUTION",
                        "reasonSummary", ex.getMessage()));
            }
        }
    }

    private void scanPaperPositionsForExitWithDynamicThreshold(AutoExitMode mode) {
        List<SmartWalletPositionEntity> openPositions = positionMapper.selectList(
                new LambdaQueryWrapper<SmartWalletPositionEntity>()
                        .eq(SmartWalletPositionEntity::getExecutionAddress, "paper-book")
                        .in(SmartWalletPositionEntity::getStatus, AUTO_EXIT_TRADABLE_POSITION_STATUSES));
        openPositions = filterAutoExitTradablePositions(openPositions);
        openPositions = filterOutAllowlistPositions(openPositions);
        marketInfoService.populatePositions(openPositions);

        for (SmartWalletPositionEntity position : openPositions) {
            accountingService.markToMarket(position);
            refreshRealtimeMarkPrice(position);
            // Record current mark price for trend analysis
            exitSignalProvider.recordMarkPrice(
                    position.getId(),
                    position.getLastMarkPrice(),
                    position.getAverageEntryPrice()
            );

            // Resolve dynamic threshold (falls back to fixed threshold when feature is disabled)
            BigDecimal threshold = exitSignalProvider.resolveStopLossThreshold(position);
            // F-P1-5: 在 wallet / scoring 调整前先扣费率（保证 fee 计入"基准阈值"）
            threshold = applyFeeAdjustment(threshold, position, mode);
            threshold = adjustThresholdByWalletBehavior(position, threshold, mode);
            threshold = adjustThresholdByExitScoringContext(position, threshold, mode);

            // Check threshold-based stop-loss
            ExitDecision decision = buildExitDecision(position, threshold, mode);

            // Check trend-based stop-loss (additional trigger)
            boolean trendTriggered = mode == AutoExitMode.STOP_LOSS
                    && exitSignalProvider.isTrendStopLossTriggered(
                            position.getId(),
                            position.getLastMarkPrice(),
                            position.getAverageEntryPrice()
                    );

            if (!decision.triggered() && !trendTriggered) {
                continue;
            }

            String exitReason = trendTriggered && !decision.triggered()
                    ? "TREND_STOP_LOSS_AUTO_EXIT"
                    : mode.reason();
            BigDecimal exitPrice = trendTriggered && !decision.triggered()
                    ? safe(position.getLastMarkPrice())
                    : resolvePaperThresholdExitPrice(position, threshold, mode, decision.markPrice());

            try {
                // F-P1-6: PAPER 模式也分批，逐档调用 accountingService.exitPosition
                StagedExitPlanner.Plan stagedPlan = stagedExitPlanner.planNext(position, exitPrice);
                BigDecimal exitQty = stagedPlan.isFullExit()
                        ? position.getQuantity()
                        : position.getQuantity().multiply(stagedPlan.getRatioOfRemaining()).setScale(8, RoundingMode.HALF_UP);
                SmartWalletPositionExitRequest exitRequest = new SmartWalletPositionExitRequest();
                exitRequest.setPrice(exitPrice);
                exitRequest.setQuantity(exitQty);
                exitRequest.setReason(stagedPlan.isFullExit()
                        ? exitReason
                        : exitReason + ";STAGED_LEG=" + (stagedPlan.getLegIndex() + 1) + "/" + stagedPlan.getLegCount());
                SmartWalletPositionExitResultView exitResult = accountingService.exitPosition(position.getId(), exitRequest);
                if (stagedPlan.isFullExit()) {
                    // 分批中途保留 trend 历史，全平时清空（与 LIVE 路径一致）
                    exitSignalProvider.clearPositionHistory(position.getId());
                }

                log.info("{} triggered positionId={} marketId={} outcome={} mark={} exitPrice={} threshold={}% actual={}% pnl={} trendTriggered={} currentStatus={}",
                        mode.logLabel(),
                        position.getId(),
                        position.getMarketId(),
                        position.getOutcome(),
                        safe(position.getLastMarkPrice()),
                        exitPrice,
                        threshold.multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP),
                        decision.rate().multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP),
                        safe(exitResult.getRealizedPnlDeltaUsdc()).setScale(2, RoundingMode.HALF_UP),
                        trendTriggered,
                        exitResult.getCurrentStatus());
                log.info("{}", flowPosition("S9_POSITION_EXIT", mode.logLabel(), "成功", position,
                        "mode", position.getExecutionAddress(),
                        "exitReason", exitReason,
                        "exitPrice", exitPrice,
                        "exitQuantity", position.getQuantity(),
                        "pnlRate", decision.rate(),
                        "trendTriggered", trendTriggered,
                        "realizedPnlDeltaUsdc", exitResult.getRealizedPnlDeltaUsdc(),
                        "reasonSummary", exitReason));
            } catch (Exception ex) {
                log.warn("{} exit failed for positionId={} marketId={}: {}",
                        mode.logLabel(),
                        position.getId(),
                        position.getMarketId(),
                        ex.getMessage(),
                        ex);
                log.info("{}", flowPosition("S9_POSITION_EXIT", mode.logLabel(), "失败", position,
                        "mode", position.getExecutionAddress(),
                        "exitReason", exitReason,
                        "firstRejectGate", "S9_EXIT_FAILED",
                        "blockerCategories", "EXIT_EXECUTION",
                        "reasonSummary", ex.getMessage()));
            }
        }
    }

    private void scanPaperPositionsForTakeProfit() {
        List<SmartWalletPositionEntity> openPositions = positionMapper.selectList(
                new LambdaQueryWrapper<SmartWalletPositionEntity>()
                        .eq(SmartWalletPositionEntity::getExecutionAddress, "paper-book")
                        .in(SmartWalletPositionEntity::getStatus, AUTO_EXIT_TRADABLE_POSITION_STATUSES));
        openPositions = filterAutoExitTradablePositions(openPositions);
        openPositions = filterOutAllowlistPositions(openPositions);
        marketInfoService.populatePositions(openPositions);

        for (SmartWalletPositionEntity position : openPositions) {
            accountingService.markToMarket(position);
            refreshRealtimeMarkPrice(position);
            BigDecimal threshold = resolveTakeProfitThreshold(position);
            threshold = adjustThresholdByWalletBehavior(position, threshold, AutoExitMode.TAKE_PROFIT);
            threshold = adjustThresholdByExitScoringContext(position, threshold, AutoExitMode.TAKE_PROFIT);
            ExitDecision decision = buildExitDecision(position, threshold, AutoExitMode.TAKE_PROFIT);
            if (!decision.triggered()) {
                continue;
            }
            try {
                BigDecimal paperExitPrice = resolvePaperThresholdExitPrice(position, threshold, AutoExitMode.TAKE_PROFIT, decision.markPrice());
                // F-P1-6: PAPER take-profit 也支持分批
                StagedExitPlanner.Plan stagedPlan = stagedExitPlanner.planNext(position, paperExitPrice);
                BigDecimal exitQty = stagedPlan.isFullExit()
                        ? position.getQuantity()
                        : position.getQuantity().multiply(stagedPlan.getRatioOfRemaining()).setScale(8, RoundingMode.HALF_UP);
                SmartWalletPositionExitRequest exitRequest = new SmartWalletPositionExitRequest();
                exitRequest.setPrice(paperExitPrice);
                exitRequest.setQuantity(exitQty);
                exitRequest.setReason(stagedPlan.isFullExit()
                        ? AutoExitMode.TAKE_PROFIT.reason()
                        : AutoExitMode.TAKE_PROFIT.reason()
                                + ";STAGED_LEG=" + (stagedPlan.getLegIndex() + 1) + "/" + stagedPlan.getLegCount());
                SmartWalletPositionExitResultView exitResult = accountingService.exitPosition(position.getId(), exitRequest);

                log.info("{} triggered positionId={} marketId={} outcome={} entry={} mark={} exitPrice={} threshold={}% actual={}% pnl={} currentStatus={}",
                        AutoExitMode.TAKE_PROFIT.logLabel(),
                        position.getId(),
                        position.getMarketId(),
                        position.getOutcome(),
                        position.getAverageEntryPrice(),
                        decision.markPrice(),
                        paperExitPrice,
                        threshold.multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP),
                        decision.rate().multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP),
                        safe(exitResult.getRealizedPnlDeltaUsdc()).setScale(2, RoundingMode.HALF_UP),
                        exitResult.getCurrentStatus());
                log.info("{}", flowPosition("S9_POSITION_EXIT", AutoExitMode.TAKE_PROFIT.logLabel(), "成功", position,
                        "mode", position.getExecutionAddress(),
                        "exitReason", AutoExitMode.TAKE_PROFIT.reason(),
                        "exitPrice", paperExitPrice,
                        "exitQuantity", position.getQuantity(),
                        "pnlRate", decision.rate(),
                        "realizedPnlDeltaUsdc", exitResult.getRealizedPnlDeltaUsdc(),
                        "reasonSummary", AutoExitMode.TAKE_PROFIT.reason()));
            } catch (Exception ex) {
                log.warn("{} exit failed for positionId={} marketId={}: {}",
                        AutoExitMode.TAKE_PROFIT.logLabel(),
                        position.getId(),
                        position.getMarketId(),
                        ex.getMessage(),
                        ex);
                log.info("{}", flowPosition("S9_POSITION_EXIT", AutoExitMode.TAKE_PROFIT.logLabel(), "失败", position,
                        "mode", position.getExecutionAddress(),
                        "exitReason", AutoExitMode.TAKE_PROFIT.reason(),
                        "firstRejectGate", "S9_EXIT_FAILED",
                        "blockerCategories", "EXIT_EXECUTION",
                        "reasonSummary", ex.getMessage()));
            }
        }
    }

    private void scanLivePositionsForTakeProfit() {
        ExecutionWalletContextLoader.ExecutionWalletContext wallet;
        try {
            wallet = walletContextLoader.requireLiveContext();
        } catch (Exception ex) {
            log.debug("Skip LIVE take-profit scan because execution wallet is not ready: {}", ex.getMessage());
            return;
        }
        if (!StringUtils.hasText(wallet.getAddress())) {
            return;
        }
        List<SmartWalletPositionEntity> openPositions = positionMapper.selectList(
                new LambdaQueryWrapper<SmartWalletPositionEntity>()
                        .eq(SmartWalletPositionEntity::getExecutionAddress, wallet.getAddress())
                        .in(SmartWalletPositionEntity::getStatus, AUTO_EXIT_TRADABLE_POSITION_STATUSES));
        openPositions = filterAutoExitTradablePositions(openPositions);
        openPositions = filterOutAllowlistPositions(openPositions);
        // Populate positions with market info to enable delegate getters
        marketInfoService.populatePositions(openPositions);
        for (SmartWalletPositionEntity position : openPositions) {
            if (hasPendingLiveExitOrder(position.getId())) {
                continue;
            }
            accountingService.markToMarket(position);
            refreshRealtimeMarkPrice(position);
            BigDecimal threshold = resolveTakeProfitThreshold(position);
            threshold = adjustThresholdByWalletBehavior(position, threshold, AutoExitMode.TAKE_PROFIT);
            threshold = adjustThresholdByExitScoringContext(position, threshold, AutoExitMode.TAKE_PROFIT);
            ExitDecision decision = buildExitDecision(position, threshold, AutoExitMode.TAKE_PROFIT);
            if (!decision.triggered()) {
                continue;
            }
            try {
                // F-P1-6: 分批出场 — enable-staged-exit=true 时按 partial-exit-ratios 拆 SELL 单
                StagedExitPlanner.Plan stagedPlan = stagedExitPlanner.planNext(position, decision.markPrice());
                SmartWalletExecutionResult result = stagedPlan.isFullExit()
                        ? clobExecutionService.executeLiveExit(
                                position,
                                decision.markPrice(),
                                AutoExitMode.TAKE_PROFIT.reason())
                        : clobExecutionService.executeLivePartialExit(
                                position,
                                stagedPlan.getRatioOfRemaining(),
                                decision.markPrice(),
                                AutoExitMode.TAKE_PROFIT.reason()
                                        + ";STAGED_LEG=" + (stagedPlan.getLegIndex() + 1) + "/" + stagedPlan.getLegCount());
                accountingService.recordLiveExitExecution(position, result, AutoExitMode.TAKE_PROFIT.reason());
                log.info("LIVE take-profit submitted positionId={} marketId={} outcome={} mark={} threshold={}% actual={}% status={} orderId={}",
                        position.getId(),
                        position.getMarketId(),
                        position.getOutcome(),
                        decision.markPrice(),
                        threshold.multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP),
                        decision.rate().multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP),
                        result.getStatus(),
                        result.getExternalOrderId());
                log.info("{}", flowPosition("S9_POSITION_EXIT", "LIVE_TAKE-PROFIT", result != null && result.isSuccess() ? "已提交" : "失败", position,
                        "mode", "LIVE",
                        "exitReason", AutoExitMode.TAKE_PROFIT.reason(),
                        "externalOrderId", result == null ? null : result.getExternalOrderId(),
                        "orderStatus", result == null ? null : result.getStatus(),
                        "exitPrice", decision.markPrice(),
                        "pnlRate", decision.rate(),
                        "reasonSummary", result == null ? null : result.getMessage()));
            } catch (Exception ex) {
                log.warn("LIVE take-profit failed for positionId={} marketId={}: {}",
                        position.getId(),
                        position.getMarketId(),
                        ex.getMessage(),
                        ex);
                log.info("{}", flowPosition("S9_POSITION_EXIT", "LIVE_TAKE-PROFIT", "失败", position,
                        "mode", "LIVE",
                        "exitReason", AutoExitMode.TAKE_PROFIT.reason(),
                        "firstRejectGate", "S9_EXIT_FAILED",
                        "blockerCategories", "EXIT_EXECUTION",
                        "reasonSummary", ex.getMessage()));
            }
        }
    }

    private void scanLivePositionsForStopLoss() {
        ExecutionWalletContextLoader.ExecutionWalletContext wallet;
        try {
            wallet = walletContextLoader.requireLiveContext();
        } catch (Exception ex) {
            log.debug("Skip LIVE stop-loss scan because execution wallet is not ready: {}", ex.getMessage());
            return;
        }
        if (!StringUtils.hasText(wallet.getAddress())) {
            return;
        }
        List<SmartWalletPositionEntity> openPositions = positionMapper.selectList(
                new LambdaQueryWrapper<SmartWalletPositionEntity>()
                        .eq(SmartWalletPositionEntity::getExecutionAddress, wallet.getAddress())
                        .in(SmartWalletPositionEntity::getStatus, AUTO_EXIT_TRADABLE_POSITION_STATUSES));
        openPositions = filterAutoExitTradablePositions(openPositions);
        openPositions = filterOutAllowlistPositions(openPositions);
        marketInfoService.populatePositions(openPositions);
        for (SmartWalletPositionEntity position : openPositions) {
            if (hasPendingLiveExitOrder(position.getId())) {
                continue;
            }
            accountingService.markToMarket(position);
            refreshRealtimeMarkPrice(position);
            exitSignalProvider.recordMarkPrice(
                    position.getId(),
                    position.getLastMarkPrice(),
                    position.getAverageEntryPrice()
            );
            BigDecimal threshold = exitSignalProvider.resolveStopLossThreshold(position);
            // F-P1-5: 阈值加上双边 taker fee
            threshold = applyFeeAdjustment(threshold, position, AutoExitMode.STOP_LOSS);
            threshold = adjustThresholdByWalletBehavior(position, threshold, AutoExitMode.STOP_LOSS);
            threshold = adjustThresholdByExitScoringContext(position, threshold, AutoExitMode.STOP_LOSS);
            ExitDecision decision = buildExitDecision(position, threshold, AutoExitMode.STOP_LOSS);
            boolean trendTriggered = exitSignalProvider.isTrendStopLossTriggered(
                    position.getId(),
                    position.getLastMarkPrice(),
                    position.getAverageEntryPrice()
            );
            if (!decision.triggered() && !trendTriggered) {
                continue;
            }
            String exitReason = trendTriggered && !decision.triggered()
                    ? "TREND_STOP_LOSS_AUTO_EXIT"
                    : AutoExitMode.STOP_LOSS.reason();
            BigDecimal exitPrice = trendTriggered && !decision.triggered()
                    ? safe(position.getLastMarkPrice())
                    : decision.markPrice();
            try {
                // F-P1-6: 分批出场 — enable-staged-exit=true 时按 partial-exit-ratios 拆 SELL 单
                StagedExitPlanner.Plan stagedPlan = stagedExitPlanner.planNext(position, exitPrice);
                SmartWalletExecutionResult result = stagedPlan.isFullExit()
                        ? clobExecutionService.executeLiveExit(
                                position,
                                exitPrice,
                                exitReason)
                        : clobExecutionService.executeLivePartialExit(
                                position,
                                stagedPlan.getRatioOfRemaining(),
                                exitPrice,
                                exitReason + ";STAGED_LEG="
                                        + (stagedPlan.getLegIndex() + 1) + "/" + stagedPlan.getLegCount());
                accountingService.recordLiveExitExecution(position, result, exitReason);
                if (result != null && result.isSuccess() && stagedPlan.isFullExit()) {
                    // 只在最后一档（全平）时清掉 trend stop-loss 价格历史，分批中途保留
                    exitSignalProvider.clearPositionHistory(position.getId());
                }
                log.info("LIVE stop-loss submitted positionId={} marketId={} outcome={} mark={} threshold={}% actual={}% trendTriggered={} status={} orderId={}",
                        position.getId(),
                        position.getMarketId(),
                        position.getOutcome(),
                        exitPrice,
                        threshold.multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP),
                        decision.rate().multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP),
                        trendTriggered,
                        result == null ? null : result.getStatus(),
                        result == null ? null : result.getExternalOrderId());
                log.info("{}", flowPosition("S9_POSITION_EXIT", "LIVE_STOP-LOSS", result != null && result.isSuccess() ? "已提交" : "失败", position,
                        "mode", "LIVE",
                        "exitReason", exitReason,
                        "externalOrderId", result == null ? null : result.getExternalOrderId(),
                        "orderStatus", result == null ? null : result.getStatus(),
                        "exitPrice", exitPrice,
                        "pnlRate", decision.rate(),
                        "trendTriggered", trendTriggered,
                        "reasonSummary", result == null ? null : result.getMessage()));
            } catch (Exception ex) {
                log.warn("LIVE stop-loss failed for positionId={} marketId={}: {}",
                        position.getId(),
                        position.getMarketId(),
                        ex.getMessage(),
                        ex);
                log.info("{}", flowPosition("S9_POSITION_EXIT", "LIVE_STOP-LOSS", "失败", position,
                        "mode", "LIVE",
                        "exitReason", exitReason,
                        "firstRejectGate", "S9_EXIT_FAILED",
                        "blockerCategories", "EXIT_EXECUTION",
                        "reasonSummary", ex.getMessage()));
            }
        }
    }

    private BigDecimal resolveTakeProfitThreshold(SmartWalletPositionEntity position) {
        SmartWalletProperties.SignalRisk risk = properties.getSignalRisk();
        BigDecimal configured = risk.getAutoTakeProfitThresholdRate() == null
                ? new BigDecimal("0.20")
                : risk.getAutoTakeProfitThresholdRate();
        // F-P1-5: 阈值加上双边 taker fee，确保净盈利达到 configured 阈值才出场
        configured = applyFeeAdjustment(configured, position, AutoExitMode.TAKE_PROFIT);
        BigDecimal unitCost = resolveUnitCostBasis(position);
        if (unitCost.compareTo(BigDecimal.ZERO) <= 0 || unitCost.compareTo(BigDecimal.ONE) >= 0) {
            return configured;
        }

        BigDecimal maxAchievableThreshold = BigDecimal.ONE.subtract(unitCost)
                .divide(unitCost, 8, RoundingMode.HALF_UP);
        if (maxAchievableThreshold.compareTo(BigDecimal.ZERO) <= 0) {
            return configured;
        }

        // Binary markets cap settlement proceeds at 1. Keep 20% upside headroom so
        // take-profit remains reachable for high-entry positions without requiring a
        // perfect move to 1.00 before the next scan.
        BigDecimal reachableThreshold = maxAchievableThreshold.multiply(new BigDecimal("0.80"))
                .setScale(4, RoundingMode.HALF_UP);
        if (reachableThreshold.compareTo(BigDecimal.ZERO) <= 0) {
            return configured;
        }
        return configured.min(reachableThreshold);
    }

    private List<SmartWalletPositionEntity> filterAutoExitTradablePositions(List<SmartWalletPositionEntity> positions) {
        if (positions == null || positions.isEmpty()) {
            return List.of();
        }
        return positions.stream()
                .filter(this::isAutoExitTradablePosition)
                .collect(Collectors.toList());
    }

    private boolean isAutoExitTradablePosition(SmartWalletPositionEntity position) {
        if (position == null || !StringUtils.hasText(position.getStatus())) {
            return false;
        }
        return AUTO_EXIT_TRADABLE_POSITION_STATUSES.stream()
                .anyMatch(status -> status.equalsIgnoreCase(position.getStatus()));
    }

    /**
     * F-P1-5：把 taker fee（双边）加到 stop-loss / take-profit 配置阈值上，
     * 这样实际触发出场时，扣完手续费后的净 ROI 才符合 configured 阈值。
     *
     * <p>公式：{@code effectiveThreshold = configured + feeMultiplier × observedTakerFeeRate}，
     *   其中 {@code feeMultiplier=2} 默认（进出两次 taker fee）。受
     *   {@code signal-risk.enable-exit-threshold-fee-adjustment}（默认 true）控制；
     *   单次扣除上限为 {@code signal-risk.exit-threshold-fee-adjustment-cap}（默认 0.08）。</p>
     *
     * <p>该方法 fee-rate 来源是 {@link PolymarketFeeScheduleService}（运行时缓存 → 品类映射 → 默认 2%），
     *   按品类动态生效。</p>
     */
    private BigDecimal applyFeeAdjustment(BigDecimal configured,
                                          SmartWalletPositionEntity position,
                                          AutoExitMode mode) {
        if (configured == null) {
            return null;
        }
        SmartWalletProperties.SignalRisk risk = properties.getSignalRisk();
        if (risk == null || !risk.isEnableExitThresholdFeeAdjustment()) {
            return configured;
        }
        if (feeScheduleService == null || position == null) {
            return configured;
        }
        String marketId = position.getMarketId();
        String category = null;
        if (position.getMarketInfo() != null) {
            // 2026-05-17 ray@cowork: entity 上没有 getCategory()，用 normalized_category 优先，回退 source_category
            category = position.getMarketInfo().getNormalizedCategory();
            if (!org.springframework.util.StringUtils.hasText(category)) {
                category = position.getMarketInfo().getSourceCategory();
            }
        }
        BigDecimal feeRate;
        try {
            feeRate = feeScheduleService.getTakerFeeRate(marketId, category);
        } catch (Exception ex) {
            log.debug("F-P1-5 fee rate lookup failed for positionId={} marketId={}: {}",
                    position.getId(), marketId, ex.getMessage());
            return configured;
        }
        if (feeRate == null || feeRate.compareTo(BigDecimal.ZERO) <= 0) {
            return configured;
        }
        BigDecimal multiplier = risk.getExitThresholdFeeMultiplier() == null
                ? new BigDecimal("2") : risk.getExitThresholdFeeMultiplier();
        BigDecimal adjustment = feeRate.multiply(multiplier).setScale(6, RoundingMode.HALF_UP);
        BigDecimal cap = risk.getExitThresholdFeeAdjustmentCap();
        if (cap != null && cap.compareTo(BigDecimal.ZERO) > 0 && adjustment.compareTo(cap) > 0) {
            adjustment = cap;
        }
        BigDecimal adjusted = configured.add(adjustment).setScale(4, RoundingMode.HALF_UP);
        if (log.isDebugEnabled()) {
            log.debug("F-P1-5 fee-adjusted threshold positionId={} marketId={} category={} mode={} configured={} feeRate={} adjustment={} adjusted={}",
                    position.getId(), marketId, category, mode.logLabel(), configured, feeRate, adjustment, adjusted);
        }
        return adjusted;
    }

    private BigDecimal adjustThresholdByWalletBehavior(SmartWalletPositionEntity position,
                                                       BigDecimal baseThreshold,
                                                       AutoExitMode mode) {
        if (position == null || baseThreshold == null || baseThreshold.compareTo(BigDecimal.ZERO) <= 0) {
            return baseThreshold;
        }
        String walletAddress = resolveDrivingWalletAddress(position.getId());
        if (!StringUtils.hasText(walletAddress)) {
            return baseThreshold;
        }
        String lane = walletBehaviorProfileService.recommendedExitLane(walletAddress);
        if (!StringUtils.hasText(lane)) {
            return baseThreshold;
        }
        BigDecimal adjusted = switch (lane.trim().toUpperCase()) {
            case "EARLY_TAKE_PROFIT" -> mode == AutoExitMode.TAKE_PROFIT
                    ? baseThreshold.multiply(new BigDecimal("0.75"))
                    : baseThreshold;
            case "FAST_STOP_LOSS" -> mode == AutoExitMode.STOP_LOSS
                    ? baseThreshold.multiply(new BigDecimal("0.80"))
                    : baseThreshold;
            case "FAST_EXIT_OR_SKIP" -> mode == AutoExitMode.STOP_LOSS
                    ? baseThreshold.multiply(new BigDecimal("0.70"))
                    : baseThreshold.multiply(new BigDecimal("0.60"));
            case "HYBRID_EARLY_EXIT" -> mode == AutoExitMode.STOP_LOSS
                    ? baseThreshold.multiply(new BigDecimal("0.85"))
                    : baseThreshold.multiply(new BigDecimal("0.80"));
            default -> baseThreshold;
        };
        BigDecimal normalized = normalizeThreshold(adjusted, mode);
        if (normalized.compareTo(baseThreshold) != 0) {
            log.debug("Adjusted {} threshold for positionId={} marketId={} walletAddress={} lane={} {} -> {}",
                    mode.logLabel(),
                    position.getId(),
                    position.getMarketId(),
                    walletAddress,
                    lane,
                    baseThreshold.setScale(4, RoundingMode.HALF_UP),
                    normalized.setScale(4, RoundingMode.HALF_UP));
        }
        return normalized;
    }

    private BigDecimal adjustThresholdByExitScoringContext(SmartWalletPositionEntity position,
                                                           BigDecimal baseThreshold,
                                                           AutoExitMode mode) {
        SmartWalletProperties.SignalRisk risk = properties.getSignalRisk();
        if (position == null
                || baseThreshold == null
                || baseThreshold.compareTo(BigDecimal.ZERO) <= 0
                || !"SCORING".equalsIgnoreCase(risk.getExitSignalProviderMode())) {
            return baseThreshold;
        }
        ExitScoringContext context = buildExitScoringContext(position);
        BigDecimal pressure = safe(risk.getExitScoringConsensusDecayWeight()).multiply(context.consensusDecay())
                .add(safe(risk.getExitScoringOrderbookDeteriorationWeight()).multiply(context.orderbookDeterioration()))
                .add(safe(risk.getExitScoringHighConcentrationWeight()).multiply(context.highConcentrationPressure()))
                .add(safe(risk.getExitScoringExtremeConcentrationWeight()).multiply(context.extremeConcentrationPressure()))
                .add(safe(risk.getExitScoringSourceWalletReductionWeight()).multiply(context.sourceWalletReduction()))
                .subtract(safe(risk.getExitScoringSourceWalletAddWeight()).multiply(context.sourceWalletAdd()))
                .max(new BigDecimal("-0.50"))
                .min(BigDecimal.ONE);
        BigDecimal multiplier = mode == AutoExitMode.STOP_LOSS
                ? BigDecimal.ONE.subtract(pressure.multiply(new BigDecimal("0.35")))
                : BigDecimal.ONE.subtract(pressure.multiply(new BigDecimal("0.45")));
        BigDecimal adjusted = normalizeThreshold(baseThreshold.multiply(multiplier), mode);
        if (adjusted.compareTo(baseThreshold) != 0) {
            log.debug("Exit scoring context adjusted {} threshold for positionId={} marketId={} {} -> {} (consensusDecay={}, orderbook={}, highConcentration={}, extremeConcentration={}, sourceReduce={}, sourceAdd={})",
                    mode.logLabel(),
                    position.getId(),
                    position.getMarketId(),
                    baseThreshold.setScale(4, RoundingMode.HALF_UP),
                    adjusted.setScale(4, RoundingMode.HALF_UP),
                    context.consensusDecay().setScale(4, RoundingMode.HALF_UP),
                    context.orderbookDeterioration().setScale(4, RoundingMode.HALF_UP),
                    context.highConcentrationPressure().setScale(4, RoundingMode.HALF_UP),
                    context.extremeConcentrationPressure().setScale(4, RoundingMode.HALF_UP),
                    context.sourceWalletReduction().setScale(4, RoundingMode.HALF_UP),
                    context.sourceWalletAdd().setScale(4, RoundingMode.HALF_UP));
        }
        return adjusted;
    }

    private String resolveDrivingWalletAddress(Long positionId) {
        if (positionId == null) {
            return null;
        }
        SmartWalletOrderEntity entryOrder = orderMapper.selectOne(new LambdaQueryWrapper<SmartWalletOrderEntity>()
                .eq(SmartWalletOrderEntity::getPositionId, positionId)
                .eq(SmartWalletOrderEntity::getSide, "BUY")
                .eq(SmartWalletOrderEntity::getOrderType, "ENTRY")
                .isNotNull(SmartWalletOrderEntity::getWalletAddress)
                .orderByDesc(SmartWalletOrderEntity::getPlacedAt)
                .orderByDesc(SmartWalletOrderEntity::getId)
                .last("limit 1"));
        return entryOrder == null ? null : entryOrder.getWalletAddress();
    }

    private ExitScoringContext buildExitScoringContext(SmartWalletPositionEntity position) {
        SmartWalletProperties.SignalRisk risk = properties.getSignalRisk();
        String drivingWallet = resolveDrivingWalletAddress(position.getId());
        BigDecimal consensusDecay = resolveConsensusDecay(position, risk);
        BigDecimal sourceReduction = BigDecimal.ZERO;
        BigDecimal sourceAdd = BigDecimal.ZERO;
        if (StringUtils.hasText(drivingWallet)) {
            SmartWalletTradeEventEntity latestFollowOnEvent = tradeEventMapper.selectOne(new LambdaQueryWrapper<SmartWalletTradeEventEntity>()
                    .eq(SmartWalletTradeEventEntity::getWalletAddress, drivingWallet)
                    .eq(SmartWalletTradeEventEntity::getMarketId, position.getMarketId())
                    .eq(SmartWalletTradeEventEntity::getOutcome, position.getOutcome())
                    .gt(position.getOpenedAt() != null, SmartWalletTradeEventEntity::getOpenedAt, position.getOpenedAt())
                    .orderByDesc(SmartWalletTradeEventEntity::getOpenedAt)
                    .orderByDesc(SmartWalletTradeEventEntity::getId)
                    .last("limit 1"));
            if (latestFollowOnEvent != null) {
                if ("SELL".equalsIgnoreCase(latestFollowOnEvent.getSide())) {
                    sourceReduction = BigDecimal.ONE;
                } else if ("BUY".equalsIgnoreCase(latestFollowOnEvent.getSide())) {
                    sourceAdd = new BigDecimal("0.65");
                }
            }
        }
        BigDecimal orderbookDeterioration = resolveOrderbookDeterioration(position);
        ConcentrationPressure concentrationPressure = resolveConcentrationPressure(position);
        return new ExitScoringContext(
                consensusDecay.max(BigDecimal.ZERO).min(BigDecimal.ONE),
                orderbookDeterioration.max(BigDecimal.ZERO).min(BigDecimal.ONE),
                concentrationPressure.highPressure(),
                concentrationPressure.extremePressure(),
                sourceReduction.max(BigDecimal.ZERO).min(BigDecimal.ONE),
                sourceAdd.max(BigDecimal.ZERO).min(BigDecimal.ONE)
        );
    }

    private BigDecimal resolveConsensusDecay(SmartWalletPositionEntity position, SmartWalletProperties.SignalRisk risk) {
        if (position == null || !StringUtils.hasText(position.getMarketId())) {
            return BigDecimal.ZERO;
        }
        ConsensusSignalView consensus = consensusSignalService.listConsensusSignals(
                        risk.getConsensusWindowSeconds(),
                        position.getMarketId(),
                        position.getOutcome(),
                        "BUY",
                        5)
                .stream()
                .filter(item -> Objects.equals(item.getMarketId(), position.getMarketId()))
                .filter(item -> Objects.equals(item.getOutcome(), position.getOutcome()))
                .findFirst()
                .orElse(null);
        BigDecimal currentConsensus = consensus == null ? BigDecimal.ZERO : safe(
                consensus.getQualityWeightedConsensusScore() == null ? consensus.getConsensusScore() : consensus.getQualityWeightedConsensusScore()
        );
        BigDecimal baseline = safe(risk.getExitScoringBaselineConsensus());
        if (baseline.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return baseline.subtract(currentConsensus)
                .divide(baseline, 8, RoundingMode.HALF_UP)
                .max(BigDecimal.ZERO)
                .min(BigDecimal.ONE);
    }

    private BigDecimal resolveOrderbookDeterioration(SmartWalletPositionEntity position) {
        if (position == null || !StringUtils.hasText(position.getMarketId())) {
            return BigDecimal.ZERO;
        }
        Date now = new Date();
        SmartWalletMarketSnapshotEntity snapshot = marketSnapshotCollectorService.findLatestSnapshotAtOrBefore(
                position.getMarketId(),
                position.getOutcome(),
                now
        );
        if (snapshot == null) {
            snapshot = marketSnapshotCollectorService.findNearestSnapshotAtOrAfter(position.getMarketId(), position.getOutcome(), now);
        }
        if (snapshot == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal spreadRisk = safe(snapshot.getSpreadBps()).divide(new BigDecimal("200"), 8, RoundingMode.HALF_UP).min(BigDecimal.ONE);
        BigDecimal volatilityRisk = safe(snapshot.getRealizedVol30s()).divide(new BigDecimal("0.20"), 8, RoundingMode.HALF_UP).min(BigDecimal.ONE);
        BigDecimal imbalanceRisk = safe(snapshot.getOrderImbalance()).compareTo(BigDecimal.ZERO) >= 0
                ? BigDecimal.ZERO
                : safe(snapshot.getOrderImbalance()).abs().min(BigDecimal.ONE);
        return spreadRisk.multiply(new BigDecimal("0.45"))
                .add(volatilityRisk.multiply(new BigDecimal("0.30")))
                .add(imbalanceRisk.multiply(new BigDecimal("0.25")))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private ConcentrationPressure resolveConcentrationPressure(SmartWalletPositionEntity position) {
        if (position == null || !StringUtils.hasText(position.getMarketId())) {
            return ConcentrationPressure.none();
        }
        SmartWalletProperties.SignalRisk risk = properties.getSignalRisk();
        ConcentrationSnapshot snap =
                marketConcentrationService.analyze(position.getMarketId(), position.getOutcome());
        if (snap == null || !snap.isDataAvailable()) {
            return ConcentrationPressure.none();
        }
        if (snap.isExtremelyConcentrated()) {
            return new ConcentrationPressure(BigDecimal.ONE, BigDecimal.ONE);
        }
        if (snap.isHighlyConcentrated()) {
            return new ConcentrationPressure(BigDecimal.ONE, BigDecimal.ZERO);
        }
        BigDecimal safeTop3 = safe(risk.getConcentrationStakeSafeTop3Pct());
        BigDecimal blockTop3 = safe(risk.getMaxTop3ConcentrationPct());
        BigDecimal top3 = safe(snap.top3Pct());
        if (top3.compareTo(safeTop3) <= 0 || blockTop3.compareTo(safeTop3) <= 0) {
            return ConcentrationPressure.none();
        }
        BigDecimal scaled = top3.subtract(safeTop3)
                .divide(blockTop3.subtract(safeTop3), 8, RoundingMode.HALF_UP)
                .max(BigDecimal.ZERO)
                .min(BigDecimal.ONE);
        return new ConcentrationPressure(scaled, BigDecimal.ZERO);
    }

    private BigDecimal normalizeThreshold(BigDecimal threshold, AutoExitMode mode) {
        if (threshold == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal min = new BigDecimal("0.03");
        BigDecimal max = mode == AutoExitMode.STOP_LOSS ? new BigDecimal("0.50") : BigDecimal.ONE;
        BigDecimal normalized = threshold.setScale(4, RoundingMode.HALF_UP);
        if (normalized.compareTo(min) < 0) {
            return min.setScale(4, RoundingMode.HALF_UP);
        }
        if (normalized.compareTo(max) > 0) {
            return max.setScale(4, RoundingMode.HALF_UP);
        }
        return normalized;
    }

    private BigDecimal resolvePaperThresholdExitPrice(SmartWalletPositionEntity position,
                                                      BigDecimal threshold,
                                                      AutoExitMode mode,
                                                      BigDecimal fallbackPrice) {
        // 盈利审计修复(2026-06-03, P0-1)：纸面止盈/止损此前按"恰好触发阈值价"零滑点记账，
        // 系统性虚高纸面收益/胜率。改为优先用真实盘口 bid（fallbackPrice = 调用前刚由 orderbook
        // bestBid 刷新的 markPrice）作为成交价——止损按真实 bid 兑现（gap-down 时更真实），
        // 止盈触发时 bid 已≥阈值，用 bid 同样真实。盘口不可用时退回阈值价（保留原兜底行为）。
        BigDecimal realBid = safe(fallbackPrice);
        if (realBid.compareTo(BigDecimal.ZERO) > 0 && realBid.compareTo(BigDecimal.ONE) <= 0) {
            return realBid.setScale(8, RoundingMode.HALF_UP);
        }
        BigDecimal quantity = safe(position == null ? null : position.getQuantity());
        BigDecimal costBasis = safe(position == null ? null : position.getCostBasisUsdc());
        if (quantity.compareTo(BigDecimal.ZERO) <= 0 || costBasis.compareTo(BigDecimal.ZERO) <= 0 || threshold == null) {
            return safe(fallbackPrice);
        }

        BigDecimal multiplier = mode == AutoExitMode.STOP_LOSS
                ? BigDecimal.ONE.subtract(threshold)
                : BigDecimal.ONE.add(threshold);
        BigDecimal thresholdPrice = costBasis.multiply(multiplier)
                .divide(quantity, 8, RoundingMode.HALF_UP);
        if (thresholdPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return safe(fallbackPrice);
        }
        if (thresholdPrice.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE.setScale(8, RoundingMode.HALF_UP);
        }
        return thresholdPrice.setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal resolveUnitCostBasis(SmartWalletPositionEntity position) {
        BigDecimal quantity = safe(position == null ? null : position.getQuantity());
        BigDecimal costBasis = safe(position == null ? null : position.getCostBasisUsdc());
        if (quantity.compareTo(BigDecimal.ZERO) > 0 && costBasis.compareTo(BigDecimal.ZERO) > 0) {
            return costBasis.divide(quantity, 8, RoundingMode.HALF_UP);
        }
        return safe(position == null ? null : position.getAverageEntryPrice());
    }

    private ExitDecision buildExitDecision(SmartWalletPositionEntity position,
                                           BigDecimal threshold,
                                           AutoExitMode mode) {
        if (position == null) {
            return ExitDecision.notTriggered();
        }
        BigDecimal quantity = safe(position.getQuantity());
        BigDecimal costBasis = safe(position.getCostBasisUsdc());
        BigDecimal markPrice = safe(position.getLastMarkPrice());
        if (quantity.compareTo(BigDecimal.ZERO) <= 0
                || costBasis.compareTo(BigDecimal.ZERO) <= 0
                || markPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return ExitDecision.notTriggered();
        }

        BigDecimal currentValue = markPrice.multiply(quantity).setScale(8, RoundingMode.HALF_UP);
        BigDecimal pnl = currentValue.subtract(costBasis).setScale(8, RoundingMode.HALF_UP);
        BigDecimal pnlRate = pnl.divide(costBasis, 8, RoundingMode.HALF_UP);
        boolean triggered = mode == AutoExitMode.STOP_LOSS
                ? pnlRate.compareTo(threshold.negate()) <= 0
                : pnlRate.compareTo(threshold) >= 0;
        return new ExitDecision(triggered, markPrice, pnlRate);
    }

    private boolean hasPendingLiveExitOrder(Long positionId) {
        if (positionId == null) {
            return false;
        }
        Long count = orderMapper.selectCount(new LambdaQueryWrapper<SmartWalletOrderEntity>()
                .eq(SmartWalletOrderEntity::getPositionId, positionId)
                .eq(SmartWalletOrderEntity::getSide, "SELL")
                .eq(SmartWalletOrderEntity::getOrderType, "EXIT")
                .in(SmartWalletOrderEntity::getStatus, List.of("LIVE", "PARTIALLY_FILLED", "OPEN", "PENDING", "PLACED", "UNMATCHED")));
        return count != null && count > 0;
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String flowPosition(String stage,
                                String action,
                                String result,
                                SmartWalletPositionEntity position,
                                Object... extra) {
        List<Object> fields = new java.util.ArrayList<>();
        fields.add("flowStage");
        fields.add(stage);
        fields.add("action");
        fields.add(action);
        fields.add("result");
        fields.add(result);
        fields.add("positionId");
        fields.add(position == null ? null : position.getId());
        fields.add("marketId");
        fields.add(position == null ? null : position.getMarketId());
        fields.add("outcome");
        fields.add(position == null ? null : position.getOutcome());
        fields.add("stakeUsdc");
        fields.add(position == null ? null : position.getCostBasisUsdc());
        fields.add("entryLane");
        fields.add("AUTO_EXIT");
        if (extra != null) {
            for (Object item : extra) {
                fields.add(item);
            }
        }
        return StrategyFlowLogFormatter.flow(fields.toArray());
    }

    private String collectExceptionText(Throwable throwable) {
        StringBuilder builder = new StringBuilder();
        Throwable current = throwable;
        while (current != null) {
            if (StringUtils.hasText(current.getMessage())) {
                if (builder.length() > 0) {
                    builder.append(" | ");
                }
                builder.append(current.getClass().getSimpleName()).append(": ").append(current.getMessage());
            }
            current = current.getCause();
        }
        return builder.toString().toLowerCase();
    }

    /**
     * 从 Polymarket CLOB API 获取实时 mark price（best bid 或 midpoint）。
     * 用于 Paper 仓位止盈/止损/时间窗口退出前的价格刷新，确保退出价格反映真实市场行情。
     * 如果拉取失败则回退到 markToMarket（本地 trade events）。
     */
    private void refreshRealtimeMarkPrice(SmartWalletPositionEntity position) {
        String tokenId = position.getTokenId();
        if (tokenId == null || tokenId.isBlank()) {
            return;
        }
        try {
            PolymarketApiClient.OrderBook orderBook = marketDataCacheService.getOrderBook(tokenId);
            if (orderBook == null) {
                return;
            }
            // 优先使用 best bid（卖出方向的最优可成交价）
            BigDecimal realtimePrice = null;
            if (orderBook.getBids() != null && !orderBook.getBids().isEmpty()) {
                realtimePrice = orderBook.getBids().get(0).getPrice();
            }
            // fallback: midpoint
            if (realtimePrice == null || realtimePrice.compareTo(BigDecimal.ZERO) <= 0) {
                realtimePrice = orderBook.getMidpoint();
            }
            if (realtimePrice != null && realtimePrice.compareTo(BigDecimal.ZERO) > 0) {
                position.setLastMarkPrice(realtimePrice);
                BigDecimal unrealized = realtimePrice.multiply(safe(position.getQuantity()))
                        .subtract(safe(position.getCostBasisUsdc()))
                        .setScale(8, RoundingMode.HALF_UP);
                position.setUnrealizedPnlUsdc(unrealized);
            }
        } catch (Exception ex) {
            log.debug("refreshRealtimeMarkPrice failed for tokenId={}: {}", tokenId, ex.getMessage());
        }
    }

    private enum AutoExitMode {
        STOP_LOSS("STOP-LOSS", "STOP_LOSS_AUTO_EXIT"),
        TAKE_PROFIT("TAKE-PROFIT", "TAKE_PROFIT_AUTO_EXIT"),
        CAPITAL_RECYCLE("CAPITAL-RECYCLE", "CAPITAL_RECYCLE_AUTO_EXIT");

        private final String logLabel;
        private final String reason;

        AutoExitMode(String logLabel, String reason) {
            this.logLabel = logLabel;
            this.reason = reason;
        }

        public String logLabel() {
            return logLabel;
        }

        public String reason() {
            return reason;
        }
    }

    private record ExitDecision(boolean triggered, BigDecimal markPrice, BigDecimal rate) {
        private static ExitDecision notTriggered() {
            return new ExitDecision(false, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    private record ExitScoringContext(BigDecimal consensusDecay,
                                      BigDecimal orderbookDeterioration,
                                      BigDecimal highConcentrationPressure,
                                      BigDecimal extremeConcentrationPressure,
                                      BigDecimal sourceWalletReduction,
                                      BigDecimal sourceWalletAdd) {
    }

    private record ConcentrationPressure(BigDecimal highPressure,
                                         BigDecimal extremePressure) {
        private static ConcentrationPressure none() {
            return new ConcentrationPressure(BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }
}
