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
import com.metaverse.coin.strategy.job.smartwallet.service.MarketConcentrationService.ConcentrationSnapshot;
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
    private final MarketConcentrationService concentrationService;

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

        // Warn early when this wallet has a suspiciously perfect win rate (leaderboard survivorship bias).
        // Single-wallet override will be blocked for these wallets; consensus path still allowed but flagged.
        if (risk.isBlockUnreliableLeaderboardWinRate() && profile != null) {
            BigDecimal winRate = safe(profile.getWinRate());
            int totalTrades = profile.getTotalTrades() == null ? 0 : profile.getTotalTrades();
            if (winRate.compareTo(safe(risk.getUnreliableWinRateThreshold())) >= 0
                    && totalTrades <= (risk.getUnreliableMaxSampleSize() == null ? 50 : risk.getUnreliableMaxSampleSize())) {
                reasons.add("[UNRELIABLE_WIN_RATE] Wallet winRate=" + winRate + " with only " + totalTrades
                        + " trades — likely leaderboard survivorship bias. Single-wallet override blocked.");
            }
        }
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

        // 告警：检测到同一实体多钱包同时触发（记录但不拒绝）
        if (consensus.isHasSuspiciousMultiWallet()) {
            reasons.add("[MULTI_WALLET_INFLATION] Suspicious multi-wallet entity detected in consensus signal. "
                    + "Raw wallets=" + consensus.getDistinctWallets()
                    + ", Deduped entities=" + consensus.getDedupedEntityCount()
                    + ". Using entity count for gate evaluation.");
        }

        boolean consensusEntityCountFailed = false;
        boolean consensusScoreFailed = false;
        if (risk.isEnableConsensusGate()) {
            // 核心修复：使用实体去重后的 dedupedEntityCount，而非原始地址数 distinctWallets
            // 防止一个实体用多钱包伪造共识强度
            int effectiveEntityCount = consensus.getDedupedEntityCount();
            int minEntities = risk.getMinDistinctEntities() != null
                    ? risk.getMinDistinctEntities()
                    : risk.getMinDistinctWallets(); // fallback 兼容旧配置
            if (effectiveEntityCount < minEntities) {
                consensusEntityCountFailed = true;
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

        // ── 集中度门控（防控盘）──────────────────────────────────────────────────
        // 在共识门控之后、EV估算之前运行，避免对最终被拒绝的信号做不必要的 API 调用
        if (risk.isEnableConcentrationGate()) {
            ConcentrationSnapshot snap = concentrationService.analyze(event.getMarketId(), event.getOutcome());
            if (snap.isDataAvailable()) {
                // Gate 1：市场集中度过高 → 直接拒绝
                if (snap.isHighlyConcentrated()) {
                    accepted = false;
                    reasons.add("[CONCENTRATION] Market is highly concentrated. " +
                            "CR1=" + snap.top1Pct().setScale(3, RoundingMode.HALF_UP) +
                            " CR3=" + snap.top3Pct().setScale(3, RoundingMode.HALF_UP) +
                            " HHI=" + snap.hhi() +
                            " holders=" + snap.observedHolders());
                } else {
                    // Gate 2（告警区间）：集中度达到警戒但未触阈值 → 按比例削减 recommendedStake
                    BigDecimal safeTop3 = safe(risk.getConcentrationStakeSafeTop3Pct());
                    BigDecimal blockTop3 = safe(risk.getMaxTop3ConcentrationPct());
                    if (snap.top3Pct().compareTo(safeTop3) > 0
                            && blockTop3.compareTo(safeTop3) > 0) {
                        BigDecimal penaltyRate = snap.top3Pct().subtract(safeTop3)
                                .divide(blockTop3.subtract(safeTop3), 4, RoundingMode.HALF_UP)
                                .multiply(safe(risk.getConcentrationStakePenaltyMaxRate()))
                                .min(safe(risk.getConcentrationStakePenaltyMaxRate()));
                        BigDecimal retainRate = BigDecimal.ONE.subtract(penaltyRate);
                        recommendedStake = recommendedStake.multiply(retainRate).setScale(2, RoundingMode.HALF_UP);
                        reasons.add("[CONCENTRATION_PENALTY] CR3=" +
                                snap.top3Pct().setScale(3, RoundingMode.HALF_UP) +
                                " in caution zone. Stake reduced by " +
                                penaltyRate.multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP) + "%.");
                    }
                }

                // Gate 3：信号源钱包本身是鲸鱼
                String triggerWallet = event.getWalletAddress();
                if (org.springframework.util.StringUtils.hasText(triggerWallet)
                        && snap.topHolderAddresses().stream()
                                .anyMatch(addr -> addr.equalsIgnoreCase(triggerWallet))) {
                    String mode = risk.getWhaleSignalSourceMode() == null ? "BLOCK" : risk.getWhaleSignalSourceMode().toUpperCase();
                    switch (mode) {
                        case "BLOCK" -> {
                            accepted = false;
                            reasons.add("[WHALE_SIGNAL] Signal source wallet is a top-5 holder in this market. " +
                                    "Possible pump-and-dump. Blocked.");
                        }
                        case "DOWNSIZE" -> {
                            BigDecimal downsize = safe(risk.getWhaleSignalDownsizeRate());
                            recommendedStake = recommendedStake.multiply(downsize).setScale(2, RoundingMode.HALF_UP);
                            reasons.add("[WHALE_SIGNAL] Signal source is a top-5 holder. " +
                                    "Stake downsized to " +
                                    downsize.multiply(new BigDecimal("100")).setScale(0, RoundingMode.HALF_UP) + "%.");
                        }
                        default ->  // WARN_ONLY
                            reasons.add("[WHALE_SIGNAL] Signal source is a top-5 holder. " +
                                    "WARN_ONLY mode — signal not blocked.");
                    }
                }
            }
            // API 不可用时 fail-open：跳过集中度检查，不阻断信号
        }

        TradeEvScoringService.TradeEvEstimate evEstimate = tradeEvScoringService.estimate(profile, event, consensus, recommendedStake);
        if (evEstimate.getSampleSize() != null && evEstimate.getSampleSize() > 0) {
            reasons.add("Local EV estimate sample size=" + evEstimate.getSampleSize() + ".");
        }
        reasons.add("Model follow probability=" + safe(evEstimate.getCalibratedFollowProbability()).setScale(4, RoundingMode.HALF_UP) + ".");
        reasons.add("Model slippage spike probability=" + safe(evEstimate.getSlippageSpikeProbability()).setScale(4, RoundingMode.HALF_UP) + ".");
        if (risk.isEnableEvGate()
                && defaultInt(risk.getEvMinSampleSize(), 0) > 0
                && defaultInt(evEstimate.getSampleSize(), 0) >= defaultInt(risk.getEvMinSampleSize(), 0)
                && safe(evEstimate.getExpectedNetEvUsdc()).compareTo(safe(risk.getMinExpectedNetEvUsdc())) < 0) {
            accepted = false;
            reasons.add("Local historical net EV estimate is below threshold.");
        }
        if (risk.isEnableProbabilityGate()
                && safe(evEstimate.getCalibratedFollowProbability()).compareTo(safe(risk.getMinCalibratedFollowProbability())) < 0) {
            accepted = false;
            reasons.add("Calibrated follow probability is below threshold.");
        }
        if (risk.isEnableSlippageGate()
                && safe(evEstimate.getEstimatedSlippageUsdc()).compareTo(safe(risk.getMaxExpectedSlippageUsdc())) > 0) {
            accepted = false;
            reasons.add("Predicted slippage cost is above threshold.");
        }
        if (risk.isEnableSlippageGate()
                && safe(evEstimate.getSlippageSpikeProbability()).compareTo(safe(risk.getMaxSlippageSpikeProbability())) > 0) {
            accepted = false;
            reasons.add("Slippage spike probability is above threshold.");
        }

        boolean singleWalletOverride = qualifiesSingleWalletOverride(profile, risk, triggerSize, recommendedStake);
        if (singleWalletOverride && consensusEntityCountFailed) {
            overrideApplied = true;
            consensusEntityCountFailed = false;
            reasons.add("Single-wallet override bypassed consensus entity-count gate (deduped entities="
                    + consensus.getDedupedEntityCount() + ").");
        }

        if (consensusEntityCountFailed) {
            accepted = false;
            int effectiveWindow = risk.getConsensusWindowSeconds() == null ? 180 : risk.getConsensusWindowSeconds();
            reasons.add("Consensus wallet count (deduped=" + consensus.getDedupedEntityCount()
                    + ", raw wallets=" + consensus.getDistinctWallets()
                    + ", window=" + effectiveWindow + "s) is below threshold="
                    + (risk.getMinDistinctEntities() != null ? risk.getMinDistinctEntities() : risk.getMinDistinctWallets()) + ".");
        }
        if (consensusScoreFailed) {
            accepted = false;
            reasons.add("Consensus score (" + safe(consensus.getConsensusScore()).setScale(3, RoundingMode.HALF_UP)
                    + ") is below threshold (" + safe(risk.getMinConsensusScore()).setScale(3, RoundingMode.HALF_UP) + ").");
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
                .followProbability(evEstimate.getFollowProbability())
                .calibratedFollowProbability(evEstimate.getCalibratedFollowProbability())
                .slippageSpikeProbability(evEstimate.getSlippageSpikeProbability())
                .evSampleSize(evEstimate.getSampleSize())
                .consensusWallets(consensus.getDistinctWallets())        // 原始钱包数（历史兼容）
                .consensusEntities(consensus.getDedupedEntityCount())    // 实体去重后数量（真实强度）
                .consensusScore(consensus.getConsensusScore())
                .hasSuspiciousMultiWallet(consensus.isHasSuspiciousMultiWallet())
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
        // Block single-wallet override for wallets with suspiciously perfect win rates:
        // winRate >= unreliableWinRateThreshold (default 1.0) AND totalTrades <= unreliableMaxSampleSize (default 50)
        // This catches leaderboard survivorship bias where only top-50 positions are shown, inflating win rate to 1.0.
        if (risk.isBlockUnreliableLeaderboardWinRate()) {
            BigDecimal winRate = safe(profile.getWinRate());
            int totalTrades = profile.getTotalTrades() == null ? 0 : profile.getTotalTrades();
            boolean suspiciouslyPerfect = winRate.compareTo(safe(risk.getUnreliableWinRateThreshold())) >= 0
                    && totalTrades <= (risk.getUnreliableMaxSampleSize() == null ? 50 : risk.getUnreliableMaxSampleSize());
            if (suspiciouslyPerfect) {
                return false;
            }
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
