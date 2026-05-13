package com.metaverse.coin.strategy.job.smartwallet.service;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletMarketInfoService;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketSnapshotEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletSignal;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class PolymarketOrderRequestFactory {

    private static final String LOG_PREFIX = "[POLY_FOLLOW]";

    private final PolymarketMarketMetadataService marketMetadataService;
    private final SmartWalletMarketInfoService marketInfoService;
    private final MarketSnapshotCollectorService marketSnapshotCollectorService;

    public PreparedPolymarketOrder prepare(SmartWalletSignal signal,
                                           ExecutionWalletContextLoader.ExecutionWalletContext wallet) {
        String outcome = signal.getFollowOutcome() == null ? "YES" : signal.getFollowOutcome().trim().toUpperCase();
        String conditionId = signal.getMarket() == null ? null : signal.getMarket().getMarketId();
        log.info("{} action=准备Polymarket跟单买入订单 result=开始 signalId={} marketId={} outcome={} requestedStakeUsdc={} requestedPrice={}",
                LOG_PREFIX, signal.getSignalId(), conditionId, outcome, signal.getRecommendedStakeUsdc(),
                signal.getMarket() == null ? null : signal.getMarket().getPrice());
        if ("YES".equals(outcome) && !wallet.isAllowYes()) {
            throw new IllegalStateException("Execution wallet is configured to block YES orders.");
        }
        if ("NO".equals(outcome) && !wallet.isAllowNo()) {
            throw new IllegalStateException("Execution wallet is configured to block NO orders.");
        }

        PolymarketApiClient.MarketMetadata metadata = marketMetadataService.getByConditionId(conditionId);
        String tokenId = resolveTokenId(metadata, outcome);
        BigDecimal minimumTickSize = metadata.getMinimumTickSize() == null || metadata.getMinimumTickSize().compareTo(BigDecimal.ZERO) <= 0
                ? new BigDecimal("0.01")
                : metadata.getMinimumTickSize();
        PreparedPolymarketOrder prepared = prepareLocalDraft(
                signal.getSignalId(),
                conditionId,
                metadata.getQuestion() == null && signal.getMarket() != null ? signal.getMarket().getQuestion() : metadata.getQuestion(),
                outcome,
                signal.getRecommendedStakeUsdc(),
                signal.getMarket() == null ? null : signal.getMarket().getPrice(),
                tokenId,
                minimumTickSize,
                metadata.isNegRisk(),
                wallet
        );
        log.info("{} action=准备Polymarket跟单买入订单 result=完成 signalId={} marketId={} outcome={} tokenId={} limitPrice={} size={} stakeUsdc={} tickSize={} negRisk={}",
                LOG_PREFIX, signal.getSignalId(), prepared.getMarketId(), prepared.getOutcome(), prepared.getTokenId(),
                prepared.getLimitPrice(), prepared.getSize(), prepared.getStakeUsdc(), prepared.getTickSize(),
                prepared.isNegRisk());
        return prepared;
    }

    public PreparedPolymarketOrder prepareExit(SmartWalletPositionEntity position,
                                               BigDecimal requestedPrice,
                                               ExecutionWalletContextLoader.ExecutionWalletContext wallet) {
        if (position == null) {
            throw new IllegalArgumentException("position must be provided for exit order preparation.");
        }
        log.info("{} action=准备Polymarket退出订单 result=开始 positionId={} marketId={} outcome={} requestedPrice={}",
                LOG_PREFIX, position.getId(), position.getMarketId(), position.getOutcome(), requestedPrice);
        // Populate position with market info to enable delegate getters
        marketInfoService.populate(position);
        if (!StringUtils.hasText(position.getMarketId())) {
            throw new IllegalStateException("Position is missing marketId.");
        }
        if (!StringUtils.hasText(position.getOutcome())) {
            throw new IllegalStateException("Position is missing outcome.");
        }
        BigDecimal quantity = position.getQuantity();
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Position has no remaining quantity to exit.");
        }

        String outcome = position.getOutcome().trim().toUpperCase(Locale.ROOT);
        PolymarketApiClient.MarketMetadata metadata = marketMetadataService.getBestEffortByConditionId(
                position.getMarketId(),
                false,
                position.getQuestion(),
                List.of(outcome)
        );
        String tokenId = StringUtils.hasText(position.getTokenId())
                ? position.getTokenId().trim()
                : resolveTokenId(metadata, outcome);
        BigDecimal minimumTickSize = metadata.getMinimumTickSize() == null || metadata.getMinimumTickSize().compareTo(BigDecimal.ZERO) <= 0
                ? new BigDecimal("0.01")
                : metadata.getMinimumTickSize();
        BigDecimal referencePrice = requestedPrice == null ? position.getLastMarkPrice() : requestedPrice;
        if (referencePrice == null || referencePrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Exit price must be provided, or the position must have a usable lastMarkPrice.");
        }
        BigDecimal slippage = referencePrice.multiply(wallet.getPriceSlippageRate());
        BigDecimal limitPrice = referencePrice.subtract(slippage).max(minimumTickSize).setScale(4, RoundingMode.HALF_UP);
        BigDecimal normalizedLimitPrice = snapToTick(limitPrice, minimumTickSize);
        BigDecimal normalizedSize = quantity.setScale(6, RoundingMode.DOWN);
        BigDecimal exitNotional = normalizedSize.multiply(normalizedLimitPrice).setScale(8, RoundingMode.HALF_UP);

        PreparedPolymarketOrder prepared = PreparedPolymarketOrder.builder()
                .signalId("position-exit-" + position.getId())
                .marketId(position.getMarketId())
                .question(position.getQuestion())
                .outcome(outcome)
                .side("SELL")
                .stakeUsdc(exitNotional)
                .limitPrice(normalizedLimitPrice)
                .size(normalizedSize)
                .tokenId(tokenId)
                .tickSize(minimumTickSize)
                .negRisk(metadata != null && metadata.isNegRisk())
                .executionAddress(wallet.getAddress())
                .funderAddress(wallet.getFunderAddress())
                .build();
        log.info("{} action=准备Polymarket退出订单 result=完成 positionId={} marketId={} outcome={} tokenId={} limitPrice={} size={} notionalUsdc={} tickSize={} negRisk={}",
                LOG_PREFIX, position.getId(), prepared.getMarketId(), prepared.getOutcome(), prepared.getTokenId(),
                prepared.getLimitPrice(), prepared.getSize(), prepared.getStakeUsdc(), prepared.getTickSize(),
                prepared.isNegRisk());
        return prepared;
    }

    public PreparedPolymarketOrder prepareLocalDraft(String signalId,
                                                     String conditionId,
                                                     String question,
                                                     String outcome,
                                                     BigDecimal requestedStakeUsdc,
                                                     BigDecimal requestedPrice,
                                                     String tokenId,
                                                     BigDecimal tickSize,
                                                    boolean negRisk,
                                                    ExecutionWalletContextLoader.ExecutionWalletContext wallet) {
        log.info("{} action=构造Polymarket本地下单草稿 result=开始 signalId={} marketId={} outcome={} requestedStakeUsdc={} requestedPrice={} tokenId={} tickSize={}",
                LOG_PREFIX, signalId, conditionId, outcome, requestedStakeUsdc, requestedPrice, tokenId, tickSize);
        if ("YES".equalsIgnoreCase(outcome) && !wallet.isAllowYes()) {
            throw new IllegalStateException("Execution wallet is configured to block YES orders.");
        }
        if ("NO".equalsIgnoreCase(outcome) && !wallet.isAllowNo()) {
            throw new IllegalStateException("Execution wallet is configured to block NO orders.");
        }
        BigDecimal recommendedStake = requestedStakeUsdc == null ? wallet.getMinOrderUsdc() : requestedStakeUsdc;
        BigDecimal cappedStake = recommendedStake
                .max(wallet.getMinOrderUsdc())
                .min(wallet.getMaxOrderUsdc())
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal rawPrice = requestedPrice == null ? BigDecimal.ZERO : requestedPrice;
        BigDecimal minimumTickSize = tickSize == null || tickSize.compareTo(BigDecimal.ZERO) <= 0
                ? new BigDecimal("0.01")
                : tickSize;

        // ── Fix-B3: 决定订单策略（TAKER / MAKER / ADAPTIVE） ──────────
        String effectiveStrategy = resolveOrderStrategy(wallet, conditionId, outcome);

        BigDecimal limitPrice;
        if ("MAKER".equals(effectiveStrategy)) {
            // Maker 模式：limitPrice = rawPrice + 1 tick（稍高于当前 bestBid / 信号价）
            // 不加 slippage，挂单在 best bid 附近等撮合，fee = 0
            limitPrice = rawPrice.add(minimumTickSize)
                    .min(BigDecimal.ONE)
                    .setScale(4, RoundingMode.HALF_UP);
            log.info("{} action=OrderStrategy signalId={} strategy=MAKER limitPrice={} (rawPrice + 1tick)",
                    LOG_PREFIX, signalId, limitPrice);
        } else {
            // Taker 模式（默认）：limitPrice = rawPrice + slippage
            BigDecimal effectiveSlippageRate = resolveSlippageRate(wallet, conditionId, outcome);
            BigDecimal slippage = rawPrice.multiply(effectiveSlippageRate);
            limitPrice = rawPrice.add(slippage).min(BigDecimal.ONE).setScale(4, RoundingMode.HALF_UP);
            log.info("{} action=OrderStrategy signalId={} strategy=TAKER slippageRate={} limitPrice={}",
                    LOG_PREFIX, signalId, effectiveSlippageRate, limitPrice);
        }

        BigDecimal normalizedLimitPrice = snapToTick(limitPrice, minimumTickSize);
        BigDecimal size = normalizedLimitPrice.compareTo(BigDecimal.ZERO) <= 0
                ? BigDecimal.ZERO
                : cappedStake.divide(normalizedLimitPrice, 6, RoundingMode.DOWN);

        PreparedPolymarketOrder prepared = PreparedPolymarketOrder.builder()
                .signalId(signalId)
                .marketId(conditionId)
                .question(question)
                .outcome(outcome)
                .side("BUY")
                .stakeUsdc(cappedStake)
                .limitPrice(normalizedLimitPrice)
                .size(size)
                .tokenId(StringUtils.hasText(tokenId) ? tokenId.trim() : null)
                .tickSize(minimumTickSize)
                .negRisk(negRisk)
                .executionAddress(wallet.getAddress())
                .funderAddress(wallet.getFunderAddress())
                .orderStrategy(effectiveStrategy)
                .build();
        log.info("{} action=构造Polymarket本地下单草稿 result=完成 signalId={} marketId={} outcome={} side={} strategy={} requestedStakeUsdc={} cappedStakeUsdc={} rawPrice={} limitPrice={} size={} tokenId={}",
                LOG_PREFIX, signalId, conditionId, outcome, prepared.getSide(), effectiveStrategy,
                requestedStakeUsdc, cappedStake, rawPrice, prepared.getLimitPrice(), prepared.getSize(),
                prepared.getTokenId());
        return prepared;
    }

    // ── Fix-C: 动态滑点计算 ──────────────────────────────────────────────

    /**
     * 根据 orderbook 深度动态计算滑点率。
     * <p>规则：
     * <ul>
     *   <li>askDepth3c >= highLiquidityThreshold → 使用紧滑点（默认 1%）</li>
     *   <li>askDepth3c >= lowLiquidityThreshold → 使用中等滑点（默认 2%）</li>
     *   <li>askDepth3c < lowLiquidityThreshold → 使用宽滑点（默认 3%）</li>
     * </ul>
     * 未启用动态滑点或快照不可用时，回退到固定 priceSlippageRate。
     */
    private BigDecimal resolveSlippageRate(ExecutionWalletContextLoader.ExecutionWalletContext wallet,
                                           String conditionId, String outcome) {
        if (!wallet.isEnableDynamicSlippage()) {
            return wallet.getPriceSlippageRate();
        }
        BigDecimal depth = fetchAskDepth3c(conditionId, outcome);
        if (depth == null) {
            // 快照不可用，fall back to 固定值
            return wallet.getPriceSlippageRate();
        }
        if (depth.compareTo(wallet.getHighLiquidityThresholdUsdc()) >= 0) {
            return wallet.getHighLiquiditySlippageRate();
        }
        if (depth.compareTo(wallet.getLowLiquidityThresholdUsdc()) >= 0) {
            return wallet.getMediumLiquiditySlippageRate();
        }
        return wallet.getLowLiquiditySlippageRate();
    }

    // ── Fix-B3: 订单策略路由 ─────────────────────────────────────────────

    /**
     * 根据配置和市场流动性决定使用 TAKER 还是 MAKER 策略。
     * <p>ADAPTIVE 模式下同时检查 askDepth3c 和 spreadBps：
     * 只有深度充足且价差窄的市场才使用 MAKER（挂单不立即成交的风险较小）。</p>
     */
    private String resolveOrderStrategy(ExecutionWalletContextLoader.ExecutionWalletContext wallet,
                                         String conditionId, String outcome) {
        String strategy = wallet.getOrderStrategy();
        if (!"ADAPTIVE".equals(strategy)) {
            // 固定 TAKER 或固定 MAKER
            return "MAKER".equals(strategy) ? "MAKER" : "TAKER";
        }
        // ADAPTIVE 模式：根据市场流动性决定
        try {
            SmartWalletMarketSnapshotEntity snapshot = marketSnapshotCollectorService
                    .findLatestSnapshotAtOrBefore(conditionId, outcome, new Date());
            if (snapshot == null || snapshot.getSnapshotAt() == null
                    || System.currentTimeMillis() - snapshot.getSnapshotAt().getTime() > 300_000L) {
                // 无有效快照，保守走 taker
                return "TAKER";
            }
            BigDecimal askDepth = snapshot.getAskDepth3c();
            BigDecimal spreadBps = snapshot.getSpreadBps();
            boolean depthOk = askDepth != null
                    && askDepth.compareTo(wallet.getMakerStrategyMinDepthUsdc()) >= 0;
            boolean spreadOk = spreadBps != null
                    && spreadBps.compareTo(wallet.getMakerStrategyMaxSpreadBps()) <= 0;
            if (depthOk && spreadOk) {
                log.debug("{} ADAPTIVE → MAKER: askDepth3c={}, spreadBps={}", LOG_PREFIX, askDepth, spreadBps);
                return "MAKER";
            }
            log.debug("{} ADAPTIVE → TAKER: askDepth3c={}, spreadBps={} (thresholds: depth>={}, spread<={})",
                    LOG_PREFIX, askDepth, spreadBps,
                    wallet.getMakerStrategyMinDepthUsdc(), wallet.getMakerStrategyMaxSpreadBps());
            return "TAKER";
        } catch (Exception e) {
            log.debug("{} ADAPTIVE → TAKER (snapshot lookup failed): {}", LOG_PREFIX, e.getMessage());
            return "TAKER";
        }
    }

    /**
     * 从最近 5 分钟的 snapshot 获取 askDepth3c。
     */
    private BigDecimal fetchAskDepth3c(String conditionId, String outcome) {
        try {
            SmartWalletMarketSnapshotEntity snapshot = marketSnapshotCollectorService
                    .findLatestSnapshotAtOrBefore(conditionId, outcome, new Date());
            if (snapshot == null || snapshot.getSnapshotAt() == null
                    || System.currentTimeMillis() - snapshot.getSnapshotAt().getTime() > 300_000L) {
                return null;
            }
            return snapshot.getAskDepth3c();
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveTokenId(PolymarketApiClient.MarketMetadata metadata, String outcome) {
        if (metadata == null || metadata.getOutcomeTokenIds() == null || metadata.getOutcomeTokenIds().isEmpty()) {
            throw new IllegalStateException("Polymarket market metadata is missing outcome token ids.");
        }
        String tokenId = findTokenId(metadata.getOutcomeTokenIds(), outcome);
        if (!StringUtils.hasText(tokenId)) {
            String supportedOutcomes = metadata.getOutcomeTokenIds().keySet().stream()
                    .filter(StringUtils::hasText)
                    .collect(Collectors.joining(", "));
            throw new IllegalStateException("Polymarket token id not found for outcome " + outcome
                    + " on market " + metadata.getConditionId()
                    + ". Supported outcomes: [" + supportedOutcomes + "].");
        }
        return tokenId.trim();
    }

    private String findTokenId(Map<String, String> outcomeTokenIds, String outcome) {
        if (!StringUtils.hasText(outcome) || outcomeTokenIds == null || outcomeTokenIds.isEmpty()) {
            return null;
        }
        String exact = outcomeTokenIds.get(outcome);
        if (StringUtils.hasText(exact)) {
            return exact;
        }
        String normalized = outcome.trim().toUpperCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : outcomeTokenIds.entrySet()) {
            if (entry.getKey() != null && normalized.equals(entry.getKey().trim().toUpperCase(Locale.ROOT))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private BigDecimal snapToTick(BigDecimal price, BigDecimal tickSize) {
        if (price == null || tickSize == null || tickSize.compareTo(BigDecimal.ZERO) <= 0) {
            return price;
        }
        return price.divide(tickSize, 0, RoundingMode.HALF_UP)
                .multiply(tickSize)
                .min(BigDecimal.ONE)
                .max(BigDecimal.ZERO)
                .setScale(Math.max(tickSize.scale(), 4), RoundingMode.HALF_UP);
    }

    @Data
    @Builder
    public static class PreparedPolymarketOrder {
        private String signalId;
        private String marketId;
        private String question;
        private String outcome;
        private String side;
        private BigDecimal stakeUsdc;
        private BigDecimal limitPrice;
        private BigDecimal size;
        private String tokenId;
        private BigDecimal tickSize;
        private boolean negRisk;
        private String executionAddress;
        private String funderAddress;
        /** 实际使用的订单策略：TAKER / MAKER */
        private String orderStrategy;
    }
}
