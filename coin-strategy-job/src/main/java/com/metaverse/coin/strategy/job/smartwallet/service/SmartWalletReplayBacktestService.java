package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletProfileMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEffectLabelMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEffectLabelEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalRiskDecision;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletReplayBacktestRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletReplayBacktestResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletReplayConcentrationSummaryResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletReplayConcentrationWindowSummary;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletReplayStrategyMetrics;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletReplayWindowResult;
import com.metaverse.coin.strategy.job.smartwallet.model.WalletBehaviorProfileView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SmartWalletReplayBacktestService {

    private static final List<Integer> DEFAULT_WINDOWS = List.of(30, 60, 90);
    private static final String SOURCE = "POLYMARKET";

    private final SmartWalletProperties properties;
    private final SmartWalletTradeEventMapper tradeEventMapper;
    private final SmartWalletTradeEffectLabelMapper tradeEffectLabelMapper;
    private final SmartWalletProfileMapper profileMapper;
    private final SmartWalletScoringService scoringService;
    private final SmartWalletProfileStoreService profileStoreService;
    private final SmartWalletSignalRiskService signalRiskService;
    private final WalletBehaviorProfileService walletBehaviorProfileService;

    public SmartWalletReplayBacktestResult replay(SmartWalletReplayBacktestRequest request) {
        SmartWalletReplayBacktestRequest applied = normalizeRequest(request);
        List<SmartWalletReplayWindowResult> windows = new ArrayList<>();
        for (Integer windowDays : applied.getWindowDays()) {
            windows.add(replayWindow(windowDays, applied.getMaxEventsPerWindow()));
        }
        return SmartWalletReplayBacktestResult.builder()
                .generatedAt(new Date())
                .applied(applied)
                .windows(windows)
                .build();
    }

    public SmartWalletReplayConcentrationSummaryResult replayConcentrationSummary(SmartWalletReplayBacktestRequest request) {
        return summarizeConcentration(replay(request));
    }

    public SmartWalletReplayConcentrationSummaryResult summarizeConcentration(SmartWalletReplayBacktestResult replayResult) {
        SmartWalletReplayBacktestResult safeResult = replayResult == null
                ? SmartWalletReplayBacktestResult.builder().generatedAt(new Date()).build()
                : replayResult;
        List<SmartWalletReplayConcentrationWindowSummary> windows = safeResult.getWindows() == null
                ? List.of()
                : safeResult.getWindows().stream()
                .filter(Objects::nonNull)
                .map(this::toConcentrationSummaryWindow)
                .toList();
        return SmartWalletReplayConcentrationSummaryResult.builder()
                .generatedAt(new Date())
                .sourceReplayGeneratedAt(safeResult.getGeneratedAt())
                .applied(safeResult.getApplied())
                .summaryScope("Filters replay output down to concentration-specific impacts from the candidate policy while preserving baseline-vs-candidate deltas for context.")
                .windows(windows)
                .build();
    }

    private SmartWalletReplayWindowResult replayWindow(Integer windowDays, Integer maxEventsPerWindow) {
        int safeWindowDays = windowDays == null ? 30 : Math.max(windowDays, 1);
        int safeLimit = maxEventsPerWindow == null ? 2000 : Math.max(1, Math.min(maxEventsPerWindow, 10000));
        Date since = Date.from(Instant.now().minus(safeWindowDays, ChronoUnit.DAYS));
        List<SmartWalletTradeEventEntity> events = tradeEventMapper.selectList(new LambdaQueryWrapper<SmartWalletTradeEventEntity>()
                .eq(SmartWalletTradeEventEntity::getSource, SOURCE)
                .ge(SmartWalletTradeEventEntity::getOpenedAt, since)
                .orderByAsc(SmartWalletTradeEventEntity::getOpenedAt)
                .orderByAsc(SmartWalletTradeEventEntity::getId)
                .last("limit " + safeLimit));
        Map<Long, SmartWalletTradeEffectLabelEntity> labelsByEventId = loadLabels(events);
        Map<String, SmartWalletProfileEntity> profilesByWallet = loadProfiles(events);
        Map<String, WalletBehaviorProfileView> behaviorsByWallet = loadBehaviorProfiles(events);

        SmartWalletProperties.SignalRisk baselineRisk = baselineRisk();
        SmartWalletProperties.SignalRisk candidateRisk = candidateRisk();
        StrategyAccumulator baseline = new StrategyAccumulator("baseline", baselineRisk.getPolicyTag(), baselineRisk.getExitSignalProviderMode());
        StrategyAccumulator candidate = new StrategyAccumulator("candidate", candidateRisk.getPolicyTag(), candidateRisk.getExitSignalProviderMode());

        for (SmartWalletTradeEventEntity event : events) {
            if (event == null || event.getWalletAddress() == null || event.getWalletAddress().isBlank()) {
                continue;
            }
            String walletKey = normalizeWallet(event.getWalletAddress());
            SmartWalletProfileEntity profile = profilesByWallet.get(walletKey);
            WalletBehaviorProfileView behaviorProfile = behaviorsByWallet.get(walletKey);
            SmartWalletTradeEffectLabelEntity label = labelsByEventId.get(event.getId());

            evaluateScenario(event, profile, behaviorProfile, label, baselineRisk, false, baseline);
            evaluateScenario(event, profile, behaviorProfile, label, candidateRisk, true, candidate);
        }

        return SmartWalletReplayWindowResult.builder()
                .windowDays(safeWindowDays)
                .scannedEvents(events.size())
                .baseline(baseline.toMetrics())
                .candidate(candidate.toMetrics())
                .build();
    }

    private SmartWalletReplayConcentrationWindowSummary toConcentrationSummaryWindow(SmartWalletReplayWindowResult window) {
        SmartWalletReplayStrategyMetrics baseline = window == null || window.getBaseline() == null
                ? SmartWalletReplayStrategyMetrics.builder().build()
                : window.getBaseline();
        SmartWalletReplayStrategyMetrics candidate = window == null || window.getCandidate() == null
                ? SmartWalletReplayStrategyMetrics.builder().build()
                : window.getCandidate();
        int scannedEvents = safeInt(window == null ? null : window.getScannedEvents());
        int concentrationBlockedCount = safeInt(candidate.getConcentrationBlockedCount());
        int extremeConcentrationBlockedCount = safeInt(candidate.getExtremeConcentrationBlockedCount());
        int totalConcentrationBlockedCount = concentrationBlockedCount + extremeConcentrationBlockedCount;
        BigDecimal totalBlockedProxyPnl = safeDecimal(candidate.getConcentrationBlockedProxyPnlUsdc())
                .add(safeDecimal(candidate.getExtremeConcentrationBlockedProxyPnlUsdc()))
                .setScale(4, RoundingMode.HALF_UP);
        BigDecimal worstBlockedProxyMaxDrawdown = safeDecimal(candidate.getConcentrationBlockedProxyMaxDrawdownUsdc())
                .max(safeDecimal(candidate.getExtremeConcentrationBlockedProxyMaxDrawdownUsdc()))
                .setScale(4, RoundingMode.HALF_UP);
        return SmartWalletReplayConcentrationWindowSummary.builder()
                .windowDays(window == null ? null : window.getWindowDays())
                .scannedEvents(scannedEvents)
                .baselinePolicyTag(baseline.getPolicyTag())
                .candidatePolicyTag(candidate.getPolicyTag())
                .baselineAcceptedEvents(safeInt(baseline.getAcceptedEvents()))
                .candidateAcceptedEvents(safeInt(candidate.getAcceptedEvents()))
                .acceptedDelta(safeInt(candidate.getAcceptedEvents()) - safeInt(baseline.getAcceptedEvents()))
                .baselineAcceptanceRate(scaleRate(baseline.getAcceptanceRate()))
                .candidateAcceptanceRate(scaleRate(candidate.getAcceptanceRate()))
                .acceptanceRateDelta(scaleRate(safeDecimal(candidate.getAcceptanceRate()).subtract(safeDecimal(baseline.getAcceptanceRate()))))
                .concentrationBlockedCount(concentrationBlockedCount)
                .extremeConcentrationBlockedCount(extremeConcentrationBlockedCount)
                .totalConcentrationBlockedCount(totalConcentrationBlockedCount)
                .concentrationBlockedRate(ratio(totalConcentrationBlockedCount, scannedEvents))
                .concentrationDownsizedAcceptedCount(safeInt(candidate.getConcentrationDownsizedAcceptedCount()))
                .concentrationDownsizedAcceptedRate(ratio(candidate.getConcentrationDownsizedAcceptedCount(), scannedEvents))
                .concentrationBlockedProxyPnlUsdc(scaleMoney(candidate.getConcentrationBlockedProxyPnlUsdc()))
                .extremeConcentrationBlockedProxyPnlUsdc(scaleMoney(candidate.getExtremeConcentrationBlockedProxyPnlUsdc()))
                .totalConcentrationBlockedProxyPnlUsdc(totalBlockedProxyPnl)
                .concentrationBlockedProxyMaxDrawdownUsdc(scaleMoney(candidate.getConcentrationBlockedProxyMaxDrawdownUsdc()))
                .extremeConcentrationBlockedProxyMaxDrawdownUsdc(scaleMoney(candidate.getExtremeConcentrationBlockedProxyMaxDrawdownUsdc()))
                .worstConcentrationBlockedProxyMaxDrawdownUsdc(worstBlockedProxyMaxDrawdown)
                .baselineRealizedPnlUsdc(scaleMoney(baseline.getRealizedPnlUsdc()))
                .candidateRealizedPnlUsdc(scaleMoney(candidate.getRealizedPnlUsdc()))
                .realizedPnlDeltaUsdc(scaleMoney(safeDecimal(candidate.getRealizedPnlUsdc()).subtract(safeDecimal(baseline.getRealizedPnlUsdc()))))
                .baselineMaxDrawdownUsdc(scaleMoney(baseline.getMaxDrawdownUsdc()))
                .candidateMaxDrawdownUsdc(scaleMoney(candidate.getMaxDrawdownUsdc()))
                .maxDrawdownDeltaUsdc(scaleMoney(safeDecimal(candidate.getMaxDrawdownUsdc()).subtract(safeDecimal(baseline.getMaxDrawdownUsdc()))))
                .candidateConcentrationReasonDistribution(filterConcentrationReasons(candidate.getSkipReasonDistribution()))
                .build();
    }

    private void evaluateScenario(SmartWalletTradeEventEntity event,
                                  SmartWalletProfileEntity sourceProfile,
                                  WalletBehaviorProfileView behaviorProfile,
                                  SmartWalletTradeEffectLabelEntity label,
                                  SmartWalletProperties.SignalRisk risk,
                                  boolean includeObserveCooldown,
                                  StrategyAccumulator accumulator) {
        accumulator.scannedEvents++;
        SmartWalletProfileEntity replayProfile = cloneProfile(sourceProfile);
        if (replayProfile != null) {
            BigDecimal behaviorComposite = includeObserveCooldown
                    ? scoringService.behaviorComposite(behaviorProfile)
                    : null;
            replayProfile.setWalletScore(scoringService.score(
                    replayProfile.getWinRate(),
                    replayProfile.getRecentWinRate7d(),
                    replayProfile.getRecentWinRate30d(),
                    replayProfile.getRealizedPnlUsdc(),
                    replayProfile.getTotalTrades(),
                    replayProfile.getRecentTradeCount7d(),
                    replayProfile.getRecentTradeCount30d(),
                    replayProfile.getAverageRoiRate(),
                    replayProfile.getMaxDrawdownRate(),
                    behaviorComposite
            ));
            profileStoreService.applyQualificationState(replayProfile, includeObserveCooldown);
        }

        SignalRiskDecision decision = signalRiskService.evaluate(replayProfile, event, risk);
        if (decision.isAccepted()) {
            accumulator.acceptedEvents++;
            if (decision.isOverrideApplied()) {
                accumulator.overrideCount++;
            }
            if (hasConcentrationDownsize(decision.getReasons())) {
                accumulator.concentrationDownsizedAcceptedCount++;
            }
            BigDecimal scaledPnl = scalePnl(resolveExitPnl(label, risk), label, decision.getRecommendedStakeUsdc());
            BigDecimal settlementProxyPnl = scalePnl(label == null ? null : label.getNetPnlRule(), label, decision.getRecommendedStakeUsdc());
            if (scaledPnl != null) {
                accumulator.acceptedWithLabels++;
                if (scaledPnl.compareTo(BigDecimal.ZERO) > 0) {
                    accumulator.positivePnlCount++;
                }
                accumulator.realizedPnl = accumulator.realizedPnl.add(scaledPnl);
                if (settlementProxyPnl != null) {
                    accumulator.settlementProxyPnl = accumulator.settlementProxyPnl.add(settlementProxyPnl);
                }
                accumulator.cumulativePnl = accumulator.cumulativePnl.add(scaledPnl);
                accumulator.cumulativePeak = accumulator.cumulativePeak.max(accumulator.cumulativePnl);
                accumulator.maxDrawdown = accumulator.maxDrawdown.max(accumulator.cumulativePeak.subtract(accumulator.cumulativePnl));
                accumulator.totalHoldMinutes = accumulator.totalHoldMinutes.add(resolveHoldMinutes(risk, label));
            }
            return;
        }

        if (decision.getReasons() != null && decision.getReasons().stream().anyMatch(reason -> reason.contains("[OBSERVE_COOLDOWN_BLOCK]"))) {
            accumulator.recentDegradationBlockCount++;
        }
        accumulateConcentrationReplayStats(accumulator, decision, risk, label);
        if (decision.getReasons() != null) {
            for (String reason : decision.getReasons()) {
                accumulator.skipReasonDistribution.merge(classifyReason(reason), 1, Integer::sum);
            }
        }
    }

    private Map<Long, SmartWalletTradeEffectLabelEntity> loadLabels(List<SmartWalletTradeEventEntity> events) {
        List<Long> eventIds = events.stream()
                .map(SmartWalletTradeEventEntity::getId)
                .filter(Objects::nonNull)
                .toList();
        if (eventIds.isEmpty()) {
            return Map.of();
        }
        return tradeEffectLabelMapper.selectList(new LambdaQueryWrapper<SmartWalletTradeEffectLabelEntity>()
                        .in(SmartWalletTradeEffectLabelEntity::getSourceEventId, eventIds))
                .stream()
                .filter(Objects::nonNull)
                .filter(label -> label.getSourceEventId() != null)
                .collect(Collectors.toMap(
                        SmartWalletTradeEffectLabelEntity::getSourceEventId,
                        label -> label,
                        this::pickMoreRecentLabel
                ));
    }

    private Map<String, SmartWalletProfileEntity> loadProfiles(List<SmartWalletTradeEventEntity> events) {
        List<String> wallets = events.stream()
                .map(SmartWalletTradeEventEntity::getWalletAddress)
                .filter(Objects::nonNull)
                .map(this::normalizeWallet)
                .distinct()
                .toList();
        if (wallets.isEmpty()) {
            return Map.of();
        }
        return profileMapper.selectList(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                        .eq(SmartWalletProfileEntity::getSource, SOURCE))
                .stream()
                .filter(Objects::nonNull)
                .filter(profile -> profile.getWalletAddress() != null)
                .filter(profile -> wallets.contains(normalizeWallet(profile.getWalletAddress())))
                .collect(Collectors.toMap(
                        profile -> normalizeWallet(profile.getWalletAddress()),
                        this::cloneProfile,
                        (left, right) -> left
                ));
    }

    private Map<String, WalletBehaviorProfileView> loadBehaviorProfiles(List<SmartWalletTradeEventEntity> events) {
        LinkedHashMap<String, WalletBehaviorProfileView> result = new LinkedHashMap<>();
        for (SmartWalletTradeEventEntity event : events) {
            if (event == null || event.getWalletAddress() == null) {
                continue;
            }
            String walletKey = normalizeWallet(event.getWalletAddress());
            result.computeIfAbsent(walletKey, key -> walletBehaviorProfileService.getProfile(event.getWalletAddress()));
        }
        return result;
    }

    private SmartWalletProperties.SignalRisk baselineRisk() {
        SmartWalletProperties.SignalRisk risk = copyRisk(properties.getSignalRisk());
        risk.setPolicyTag("baseline-v1");
        risk.setEnableSingleWalletOverride(false);
        risk.setQualityWeightedConsensusEnabled(false);
        risk.setEnableSingleWalletOverrideCanary(false);
        risk.setEnableConcentrationGate(false);
        risk.setExitSignalProviderMode("RULES");
        if (risk.getMinDistinctEntities() == null) {
            risk.setMinDistinctEntities(risk.getMinDistinctWallets());
        }
        return risk;
    }

    private SmartWalletProperties.SignalRisk candidateRisk() {
        SmartWalletProperties.SignalRisk risk = copyRisk(properties.getSignalRisk());
        if (risk.getPolicyTag() == null || risk.getPolicyTag().isBlank()) {
            risk.setPolicyTag("candidate-v2");
        }
        return risk;
    }

    private SmartWalletProperties.SignalRisk copyRisk(SmartWalletProperties.SignalRisk source) {
        SmartWalletProperties.SignalRisk target = new SmartWalletProperties.SignalRisk();
        target.setAllowSellSignal(source.isAllowSellSignal());
        target.setEnableAutoStopLoss(source.isEnableAutoStopLoss());
        target.setAutoStopLossThresholdRate(source.getAutoStopLossThresholdRate());
        target.setEnableAutoTakeProfit(source.isEnableAutoTakeProfit());
        target.setAutoTakeProfitThresholdRate(source.getAutoTakeProfitThresholdRate());
        target.setMaxSignalAgeSeconds(source.getMaxSignalAgeSeconds());
        target.setMinTriggerSizeUsdc(source.getMinTriggerSizeUsdc());
        target.setMinFollowPrice(source.getMinFollowPrice());
        target.setMaxFollowPrice(source.getMaxFollowPrice());
        target.setEnableProfitabilityGate(source.isEnableProfitabilityGate());
        target.setProfitabilityMinSampleSize(source.getProfitabilityMinSampleSize());
        target.setMinEstimatedNetProfitabilityUsdc(source.getMinEstimatedNetProfitabilityUsdc());
        target.setEnableProbabilityGate(source.isEnableProbabilityGate());
        target.setMinCalibratedFollowProbability(source.getMinCalibratedFollowProbability());
        target.setEnableSlippageGate(source.isEnableSlippageGate());
        target.setMaxExpectedSlippageUsdc(source.getMaxExpectedSlippageUsdc());
        target.setMaxSlippageSpikeProbability(source.getMaxSlippageSpikeProbability());
        target.setMaxOpenPositions(source.getMaxOpenPositions());
        target.setMaxTotalOpenCostUsdc(source.getMaxTotalOpenCostUsdc());
        target.setMaxHoursToMarketEnd(source.getMaxHoursToMarketEnd());
        target.setBlockDuplicateOpenPosition(source.isBlockDuplicateOpenPosition());
        target.setBlockDuplicatePendingTask(source.isBlockDuplicatePendingTask());
        target.setBlockOppositeOpenPositionSameMarket(source.isBlockOppositeOpenPositionSameMarket());
        target.setBlockOppositePendingTaskSameMarket(source.isBlockOppositePendingTaskSameMarket());
        target.setEnableConsensusGate(source.isEnableConsensusGate());
        target.setPolicyTag(source.getPolicyTag());
        target.setEntryStrategyRouteMode(source.getEntryStrategyRouteMode());
        target.setEnabledEntryStrategies(source.getEnabledEntryStrategies() == null ? List.of() : new ArrayList<>(source.getEnabledEntryStrategies()));
        target.setConsensusWindowSeconds(source.getConsensusWindowSeconds());
        target.setMinDistinctWallets(source.getMinDistinctWallets());
        target.setMinDistinctEntities(source.getMinDistinctEntities());
        target.setMinConsensusScore(source.getMinConsensusScore());
        target.setQualityWeightedConsensusEnabled(source.isQualityWeightedConsensusEnabled());
        target.setConsensusEntityBaseWeight(source.getConsensusEntityBaseWeight());
        target.setConsensusBehaviorWeight(source.getConsensusBehaviorWeight());
        target.setConsensusRecencyWeight(source.getConsensusRecencyWeight());
        target.setConsensusLeaderFollowerWeight(source.getConsensusLeaderFollowerWeight());
        target.setConsensusIndependenceWeight(source.getConsensusIndependenceWeight());
        target.setConsensusRecentConsistencyWeight(source.getConsensusRecentConsistencyWeight());
        target.setEnableSingleWalletOverride(source.isEnableSingleWalletOverride());
        target.setEnableSingleWalletOverrideCanary(source.isEnableSingleWalletOverrideCanary());
        target.setSingleWalletOverrideCanaryRate(source.getSingleWalletOverrideCanaryRate());
        target.setSingleWalletOverrideWhitelist(source.getSingleWalletOverrideWhitelist() == null ? List.of() : new ArrayList<>(source.getSingleWalletOverrideWhitelist()));
        target.setBlockUnreliableLeaderboardWinRate(source.isBlockUnreliableLeaderboardWinRate());
        target.setUnreliableWinRateThreshold(source.getUnreliableWinRateThreshold());
        target.setUnreliableMaxSampleSize(source.getUnreliableMaxSampleSize());
        target.setSingleWalletOverrideMinWalletScore(source.getSingleWalletOverrideMinWalletScore());
        target.setSingleWalletOverrideMinWinRate(source.getSingleWalletOverrideMinWinRate());
        target.setSingleWalletOverrideMinTrades(source.getSingleWalletOverrideMinTrades());
        target.setSingleWalletOverrideMinRecentTrades7d(source.getSingleWalletOverrideMinRecentTrades7d());
        target.setSingleWalletOverrideMinRecentWinRate7d(source.getSingleWalletOverrideMinRecentWinRate7d());
        target.setSingleWalletOverrideMinReplicabilityScore(source.getSingleWalletOverrideMinReplicabilityScore());
        target.setSingleWalletOverrideMinQualityScore(source.getSingleWalletOverrideMinQualityScore());
        target.setSingleWalletOverrideMinTriggerSizeUsdc(source.getSingleWalletOverrideMinTriggerSizeUsdc());
        target.setSingleWalletOverrideMinRecommendedStakeUsdc(source.getSingleWalletOverrideMinRecommendedStakeUsdc());
        target.setEnableConcentrationGate(source.isEnableConcentrationGate());
        target.setConcentrationHighRiskMode(source.getConcentrationHighRiskMode());
        target.setConcentrationHighRiskDownsizeRate(source.getConcentrationHighRiskDownsizeRate());
        target.setMaxTop1ConcentrationPct(source.getMaxTop1ConcentrationPct());
        target.setMaxTop3ConcentrationPct(source.getMaxTop3ConcentrationPct());
        target.setMaxHhi(source.getMaxHhi());
        target.setWhaleSignalSourceMode(source.getWhaleSignalSourceMode());
        target.setWhaleSignalDownsizeRate(source.getWhaleSignalDownsizeRate());
        target.setConcentrationCacheTtlMs(source.getConcentrationCacheTtlMs());
        target.setConcentrationTopN(source.getConcentrationTopN());
        target.setConcentrationStakeSafeTop3Pct(source.getConcentrationStakeSafeTop3Pct());
        target.setConcentrationStakePenaltyMaxRate(source.getConcentrationStakePenaltyMaxRate());
        target.setKellyStakeEnabled(source.isKellyStakeEnabled());
        target.setKellyFraction(source.getKellyFraction());
        target.setStakeStabilityLowScore(source.getStakeStabilityLowScore());
        target.setStakeStabilityLowMultiplier(source.getStakeStabilityLowMultiplier());
        target.setStakeStabilityHighScore(source.getStakeStabilityHighScore());
        target.setStakeStabilityHighMultiplier(source.getStakeStabilityHighMultiplier());
        target.setStakeSlippagePenaltyStartRate(source.getStakeSlippagePenaltyStartRate());
        target.setStakePenaltyFloorMultiplier(source.getStakePenaltyFloorMultiplier());
        target.setStakeThemeCorrelationPenaltyMultiplier(source.getStakeThemeCorrelationPenaltyMultiplier());
        target.setTimeAwareStopLossEnabled(source.isTimeAwareStopLossEnabled());
        target.setStopLossTiers(source.getStopLossTiers() == null ? List.of() : new ArrayList<>(source.getStopLossTiers()));
        target.setTrendStopLossEnabled(source.isTrendStopLossEnabled());
        target.setTrendStopLossConsecutivePeriods(source.getTrendStopLossConsecutivePeriods());
        target.setExitSignalProviderMode(source.getExitSignalProviderMode());
        target.setExitScoringRoiMomentumWeight(source.getExitScoringRoiMomentumWeight());
        target.setExitScoringConsensusDecayWeight(source.getExitScoringConsensusDecayWeight());
        target.setExitScoringOrderbookDeteriorationWeight(source.getExitScoringOrderbookDeteriorationWeight());
        target.setExitScoringSourceWalletReductionWeight(source.getExitScoringSourceWalletReductionWeight());
        target.setExitScoringSourceWalletAddWeight(source.getExitScoringSourceWalletAddWeight());
        target.setExitScoringBaselineConsensus(source.getExitScoringBaselineConsensus());
        target.setExitScoringMinThreshold(source.getExitScoringMinThreshold());
        target.setExitScoringMaxThreshold(source.getExitScoringMaxThreshold());
        return target;
    }

    private SmartWalletProfileEntity cloneProfile(SmartWalletProfileEntity source) {
        if (source == null) {
            return null;
        }
        return new SmartWalletProfileEntity()
                .setId(source.getId())
                .setWalletAddress(source.getWalletAddress())
                .setChain(source.getChain())
                .setSource(source.getSource())
                .setAlias(source.getAlias())
                .setWinRate(source.getWinRate())
                .setRecentWinRate7d(source.getRecentWinRate7d())
                .setRecentWinRate30d(source.getRecentWinRate30d())
                .setRecentTradeCount7d(source.getRecentTradeCount7d())
                .setRecentTradeCount30d(source.getRecentTradeCount30d())
                .setRealizedPnlUsdc(source.getRealizedPnlUsdc())
                .setTotalTrades(source.getTotalTrades())
                .setAverageSizeUsdc(source.getAverageSizeUsdc())
                .setAverageRoiRate(source.getAverageRoiRate())
                .setMaxDrawdownRate(source.getMaxDrawdownRate())
                .setQualified(source.getQualified())
                .setWatchEnabled(source.getWatchEnabled())
                .setWalletScore(source.getWalletScore())
                .setManualWatchEnabled(source.getManualWatchEnabled())
                .setManualRecommendedExitLane(source.getManualRecommendedExitLane())
                .setManualOverrideNote(source.getManualOverrideNote())
                .setObserveCooldownUntil(source.getObserveCooldownUntil())
                .setObserveCooldownReason(source.getObserveCooldownReason());
    }

    private SmartWalletTradeEffectLabelEntity pickMoreRecentLabel(SmartWalletTradeEffectLabelEntity left,
                                                                  SmartWalletTradeEffectLabelEntity right) {
        Date leftTs = left.getUpdatedAt() == null ? left.getCreatedAt() : left.getUpdatedAt();
        Date rightTs = right.getUpdatedAt() == null ? right.getCreatedAt() : right.getUpdatedAt();
        if (leftTs == null) {
            return right;
        }
        if (rightTs == null) {
            return left;
        }
        return rightTs.after(leftTs) ? right : left;
    }

    private BigDecimal scalePnl(BigDecimal rawPnl, SmartWalletTradeEffectLabelEntity label, BigDecimal recommendedStakeUsdc) {
        if (rawPnl == null) {
            return null;
        }
        if (recommendedStakeUsdc == null || recommendedStakeUsdc.compareTo(BigDecimal.ZERO) <= 0) {
            return rawPnl.setScale(4, RoundingMode.HALF_UP);
        }
        if (label.getPaperStakeUsdc() == null || label.getPaperStakeUsdc().compareTo(BigDecimal.ZERO) <= 0) {
            return rawPnl.setScale(4, RoundingMode.HALF_UP);
        }
        return rawPnl
                .multiply(recommendedStakeUsdc)
                .divide(label.getPaperStakeUsdc(), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal resolveExitPnl(SmartWalletTradeEffectLabelEntity label, SmartWalletProperties.SignalRisk risk) {
        if (label == null) {
            return null;
        }
        if ("SCORING".equalsIgnoreCase(risk.getExitSignalProviderMode())) {
            return label.getNetPnl5m() != null ? label.getNetPnl5m() : label.getNetPnlRule();
        }
        return label.getNetPnlRule();
    }

    private BigDecimal resolveHoldMinutes(SmartWalletProperties.SignalRisk risk, SmartWalletTradeEffectLabelEntity label) {
        if (label == null) {
            return BigDecimal.ZERO;
        }
        if ("SCORING".equalsIgnoreCase(risk.getExitSignalProviderMode())) {
            return new BigDecimal("5");
        }
        return new BigDecimal("15");
    }

    private void accumulateConcentrationReplayStats(StrategyAccumulator accumulator,
                                                    SignalRiskDecision decision,
                                                    SmartWalletProperties.SignalRisk risk,
                                                    SmartWalletTradeEffectLabelEntity label) {
        if (decision == null || decision.getReasons() == null || decision.getReasons().isEmpty()) {
            return;
        }
        boolean extremeBlocked = decision.getReasons().stream().anyMatch(reason -> reason.contains("[EXTREME_CONCENTRATION]"));
        boolean concentrationBlocked = !extremeBlocked
                && decision.getReasons().stream().anyMatch(reason -> reason.contains("[CONCENTRATION]"));
        if (!extremeBlocked && !concentrationBlocked) {
            return;
        }
        BigDecimal proxyPnl = scalePnl(resolveExitPnl(label, risk), label, decision.getRecommendedStakeUsdc());
        if (extremeBlocked) {
            accumulator.extremeConcentrationBlockedCount++;
            accumulator.accumulateExtremeConcentrationProxy(proxyPnl);
        } else {
            accumulator.concentrationBlockedCount++;
            accumulator.accumulateConcentrationProxy(proxyPnl);
        }
    }

    private boolean hasConcentrationDownsize(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return false;
        }
        return reasons.stream().anyMatch(reason ->
                reason.contains("[CONCENTRATION_DOWNSIZE]")
                        || reason.contains("[CONCENTRATION_PENALTY]")
                        || reason.contains("[EXTREME_CONCENTRATION_DOWNSIZE]")
                        || reason.contains("[WHALE_SIGNAL]"));
    }

    private SmartWalletReplayBacktestRequest normalizeRequest(SmartWalletReplayBacktestRequest request) {
        SmartWalletReplayBacktestRequest applied = request == null ? new SmartWalletReplayBacktestRequest() : request;
        List<Integer> windows = (applied.getWindowDays() == null || applied.getWindowDays().isEmpty() ? DEFAULT_WINDOWS : applied.getWindowDays())
                .stream()
                .filter(Objects::nonNull)
                .map(day -> Math.max(day, 1))
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        applied.setWindowDays(windows.isEmpty() ? DEFAULT_WINDOWS : windows);
        applied.setMaxEventsPerWindow(applied.getMaxEventsPerWindow() == null ? 2000 : Math.max(1, Math.min(applied.getMaxEventsPerWindow(), 10000)));
        return applied;
    }

    private Map<String, Integer> filterConcentrationReasons(Map<String, Integer> reasons) {
        LinkedHashMap<String, Integer> filtered = new LinkedHashMap<>();
        if (reasons == null || reasons.isEmpty()) {
            return filtered;
        }
        reasons.forEach((key, value) -> {
            if (key == null) {
                return;
            }
            String normalized = key.trim().toUpperCase(Locale.ROOT);
            if (normalized.contains("CONCENTRATION") || normalized.contains("WHALE_SIGNAL")) {
                filtered.put(normalized, value);
            }
        });
        return filtered;
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private BigDecimal safeDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal scaleRate(BigDecimal value) {
        return safeDecimal(value).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal scaleMoney(BigDecimal value) {
        return safeDecimal(value).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal ratio(Integer numerator, Integer denominator) {
        int safeDenominator = denominator == null ? 0 : denominator;
        if (safeDenominator <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(safeInt(numerator))
                .divide(BigDecimal.valueOf(safeDenominator), 4, RoundingMode.HALF_UP);
    }

    private String normalizeWallet(String wallet) {
        return wallet == null ? null : wallet.trim().toLowerCase(Locale.ROOT);
    }

    private String classifyReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "OTHER";
        }
        int bracketEnd = reason.indexOf(']');
        if (reason.startsWith("[") && bracketEnd > 1) {
            return reason.substring(1, bracketEnd).trim().toUpperCase(Locale.ROOT);
        }
        String normalized = reason.toUpperCase(Locale.ROOT);
        if (normalized.contains("CONSENSUS")) {
            return "CONSENSUS";
        }
        if (normalized.contains("WALLET IS NOT QUALIFIED")) {
            return "WATCHLIST";
        }
        if (normalized.contains("TRIGGER SIZE")) {
            return "SIZE";
        }
        if (normalized.contains("TRIGGER PRICE")) {
            return "PRICE";
        }
        if (normalized.contains("STALE")) {
            return "STALE_SIGNAL";
        }
        return "OTHER";
    }

    private static final class StrategyAccumulator {
        private final String strategyKey;
        private final String policyTag;
        private final String exitMode;
        private int scannedEvents;
        private int acceptedEvents;
        private int acceptedWithLabels;
        private int positivePnlCount;
        private int overrideCount;
        private int recentDegradationBlockCount;
        private int concentrationBlockedCount;
        private int extremeConcentrationBlockedCount;
        private int concentrationDownsizedAcceptedCount;
        private BigDecimal realizedPnl = BigDecimal.ZERO;
        private BigDecimal settlementProxyPnl = BigDecimal.ZERO;
        private BigDecimal cumulativePnl = BigDecimal.ZERO;
        private BigDecimal cumulativePeak = BigDecimal.ZERO;
        private BigDecimal maxDrawdown = BigDecimal.ZERO;
        private BigDecimal concentrationBlockedProxyPnl = BigDecimal.ZERO;
        private BigDecimal concentrationBlockedProxyCumulative = BigDecimal.ZERO;
        private BigDecimal concentrationBlockedProxyPeak = BigDecimal.ZERO;
        private BigDecimal concentrationBlockedProxyMaxDrawdown = BigDecimal.ZERO;
        private BigDecimal extremeConcentrationBlockedProxyPnl = BigDecimal.ZERO;
        private BigDecimal extremeConcentrationBlockedProxyCumulative = BigDecimal.ZERO;
        private BigDecimal extremeConcentrationBlockedProxyPeak = BigDecimal.ZERO;
        private BigDecimal extremeConcentrationBlockedProxyMaxDrawdown = BigDecimal.ZERO;
        private BigDecimal totalHoldMinutes = BigDecimal.ZERO;
        private final Map<String, Integer> skipReasonDistribution = new LinkedHashMap<>();

        private StrategyAccumulator(String strategyKey, String policyTag, String exitMode) {
            this.strategyKey = strategyKey;
            this.policyTag = policyTag;
            this.exitMode = exitMode;
        }

        private SmartWalletReplayStrategyMetrics toMetrics() {
            BigDecimal scanned = BigDecimal.valueOf(Math.max(scannedEvents, 1));
            BigDecimal labeledAccepted = BigDecimal.valueOf(Math.max(acceptedWithLabels, 1));
            return SmartWalletReplayStrategyMetrics.builder()
                    .strategyKey(strategyKey)
                    .policyTag(policyTag)
                    .exitMode(exitMode)
                    .scannedEvents(scannedEvents)
                    .acceptedEvents(acceptedEvents)
                    .acceptedWithLabelEvents(acceptedWithLabels)
                    .acceptanceRate(BigDecimal.valueOf(acceptedEvents).divide(scanned, 4, RoundingMode.HALF_UP))
                    .labelCoverageRate(BigDecimal.valueOf(acceptedWithLabels).divide(BigDecimal.valueOf(Math.max(acceptedEvents, 1)), 4, RoundingMode.HALF_UP))
                    .overrideCount(overrideCount)
                    .recentDegradationBlockCount(recentDegradationBlockCount)
                    .concentrationBlockedCount(concentrationBlockedCount)
                    .extremeConcentrationBlockedCount(extremeConcentrationBlockedCount)
                    .concentrationDownsizedAcceptedCount(concentrationDownsizedAcceptedCount)
                    .winRate(BigDecimal.valueOf(positivePnlCount).divide(labeledAccepted, 4, RoundingMode.HALF_UP))
                    .realizedPnlUsdc(realizedPnl.setScale(4, RoundingMode.HALF_UP))
                    .settlementProxyPnlUsdc(settlementProxyPnl.setScale(4, RoundingMode.HALF_UP))
                    .maxDrawdownUsdc(maxDrawdown.setScale(4, RoundingMode.HALF_UP))
                    .concentrationBlockedProxyPnlUsdc(concentrationBlockedProxyPnl.setScale(4, RoundingMode.HALF_UP))
                    .concentrationBlockedProxyMaxDrawdownUsdc(concentrationBlockedProxyMaxDrawdown.setScale(4, RoundingMode.HALF_UP))
                    .extremeConcentrationBlockedProxyPnlUsdc(extremeConcentrationBlockedProxyPnl.setScale(4, RoundingMode.HALF_UP))
                    .extremeConcentrationBlockedProxyMaxDrawdownUsdc(extremeConcentrationBlockedProxyMaxDrawdown.setScale(4, RoundingMode.HALF_UP))
                    .averageHoldMinutes(totalHoldMinutes.divide(labeledAccepted, 2, RoundingMode.HALF_UP))
                    .skipReasonDistribution(new LinkedHashMap<>(skipReasonDistribution))
                    .build();
        }

        private void accumulateConcentrationProxy(BigDecimal pnl) {
            if (pnl == null) {
                return;
            }
            concentrationBlockedProxyPnl = concentrationBlockedProxyPnl.add(pnl);
            concentrationBlockedProxyCumulative = concentrationBlockedProxyCumulative.add(pnl);
            concentrationBlockedProxyPeak = concentrationBlockedProxyPeak.max(concentrationBlockedProxyCumulative);
            concentrationBlockedProxyMaxDrawdown = concentrationBlockedProxyMaxDrawdown.max(
                    concentrationBlockedProxyPeak.subtract(concentrationBlockedProxyCumulative));
        }

        private void accumulateExtremeConcentrationProxy(BigDecimal pnl) {
            if (pnl == null) {
                return;
            }
            extremeConcentrationBlockedProxyPnl = extremeConcentrationBlockedProxyPnl.add(pnl);
            extremeConcentrationBlockedProxyCumulative = extremeConcentrationBlockedProxyCumulative.add(pnl);
            extremeConcentrationBlockedProxyPeak = extremeConcentrationBlockedProxyPeak.max(extremeConcentrationBlockedProxyCumulative);
            extremeConcentrationBlockedProxyMaxDrawdown = extremeConcentrationBlockedProxyMaxDrawdown.max(
                    extremeConcentrationBlockedProxyPeak.subtract(extremeConcentrationBlockedProxyCumulative));
        }
    }
}
