package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
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

@Service
@Slf4j
@RequiredArgsConstructor
public class SmartWalletPositionSyncService {

    private final SmartWalletProperties properties;
    private final PolymarketApiClient polymarketApiClient;
    private final ExecutionWalletContextLoader walletContextLoader;
    private final SmartWalletPositionMapper positionMapper;
    private final SmartWalletAccountingService accountingService;
    private volatile Date cooldownUntil;
    private volatile String cooldownReason;

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

            // Further process positions if needed...
        } catch (Exception e) {
            applyCooldown(e);
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
    // P2 Stop-loss: runs every 5 minutes, closes paper positions whose mark
    // price has fallen >= stopLossThresholdRate below average entry price.
    // -------------------------------------------------------------------------
    @Scheduled(fixedDelay = 300_000)
    public void checkStopLoss() {
        SmartWalletProperties.SignalRisk risk = properties.getSignalRisk();
        if (!properties.isEnabled() || risk == null || !risk.isEnableStopLoss()) {
            return;
        }
        BigDecimal threshold = risk.getStopLossThresholdRate() == null
                ? new BigDecimal("0.50") : risk.getStopLossThresholdRate();

        List<SmartWalletPositionEntity> openPositions = positionMapper.selectList(
                new LambdaQueryWrapper<SmartWalletPositionEntity>()
                        .eq(SmartWalletPositionEntity::getExecutionAddress, "paper-book")
                        .in(SmartWalletPositionEntity::getStatus, List.of("OPEN", "PARTIALLY_CLOSED")));

        for (SmartWalletPositionEntity pos : openPositions) {
            BigDecimal entry = pos.getAverageEntryPrice();
            BigDecimal mark  = pos.getLastMarkPrice();
            if (entry == null || entry.compareTo(BigDecimal.ZERO) <= 0 || mark == null) {
                continue;
            }
            // loss rate = (entry - mark) / entry
            BigDecimal lossRate = entry.subtract(mark).divide(entry, 8, RoundingMode.HALF_UP);
            if (lossRate.compareTo(threshold) < 0) {
                continue;
            }
            try {
                SmartWalletPositionExitRequest exitRequest = new SmartWalletPositionExitRequest();
                exitRequest.setPrice(mark);
                exitRequest.setQuantity(pos.getQuantity());
                exitRequest.setReason("STOP_LOSS_AUTO_EXIT");
                SmartWalletPositionExitResultView exitResult = accountingService.exitPosition(pos.getId(), exitRequest);

                log.info("STOP-LOSS triggered positionId={} marketId={} outcome={} entry={} mark={} lossRate={}% pnl={} currentStatus={}",
                        pos.getId(), pos.getMarketId(), pos.getOutcome(),
                        entry, mark,
                        lossRate.multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP),
                        exitResult.getRealizedPnlDeltaUsdc().setScale(2, RoundingMode.HALF_UP),
                        exitResult.getCurrentStatus());
            } catch (Exception ex) {
                log.warn("STOP-LOSS exit failed for positionId={} marketId={}: {}",
                        pos.getId(),
                        pos.getMarketId(),
                        ex.getMessage(),
                        ex);
            }
        }
    }

    private boolean isNotFoundError(Exception e) {
        String message = collectExceptionText(e);
        return message.contains("status=404");
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
}
