package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletExecutionResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPositionExitRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPositionExitResultView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
@RequiredArgsConstructor
public class SmartWalletPositionSyncService {

    private final SmartWalletProperties properties;
    private final PolymarketApiClient polymarketApiClient;
    private final ExecutionWalletContextLoader walletContextLoader;
    private final SmartWalletPositionMapper positionMapper;
    private final SmartWalletOrderMapper orderMapper;
    private final SmartWalletAccountingService accountingService;
    private final PolymarketClobExecutionService clobExecutionService;
    private final SmartWalletRecoveryService recoveryService;
    private final SmartWalletMarketInfoService marketInfoService;
    private final TimeAwareStopLossService timeAwareStopLossService;
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
            log.debug("Current positions count for {}: {}", context.getAddress(), positions.size());
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
        BigDecimal threshold = risk.getAutoTakeProfitThresholdRate() == null
                ? new BigDecimal("0.20") : risk.getAutoTakeProfitThresholdRate();
        scanPaperPositionsForExit(threshold, AutoExitMode.TAKE_PROFIT);
        scanLivePositionsForTakeProfit(threshold);
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

    private void scanPaperPositionsForExit(BigDecimal threshold, AutoExitMode mode) {
        List<SmartWalletPositionEntity> openPositions = positionMapper.selectList(
                new LambdaQueryWrapper<SmartWalletPositionEntity>()
                        .eq(SmartWalletPositionEntity::getExecutionAddress, "paper-book")
                        .in(SmartWalletPositionEntity::getStatus, List.of("OPEN", "PARTIALLY_CLOSED", "SETTLEMENT_PENDING_ORACLE")));
        // Populate positions with market info to enable delegate getters
        marketInfoService.populatePositions(openPositions);

        for (SmartWalletPositionEntity position : openPositions) {
            ExitDecision decision = buildExitDecision(position, threshold, mode);
            if (!decision.triggered()) {
                continue;
            }
            try {
                SmartWalletPositionExitRequest exitRequest = new SmartWalletPositionExitRequest();
                exitRequest.setPrice(decision.markPrice());
                exitRequest.setQuantity(position.getQuantity());
                exitRequest.setReason(mode.reason());
                SmartWalletPositionExitResultView exitResult = accountingService.exitPosition(position.getId(), exitRequest);

                log.info("{} triggered positionId={} marketId={} outcome={} entry={} mark={} threshold={}% actual={}% pnl={} currentStatus={}",
                        mode.logLabel(),
                        position.getId(),
                        position.getMarketId(),
                        position.getOutcome(),
                        position.getAverageEntryPrice(),
                        decision.markPrice(),
                        threshold.multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP),
                        decision.rate().multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP),
                        safe(exitResult.getRealizedPnlDeltaUsdc()).setScale(2, RoundingMode.HALF_UP),
                        exitResult.getCurrentStatus());
            } catch (Exception ex) {
                log.warn("{} exit failed for positionId={} marketId={}: {}",
                        mode.logLabel(),
                        position.getId(),
                        position.getMarketId(),
                        ex.getMessage(),
                        ex);
            }
        }
    }

    private void scanPaperPositionsForExitWithDynamicThreshold(AutoExitMode mode) {
        List<SmartWalletPositionEntity> openPositions = positionMapper.selectList(
                new LambdaQueryWrapper<SmartWalletPositionEntity>()
                        .eq(SmartWalletPositionEntity::getExecutionAddress, "paper-book")
                        .in(SmartWalletPositionEntity::getStatus, List.of("OPEN", "PARTIALLY_CLOSED", "SETTLEMENT_PENDING_ORACLE")));
        marketInfoService.populatePositions(openPositions);

        for (SmartWalletPositionEntity position : openPositions) {
            // Record current mark price for trend analysis
            timeAwareStopLossService.recordMarkPrice(
                    position.getId(),
                    position.getLastMarkPrice(),
                    position.getAverageEntryPrice()
            );

            // Resolve dynamic threshold (falls back to fixed threshold when feature is disabled)
            BigDecimal threshold = timeAwareStopLossService.resolveStopLossThreshold(position);

            // Check threshold-based stop-loss
            ExitDecision decision = buildExitDecision(position, threshold, mode);

            // Check trend-based stop-loss (additional trigger)
            boolean trendTriggered = mode == AutoExitMode.STOP_LOSS
                    && timeAwareStopLossService.isTrendStopLossTriggered(
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
            BigDecimal exitPrice = decision.triggered() ? decision.markPrice() : safe(position.getLastMarkPrice());

            try {
                SmartWalletPositionExitRequest exitRequest = new SmartWalletPositionExitRequest();
                exitRequest.setPrice(exitPrice);
                exitRequest.setQuantity(position.getQuantity());
                exitRequest.setReason(exitReason);
                SmartWalletPositionExitResultView exitResult = accountingService.exitPosition(position.getId(), exitRequest);
                timeAwareStopLossService.clearPositionHistory(position.getId());

                log.info("{} triggered positionId={} marketId={} outcome={} mark={} threshold={}% actual={}% pnl={} trendTriggered={} currentStatus={}",
                        mode.logLabel(),
                        position.getId(),
                        position.getMarketId(),
                        position.getOutcome(),
                        exitPrice,
                        threshold.multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP),
                        decision.rate().multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP),
                        safe(exitResult.getRealizedPnlDeltaUsdc()).setScale(2, RoundingMode.HALF_UP),
                        trendTriggered,
                        exitResult.getCurrentStatus());
            } catch (Exception ex) {
                log.warn("{} exit failed for positionId={} marketId={}: {}",
                        mode.logLabel(),
                        position.getId(),
                        position.getMarketId(),
                        ex.getMessage(),
                        ex);
            }
        }
    }

    private void scanLivePositionsForTakeProfit(BigDecimal threshold) {
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
                        .in(SmartWalletPositionEntity::getStatus, List.of("OPEN", "PARTIALLY_CLOSED", "SETTLEMENT_PENDING_ORACLE")));
        // Populate positions with market info to enable delegate getters
        marketInfoService.populatePositions(openPositions);
        for (SmartWalletPositionEntity position : openPositions) {
            if (hasPendingLiveExitOrder(position.getId())) {
                continue;
            }
            ExitDecision decision = buildExitDecision(position, threshold, AutoExitMode.TAKE_PROFIT);
            if (!decision.triggered()) {
                continue;
            }
            try {
                SmartWalletExecutionResult result = clobExecutionService.executeLiveExit(
                        position,
                        decision.markPrice(),
                        AutoExitMode.TAKE_PROFIT.reason()
                );
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
            } catch (Exception ex) {
                log.warn("LIVE take-profit failed for positionId={} marketId={}: {}",
                        position.getId(),
                        position.getMarketId(),
                        ex.getMessage(),
                        ex);
            }
        }
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

    private enum AutoExitMode {
        STOP_LOSS("STOP-LOSS", "STOP_LOSS_AUTO_EXIT"),
        TAKE_PROFIT("TAKE-PROFIT", "TAKE_PROFIT_AUTO_EXIT");

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
}
