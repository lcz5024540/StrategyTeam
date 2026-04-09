package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.ConsensusSignalView;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalRiskDecision;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SmartWalletSignalRiskService {

    private final SmartWalletProperties properties;
    private final ExecutionWalletContextLoader executionWalletContextLoader;
    private final PolymarketConsensusSignalService consensusSignalService;
    private final SmartWalletPositionMapper positionMapper;
    private final SmartWalletFollowTaskMapper followTaskMapper;
    private final PolymarketMarketMetadataService marketMetadataService;
    private final SmartWalletAccountingService accountingService;
    private final TradeEvScoringService tradeEvScoringService;

    public SignalRiskDecision evaluate(SmartWalletProfileEntity profile, SmartWalletTradeEventEntity event) {
        return evaluate(profile, event, null, null);
    }

    public SignalRiskDecision evaluate(SmartWalletProfileEntity profile,
                                       SmartWalletTradeEventEntity event,
                                       SmartWalletProperties.SignalRisk overrideRisk) {
        return evaluate(profile, event, overrideRisk, null);
    }

    public SignalRiskDecision evaluate(SmartWalletProfileEntity profile,
                                       SmartWalletTradeEventEntity event,
                                       SmartWalletProperties.SignalRisk overrideRisk,
                                       Long currentFollowTaskId) {
        List<String> reasons = new ArrayList<>();
        boolean accepted = true;
        boolean overrideApplied = false;

        if (profile == null || !Boolean.TRUE.equals(profile.getQualified()) || !Boolean.TRUE.equals(profile.getWatchEnabled())) {
            accepted = false;
            reasons.add("Wallet is not qualified in watchlist.");
        }

        SmartWalletProperties.SignalRisk risk = overrideRisk == null ? properties.getSignalRisk() : overrideRisk;
        if (!risk.isAllowSellSignal() && "SELL".equalsIgnoreCase(event.getSide())) {
            accepted = false;
            reasons.add("SELL signals are disabled by risk policy.");
        }

        BigDecimal triggerSize = safe(event.getSizeUsdc());
        if (triggerSize.compareTo(safe(risk.getMinTriggerSizeUsdc())) < 0) {
            accepted = false;
            reasons.add("Trigger size is below minimum threshold.");
        }

        BigDecimal price = safe(event.getPrice());
        if (price.compareTo(safe(risk.getMinFollowPrice())) < 0 || price.compareTo(safe(risk.getMaxFollowPrice())) > 0) {
            accepted = false;
            reasons.add("Trigger price is outside follow range.");
        }

        Integer openPositions = countOpenPositions();
        if (openPositions >= defaultInt(risk.getMaxOpenPositions(), Integer.MAX_VALUE)) {
            accepted = false;
            reasons.add("Open position count has reached the portfolio limit.");
        }

        BigDecimal totalOpenCost = totalOpenCostUsdc();
        if (totalOpenCost.compareTo(safe(risk.getMaxTotalOpenCostUsdc())) >= 0) {
            accepted = false;
            reasons.add("Total open position cost has reached the portfolio budget limit.");
        }

        BigDecimal availableCapital = accountingService.availableCapitalUsdc();
        if (availableCapital.compareTo(BigDecimal.ZERO) <= 0) {
            accepted = false;
            reasons.add("Available capital is depleted. Strategy is paused to avoid bankroll penetration.");
        }

        if (event.getOpenedAt() != null) {
            long ageMs = Math.max(0L, System.currentTimeMillis() - event.getOpenedAt().getTime());
            if (ageMs > risk.getMaxSignalAgeSeconds() * 1000L) {
                accepted = false;
                reasons.add("Signal is stale.");
            }
        }

        PolymarketApiClient.MarketMetadata metadata = null;
        try {
            metadata = marketMetadataService.getByConditionId(event.getMarketId());
        } catch (Exception ignored) {
            // Keep risk evaluation resilient when metadata endpoint is temporarily flaky.
        }
        Long endAt = metadata == null ? null : metadata.getEndAt();
        if (endAt != null && endAt > 0) {
            long remainingMs = endAt - System.currentTimeMillis();
            if (remainingMs <= 0) {
                accepted = false;
                reasons.add("Market is already at or past expected end time.");
            } else {
                long maxHours = defaultInt(risk.getMaxHoursToMarketEnd(), Integer.MAX_VALUE);
                if (remainingMs > maxHours * 3600_000L) {
                    accepted = false;
                    reasons.add("Market end time is too far away for the current capital-turnover policy.");
                }
            }
        }

        if (risk.isBlockDuplicateOpenPosition()
                && hasOpenPosition(event.getMarketId(), event.getOutcome())) {
            accepted = false;
            reasons.add("An open position already exists for the same market and outcome.");
        }

        if (risk.isBlockOppositeOpenPositionSameMarket()
                && hasOpenPositionOnSameMarketDifferentOutcome(event.getMarketId(), event.getOutcome())) {
            accepted = false;
            reasons.add("An open position already exists for the same market with a different outcome.");
        }

        if (risk.isBlockDuplicatePendingTask()
                && hasPendingFollowTask(event.getMarketId(), event.getOutcome(), currentFollowTaskId)) {
            accepted = false;
            reasons.add("A pending follow task already exists for the same market and outcome.");
        }

        if (risk.isBlockOppositePendingTaskSameMarket()
                && hasPendingFollowTaskOnSameMarketDifferentOutcome(event.getMarketId(), event.getOutcome(), currentFollowTaskId)) {
            accepted = false;
            reasons.add("A pending follow task already exists for the same market with a different outcome.");
        }

        ConsensusSignalView consensus = consensusSignalService.buildConsensusForEvent(event);
        boolean consensusWalletCountFailed = false;
        boolean consensusScoreFailed = false;
        if (risk.isEnableConsensusGate()) {
            if (consensus.getDistinctWallets() < risk.getMinDistinctWallets()) {
                consensusWalletCountFailed = true;
            }
            if (safe(consensus.getConsensusScore()).compareTo(safe(risk.getMinConsensusScore())) < 0) {
                consensusScoreFailed = true;
            }
        }

        BigDecimal walletScore = profile == null ? BigDecimal.ZERO : safe(profile.getWalletScore());
        BigDecimal recommendedStake = triggerSize
                .multiply(walletScore)
                .multiply(properties.getBankrollUtilizationRate())
                .min(properties.getMaxStakeUsdc())
                .setScale(2, RoundingMode.HALF_UP);
        recommendedStake = recommendedStake.min(availableCapital.max(BigDecimal.ZERO)).setScale(2, RoundingMode.HALF_UP);

        BigDecimal minOrderUsdc = executionWalletContextLoader.load().getMinOrderUsdc();
        if (recommendedStake.compareTo(minOrderUsdc) < 0) {
            accepted = false;
            reasons.add("Recommended stake is below execution wallet minimum order.");
        }

        TradeEvScoringService.TradeEvEstimate evEstimate = tradeEvScoringService.estimate(event, recommendedStake);
        if (evEstimate.getSampleSize() != null && evEstimate.getSampleSize() > 0) {
            reasons.add("Local EV estimate sample size=" + evEstimate.getSampleSize() + ".");
        }
        if (risk.isEnableEvGate()
                && defaultInt(risk.getEvMinSampleSize(), 0) > 0
                && defaultInt(evEstimate.getSampleSize(), 0) >= defaultInt(risk.getEvMinSampleSize(), 0)
                && safe(evEstimate.getExpectedNetEvUsdc()).compareTo(safe(risk.getMinExpectedNetEvUsdc())) < 0) {
            accepted = false;
            reasons.add("Local historical net EV estimate is below threshold.");
        }

        boolean singleWalletOverride = qualifiesSingleWalletOverride(profile, risk, triggerSize, recommendedStake);
        if (singleWalletOverride && consensusWalletCountFailed) {
            overrideApplied = true;
            consensusWalletCountFailed = false;
            reasons.add("Single-wallet override bypassed consensus wallet-count gate.");
        }

        if (consensusWalletCountFailed) {
            accepted = false;
            reasons.add("Consensus wallet count is below threshold.");
        }
        if (consensusScoreFailed) {
            accepted = false;
            reasons.add("Consensus score is below threshold.");
        }

        if (accepted) {
            reasons.add("Signal passed watchlist, freshness, size and price checks.");
        }

        return SignalRiskDecision.builder()
                .accepted(accepted)
                .overrideApplied(overrideApplied)
                .recommendedStakeUsdc(recommendedStake)
                .expectedNetEvUsdc(evEstimate.getExpectedNetEvUsdc())
                .estimatedFeeUsdc(evEstimate.getEstimatedFeeUsdc())
                .estimatedSlippageUsdc(evEstimate.getEstimatedSlippageUsdc())
                .evSampleSize(evEstimate.getSampleSize())
                .consensusWallets(consensus.getDistinctWallets())
                .consensusScore(consensus.getConsensusScore())
                .reasons(reasons)
                .build();
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private boolean qualifiesSingleWalletOverride(SmartWalletProfileEntity profile,
                                                  SmartWalletProperties.SignalRisk risk,
                                                  BigDecimal triggerSize,
                                                  BigDecimal recommendedStake) {
        if (!risk.isEnableSingleWalletOverride() || profile == null) {
            return false;
        }
        return safe(profile.getWalletScore()).compareTo(safe(risk.getSingleWalletOverrideMinWalletScore())) >= 0
                && safe(profile.getWinRate()).compareTo(safe(risk.getSingleWalletOverrideMinWinRate())) >= 0
                && (profile.getTotalTrades() == null ? 0 : profile.getTotalTrades()) >= (risk.getSingleWalletOverrideMinTrades() == null ? 0 : risk.getSingleWalletOverrideMinTrades())
                && triggerSize.compareTo(safe(risk.getSingleWalletOverrideMinTriggerSizeUsdc())) >= 0
                && recommendedStake.compareTo(safe(risk.getSingleWalletOverrideMinRecommendedStakeUsdc())) >= 0;
    }

    private Integer countOpenPositions() {
        Long count = positionMapper.selectCount(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .in(SmartWalletPositionEntity::getStatus, List.of("OPEN", "PARTIALLY_CLOSED")));
        return count == null ? 0 : count.intValue();
    }

    private BigDecimal totalOpenCostUsdc() {
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .in(SmartWalletPositionEntity::getStatus, List.of("OPEN", "PARTIALLY_CLOSED")));
        BigDecimal total = BigDecimal.ZERO;
        for (SmartWalletPositionEntity position : positions) {
            total = total.add(safe(position.getCostBasisUsdc()));
        }
        return total;
    }

    private boolean hasOpenPosition(String marketId, String outcome) {
        if (marketId == null || outcome == null) {
            return false;
        }
        Long count = positionMapper.selectCount(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .eq(SmartWalletPositionEntity::getMarketId, marketId)
                .eq(SmartWalletPositionEntity::getOutcome, outcome)
                .in(SmartWalletPositionEntity::getStatus, List.of("OPEN", "PARTIALLY_CLOSED")));
        return count != null && count > 0;
    }

    private boolean hasPendingFollowTask(String marketId, String outcome, Long currentFollowTaskId) {
        if (marketId == null || outcome == null) {
            return false;
        }
        LambdaQueryWrapper<SmartWalletFollowTaskEntity> query = new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                .eq(SmartWalletFollowTaskEntity::getMarketId, marketId)
                .eq(SmartWalletFollowTaskEntity::getOutcome, outcome)
                .eq(SmartWalletFollowTaskEntity::getStatus, "PENDING");
        if (currentFollowTaskId != null) {
            query.ne(SmartWalletFollowTaskEntity::getId, currentFollowTaskId);
        }
        Long count = followTaskMapper.selectCount(query);
        return count != null && count > 0;
    }

    private boolean hasOpenPositionOnSameMarketDifferentOutcome(String marketId, String outcome) {
        if (marketId == null || outcome == null) {
            return false;
        }
        Long count = positionMapper.selectCount(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .eq(SmartWalletPositionEntity::getMarketId, marketId)
                .ne(SmartWalletPositionEntity::getOutcome, outcome)
                .in(SmartWalletPositionEntity::getStatus, List.of("OPEN", "PARTIALLY_CLOSED")));
        return count != null && count > 0;
    }

    private boolean hasPendingFollowTaskOnSameMarketDifferentOutcome(String marketId, String outcome, Long currentFollowTaskId) {
        if (marketId == null || outcome == null) {
            return false;
        }
        LambdaQueryWrapper<SmartWalletFollowTaskEntity> query = new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                .eq(SmartWalletFollowTaskEntity::getMarketId, marketId)
                .ne(SmartWalletFollowTaskEntity::getOutcome, outcome)
                .eq(SmartWalletFollowTaskEntity::getStatus, "PENDING");
        if (currentFollowTaskId != null) {
            query.ne(SmartWalletFollowTaskEntity::getId, currentFollowTaskId);
        }
        Long count = followTaskMapper.selectCount(query);
        return count != null && count > 0;
    }

    private int defaultInt(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }
}
