package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletManualAllowlistEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.ConsensusSignalView;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalRiskDecision;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalStrategyCheckResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalStrategyRouteResult;
import com.metaverse.coin.strategy.job.smartwallet.model.WalletArchetypeView;
import com.metaverse.coin.strategy.job.smartwallet.model.WalletBehaviorProfileView;
import com.metaverse.coin.strategy.job.smartwallet.service.MarketConcentrationService.ConcentrationSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class SmartWalletSignalRiskService {

    private static final String LOG_PREFIX = "[POLY_FOLLOW]";
    private static final String MONITOR_TIER_HOT = "HOT";
    private static final long OBVIOUSLY_FUTURE_MARKET_END_MS = 365L * 24L * 3600_000L;

    private final SmartWalletProperties properties;
    private final ExecutionWalletContextLoader executionWalletContextLoader;
    private final PolymarketConsensusSignalService consensusSignalService;
    private final SmartWalletPositionMapper positionMapper;
    private final SmartWalletFollowTaskMapper followTaskMapper;
    private final SmartWalletOrderMapper orderMapper;
    private final SmartWalletTradeEventMapper tradeEventMapper;
    private final PolymarketMarketMetadataService marketMetadataService;
    private final SmartWalletMarketStateService marketStateService;
    private final SmartWalletAccountingService accountingService;
    private final TradeProfitabilityScoringService tradeProfitabilityScoringService;
    private final MarketConcentrationService concentrationService;
    private final SmartWalletLeaderboardStateService leaderboardStateService;
    private final SmartWalletEntryStrategyRouter entryStrategyRouter;
    private final SmartWalletMarketInfoService marketInfoService;
    private final KellyStakeCalculator kellyStakeCalculator;
    private final PolymarketApiClient polymarketApiClient;
    private final WalletArchetypeClassifierService walletArchetypeClassifierService;
    private final WalletBehaviorProfileService walletBehaviorProfileService;
    private final SmartWalletManualAllowlistService manualAllowlistService;

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
        SmartWalletManualAllowlistEntity manualAllowlist = manualAllowlistService
                .findActive(event == null ? null : event.getWalletAddress())
                .orElse(null);
        boolean manualAllowlistApplied = manualAllowlist != null;
        boolean manualWatchDisabled = profile != null && Boolean.FALSE.equals(profile.getManualWatchEnabled());
        boolean addressAdmissionRejected = false;
        if (manualWatchDisabled) {
            accepted = false;
            manualAllowlistApplied = false;
            addressAdmissionRejected = true;
            reasons.add("[MANUAL_WATCH_DISABLED] Wallet is manually forced to observe-only; manual allowlist cannot override it.");
        } else if (isObserveCooldownActive(profile)) {
            accepted = false;
            manualAllowlistApplied = false;
            addressAdmissionRejected = true;
            reasons.add("[OBSERVE_COOLDOWN_BLOCK] Wallet is currently in recent-degradation observe cooldown until "
                    + profile.getObserveCooldownUntil() + ". " + profile.getObserveCooldownReason());
        } else if (manualAllowlistApplied) {
            reasons.add("[MANUAL_ALLOWLIST_PASS] Wallet matched active manual allowlist. Address-profile admission checks are bypassed only.");
            if (profile == null) {
                accepted = false;
                reasons.add("[MANUAL_ALLOWLIST_PROFILE_MISSING] Wallet has no profile yet. PAPER observation is allowed, but executable follow is blocked until profile metrics exist.");
            }
        }

        if (!manualAllowlistApplied && !addressAdmissionRejected && !isExecutableProfile(profile)) {
            accepted = false;
            reasons.add(watchlistBlockReason(profile));
        }

        SmartWalletProperties.SignalRisk risk = overrideRisk == null ? properties.getSignalRisk() : overrideRisk;
        boolean suspiciouslyPerfectWinRate = false;
        WalletArchetypeView archetype = walletArchetypeClassifierService.classify(
                profile == null ? null : profile.getWalletAddress(),
                profile
        );
        reasons.add("[ARCHETYPE] archetype=" + archetype.archetype()
                + ", lane=" + archetype.followLane()
                + ", fit=" + archetype.followFitScore()
                + ", confidence=" + archetype.confidence() + ".");
        if (!manualAllowlistApplied && "OBSERVE_ONLY".equalsIgnoreCase(archetype.followLane())) {
            accepted = false;
            reasons.add("[ARCHETYPE_BLOCK] Wallet is currently classified as observe-only under local followability.");
        }
        WalletBehaviorProfileView behaviorProfile = profile == null
                ? null
                : walletBehaviorProfileService.getProfile(profile.getWalletAddress());
        if (behaviorProfile != null) {
            reasons.add("[BEHAVIOR] exitLane=" + behaviorProfile.recommendedExitLane()
                    + ", replicability=" + behaviorProfile.replicabilityScore()
                    + ", earlyExitRate=" + behaviorProfile.earlyExitRate()
                    + ", settlementCompletionRate=" + behaviorProfile.settlementCompletionRate() + ".");
            if (!manualAllowlistApplied && "FAST_EXIT_OR_SKIP".equalsIgnoreCase(behaviorProfile.recommendedExitLane())) {
                accepted = false;
                reasons.add("[BEHAVIOR_BLOCK] Wallet relies on very fast exits or low-replicability timing. Skip follow entry.");
            }
        }

        // Warn early when this wallet has a suspiciously perfect win rate (leaderboard survivorship bias).
        // Single-wallet override will be blocked for these wallets; consensus path still allowed but flagged.
        if (risk.isBlockUnreliableLeaderboardWinRate() && profile != null) {
            BigDecimal winRate = safe(profile.getWinRate());
            int totalTrades = profile.getTotalTrades() == null ? 0 : profile.getTotalTrades();
            suspiciouslyPerfectWinRate = winRate.compareTo(safe(risk.getUnreliableWinRateThreshold())) >= 0
                    && totalTrades <= (risk.getUnreliableMaxSampleSize() == null ? 50 : risk.getUnreliableMaxSampleSize());
            if (suspiciouslyPerfectWinRate) {
                reasons.add("[UNRELIABLE_WIN_RATE] Wallet winRate=" + winRate + " with only " + totalTrades
                        + " trades — likely leaderboard survivorship bias. Single-wallet override blocked.");
            }
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

        CapitalContext capitalContext = resolveCapitalContext();
        BigDecimal availableCapital = capitalContext.effectiveCapital();
        if (availableCapital.compareTo(BigDecimal.ZERO) <= 0) {
            accepted = false;
            reasons.add("Available capital is depleted. Strategy is paused to avoid bankroll penetration.");
        }
        if (capitalContext.liveWalletBalanceApplied()) {
            reasons.add("Live wallet balance sizing applied. liveBalanceUsdc="
                    + capitalContext.liveWalletBalance().setScale(2, RoundingMode.HALF_UP)
                    + ", effectiveCapitalUsdc="
                    + capitalContext.effectiveCapital().setScale(2, RoundingMode.HALF_UP) + ".");
        }

        if (event.getOpenedAt() != null) {
            long ageMs = Math.max(0L, System.currentTimeMillis() - event.getOpenedAt().getTime());
            long effectiveMaxSignalAgeMs = resolveEffectiveSignalAgeMs(risk, archetype);
            if (ageMs > effectiveMaxSignalAgeMs) {
                accepted = false;
                reasons.add("Signal is stale for lane " + archetype.followLane() + ".");
            }
        }

        SmartWalletMarketStateService.MarketStateSnapshot marketState = marketStateService.refreshMarket(
                event.getMarketId(),
                event.getQuestion(),
                StringUtils.hasText(event.getOutcome()) ? List.of(event.getOutcome()) : List.of()
        );
        if (!marketState.isActive()) {
            accepted = false;
            reasons.add(marketState.blockingReasonMessage());
        }
        Long endAt = marketState.marketEndAt();
        if (endAt != null && endAt > 0) {
            long remainingMs = endAt - System.currentTimeMillis();
            if (remainingMs <= 0) {
                accepted = false;
                reasons.add("Market is already at or past expected end time.");
            } else {
                long maxHours = defaultInt(risk.getMaxHoursToMarketEnd(), Integer.MAX_VALUE);
                if (remainingMs > OBVIOUSLY_FUTURE_MARKET_END_MS) {
                    accepted = false;
                    reasons.add("[MARKET_END_AT_DATA_QUALITY] Market end time is unexpectedly far in the future; verify metadata before following.");
                } else if (remainingMs > maxHours * 3600_000L) {
                    accepted = false;
                    reasons.add("Market end time is too far away for the current capital-turnover policy.");
                }
            }
        }

        boolean hasOppositeOpenPosition = hasOpenPositionOnSameMarketDifferentOutcome(event.getMarketId(), event.getOutcome());
        MarketRoutingContext marketRouting = classifyMarket(event, marketState, endAt, risk, hasOppositeOpenPosition);
        String marketType = marketRouting.marketType();
        String entryLane = marketRouting.entryLane();
        String exitIntent = marketRouting.exitIntent();
        reasons.add("[MARKET_TYPE] marketType=" + marketType + ", entryLane=" + entryLane
                + (StringUtils.hasText(exitIntent) ? ", exitIntent=" + exitIntent : "") + ".");
        if (!risk.isAllowSellSignal() && "SELL".equalsIgnoreCase(event.getSide())
                && !"DYNAMIC_FOLLOW_EXIT".equals(entryLane)) {
            accepted = false;
            reasons.add("SELL signals are disabled by risk policy.");
        }
        if ("FAST_EVENT".equals(marketType)
                && !"DYNAMIC_FOLLOW_EXIT".equals(entryLane)) {
            accepted = false;
            reasons.add("[FAST_EVENT_ENTRY_BLOCK] Fast-event markets are blocked for new entry; only dynamic follow-exit signals are allowed.");
        }

        boolean hasDuplicatePosition = risk.isBlockDuplicateOpenPosition()
                && hasOpenPosition(event.getMarketId(), event.getOutcome());

        if (risk.isBlockOppositeOpenPositionSameMarket()
                && hasOppositeOpenPosition
                && !"DYNAMIC_FOLLOW_EXIT".equals(entryLane)) {
            accepted = false;
            reasons.add("An open position already exists for the same market with a different outcome.");
        }

        if (risk.isBlockDuplicatePendingTask()
                && hasPendingFollowTask(event.getMarketId(), event.getOutcome(), currentFollowTaskId)
                && !"DYNAMIC_FOLLOW_EXIT".equals(entryLane)) {
            accepted = false;
            reasons.add("A pending follow task already exists for the same market and outcome.");
        }

        if (risk.isBlockOppositePendingTaskSameMarket()
                && hasPendingFollowTaskOnSameMarketDifferentOutcome(event.getMarketId(), event.getOutcome(), currentFollowTaskId)
                && !"DYNAMIC_FOLLOW_EXIT".equals(entryLane)) {
            accepted = false;
            reasons.add("A pending follow task already exists for the same market with a different outcome.");
        }

        ConsensusSignalView consensus = consensusSignalService.buildConsensusForEvent(event, risk);

        // 告警：检测到同一实体多钱包同时触发（记录但不拒绝）
        if (consensus.isHasSuspiciousMultiWallet()) {
            reasons.add("[MULTI_WALLET_INFLATION] Suspicious multi-wallet entity detected in consensus signal. "
                    + "Raw wallets=" + consensus.getDistinctWallets()
                    + ", Deduped entities=" + consensus.getDedupedEntityCount()
                    + ". Using entity count for gate evaluation.");
        }
        if (safe(consensus.getTopEntityWeightShare()).compareTo(new BigDecimal("0.75")) > 0
                && consensus.getDedupedEntityCount() < 3) {
            reasons.add("[CONSENSUS_DOMINANCE] One entity contributes "
                    + safe(consensus.getTopEntityWeightShare()).multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP)
                    + "% of weighted consensus with only " + consensus.getDedupedEntityCount() + " deduped entities.");
        }
        if (safe(consensus.getEntityIndependenceScore()).compareTo(new BigDecimal("0.45")) < 0) {
            reasons.add("[CONSENSUS_INDEPENDENCE] Entity independence score is weak at "
                    + safe(consensus.getEntityIndependenceScore()).setScale(3, RoundingMode.HALF_UP) + ".");
        }
        if (safe(consensus.getRecentConsistencyScore()).compareTo(new BigDecimal("0.50")) < 0) {
            reasons.add("[CONSENSUS_CONSISTENCY] Recent entity consistency is weak at "
                    + safe(consensus.getRecentConsistencyScore()).setScale(3, RoundingMode.HALF_UP) + ".");
        }
        if (safe(consensus.getLeaderFollowerScore()).compareTo(new BigDecimal("0.40")) < 0) {
            reasons.add("[CONSENSUS_TIMING] Consensus is driven by weak leader/follower sequencing at "
                    + safe(consensus.getLeaderFollowerScore()).setScale(3, RoundingMode.HALF_UP) + ".");
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
            if ("LONG_HORIZON".equals(marketType)) {
                minEntities = Math.max(minEntities, 2);
            }
            if (effectiveEntityCount < minEntities) {
                consensusEntityCountFailed = true;
            }
            if (safe(consensus.getConsensusScore()).compareTo(safe(risk.getMinConsensusScore())) < 0) {
                consensusScoreFailed = true;
            }
        }

        BigDecimal walletScore = profile == null
                ? BigDecimal.ZERO
                : leaderboardStateService.adjustWalletScore(profile, event);
        BigDecimal preliminaryStake = availableCapital
                .multiply(properties.getBankrollUtilizationRate())
                .multiply(walletScore)
                .min(properties.getMaxStakeUsdc())
                .setScale(2, RoundingMode.HALF_UP);
        preliminaryStake = applyArchetypeStakeAdjustments(preliminaryStake, archetype, event, reasons);
        preliminaryStake = applyBehaviorStakeAdjustments(preliminaryStake, behaviorProfile, reasons);
        preliminaryStake = applyDynamicCapitalScale(preliminaryStake, capitalContext);
        if ("LONG_HORIZON".equals(marketType)) {
            BigDecimal multiplier = risk.getLongHorizonStakeMultiplier() == null
                    ? new BigDecimal("0.50")
                    : safe(risk.getLongHorizonStakeMultiplier());
            preliminaryStake = preliminaryStake.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
            reasons.add("[LONG_HORIZON_STAKE] Long-horizon market stake multiplier applied: "
                    + multiplier.setScale(2, RoundingMode.HALF_UP) + ".");
        }
        preliminaryStake = preliminaryStake.min(availableCapital.max(BigDecimal.ZERO)).setScale(2, RoundingMode.HALF_UP);

        ExecutionWalletContextLoader.ExecutionWalletContext executionWallet = executionWalletContextLoader.load();
        BigDecimal minOrderUsdc = executionWallet.getMinOrderUsdc() == null
                ? BigDecimal.ONE
                : executionWallet.getMinOrderUsdc();
        if (preliminaryStake.compareTo(minOrderUsdc) < 0) {
            accepted = false;
            reasons.add("Recommended stake is below execution wallet minimum order.");
        }

        // ── 集中度门控（防控盘）──────────────────────────────────────────────────
        // 在共识门控之后、风险分数估算之前运行，避免对最终被拒绝的信号做不必要的 API 调用
        if (risk.isEnableConcentrationGate()) {
            ConcentrationSnapshot snap = concentrationService.analyze(event.getMarketId(), event.getOutcome());
            if (snap.isDataAvailable()) {
                int minObservedHolders = risk.getConcentrationMinObservedHoldersForGate() == null
                        ? 0
                        : Math.max(0, risk.getConcentrationMinObservedHoldersForGate());
                if (snap.observedHolders() < minObservedHolders) {
                    reasons.add("[CONCENTRATION_LOW_COVERAGE] Only " + snap.observedHolders()
                            + " holders observed for concentration sample; gate requires at least "
                            + minObservedHolders + ". Concentration result is not enforced.");
                } else {
                    // Gate 1：极端集中市场优先处理，避免和普通高集中共用同一套 downsizing
                    if (snap.isExtremelyConcentrated()) {
                        String mode = normalizeMode(risk.getExtremeConcentrationMode(), "DOWNSIZE");
                        switch (mode) {
                            case "BLOCK" -> {
                                accepted = false;
                                reasons.add("[EXTREME_CONCENTRATION] Market is extremely concentrated. " +
                                        "CR1=" + snap.top1Pct().setScale(3, RoundingMode.HALF_UP) +
                                        " CR3=" + snap.top3Pct().setScale(3, RoundingMode.HALF_UP) +
                                        " CR5=" + snap.top5Pct().setScale(3, RoundingMode.HALF_UP) +
                                        " HHI=" + snap.hhi() +
                                        " holders=" + snap.observedHolders());
                            }
                            case "DOWNSIZE" -> {
                                BigDecimal retainRate = safe(risk.getExtremeConcentrationDownsizeRate()).max(new BigDecimal("0.05")).min(BigDecimal.ONE);
                                preliminaryStake = preliminaryStake.multiply(retainRate).setScale(2, RoundingMode.HALF_UP);
                                reasons.add("[EXTREME_CONCENTRATION_DOWNSIZE] Market is extremely concentrated. " +
                                        "CR1=" + snap.top1Pct().setScale(3, RoundingMode.HALF_UP) +
                                        " CR3=" + snap.top3Pct().setScale(3, RoundingMode.HALF_UP) +
                                        " CR5=" + snap.top5Pct().setScale(3, RoundingMode.HALF_UP) +
                                        " HHI=" + snap.hhi() +
                                        ". Stake retained at " + retainRate.multiply(new BigDecimal("100")).setScale(0, RoundingMode.HALF_UP) + "%.");
                            }
                            default -> reasons.add("[EXTREME_CONCENTRATION_WARN] Extremely concentrated market detected but warn-only mode is enabled. " +
                                    "CR1=" + snap.top1Pct().setScale(3, RoundingMode.HALF_UP) +
                                    " CR3=" + snap.top3Pct().setScale(3, RoundingMode.HALF_UP) +
                                    " CR5=" + snap.top5Pct().setScale(3, RoundingMode.HALF_UP) +
                                    " HHI=" + snap.hhi() + ".");
                        }
                    } else if (snap.isHighlyConcentrated()) {
                        // Gate 2：普通高集中市场 → 根据模式选择 BLOCK / DOWNSIZE / WARN_ONLY
                        String mode = normalizeMode(risk.getConcentrationHighRiskMode(), "DOWNSIZE");
                        switch (mode) {
                            case "BLOCK" -> {
                                accepted = false;
                                reasons.add("[CONCENTRATION] Market is highly concentrated. " +
                                        "CR1=" + snap.top1Pct().setScale(3, RoundingMode.HALF_UP) +
                                        " CR3=" + snap.top3Pct().setScale(3, RoundingMode.HALF_UP) +
                                        " HHI=" + snap.hhi() +
                                        " holders=" + snap.observedHolders());
                            }
                            case "DOWNSIZE" -> {
                                BigDecimal retainRate = safe(risk.getConcentrationHighRiskDownsizeRate()).max(new BigDecimal("0.05")).min(BigDecimal.ONE);
                                preliminaryStake = preliminaryStake.multiply(retainRate).setScale(2, RoundingMode.HALF_UP);
                                reasons.add("[CONCENTRATION_DOWNSIZE] Highly concentrated market. " +
                                        "CR1=" + snap.top1Pct().setScale(3, RoundingMode.HALF_UP) +
                                        " CR3=" + snap.top3Pct().setScale(3, RoundingMode.HALF_UP) +
                                        " HHI=" + snap.hhi() +
                                        ". Stake retained at " + retainRate.multiply(new BigDecimal("100")).setScale(0, RoundingMode.HALF_UP) + "%.");
                            }
                            default -> reasons.add("[CONCENTRATION_WARN] Highly concentrated market detected but warn-only mode is enabled. " +
                                    "CR1=" + snap.top1Pct().setScale(3, RoundingMode.HALF_UP) +
                                    " CR3=" + snap.top3Pct().setScale(3, RoundingMode.HALF_UP) +
                                    " HHI=" + snap.hhi() + ".");
                        }
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
                            preliminaryStake = preliminaryStake.multiply(retainRate).setScale(2, RoundingMode.HALF_UP);
                            reasons.add("[CONCENTRATION_PENALTY] CR3=" +
                                    snap.top3Pct().setScale(3, RoundingMode.HALF_UP) +
                                    " in caution zone. Stake reduced by " +
                                    penaltyRate.multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP) + "%.");
                        }
                    }

                    // Gate 4：信号源钱包本身是鲸鱼
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
                                preliminaryStake = preliminaryStake.multiply(downsize).setScale(2, RoundingMode.HALF_UP);
                                reasons.add("[WHALE_SIGNAL] Signal source is a top-5 holder. " +
                                        "Stake downsized to " +
                                        downsize.multiply(new BigDecimal("100")).setScale(0, RoundingMode.HALF_UP) + "%.");
                            }
                            default -> reasons.add("[WHALE_SIGNAL] Signal source is a top-5 holder. " +
                                    "WARN_ONLY mode — signal not blocked.");
                        }
                    }
                }
            }
            // API 不可用时 fail-open：跳过集中度检查，不阻断信号
        }

        TradeProfitabilityScoringService.ProfitabilityEstimate profitabilityEstimate =
                tradeProfitabilityScoringService.estimate(profile, event, consensus, preliminaryStake);

        // ── Kelly 仓位计算 ─────────────────────────────────────────────────────
        BigDecimal recommendedStake;
        if (risk.isKellyStakeEnabled()) {
            BigDecimal kellyStake = kellyStakeCalculator.compute(
                    profitabilityEstimate.getCalibratedFollowProbability(),
                    event.getPrice(),
                    availableCapital,
                    risk.getKellyFraction(),
                    minOrderUsdc,
                    properties.getMaxStakeUsdc(),
                    risk.getKellySignalEdgeFactor()
            );
            if (kellyStake == null) {
                // 概率未知，回退到 preliminaryStake
                recommendedStake = preliminaryStake;
                reasons.add("Kelly stake: probability unknown, falling back to legacy formula.");
            } else if (kellyStake.compareTo(BigDecimal.ZERO) == 0) {
                // EV 为负，Kelly 建议拒绝
                accepted = false;
                recommendedStake = BigDecimal.ZERO;
                reasons.add("Kelly stake: negative EV, signal rejected.");
            } else {
                recommendedStake = kellyStake;
                reasons.add("Kelly stake=" + kellyStake + " (p=" +
                        safe(profitabilityEstimate.getCalibratedFollowProbability()).setScale(4, RoundingMode.HALF_UP) +
                        " price=" + safe(event.getPrice()).setScale(4, RoundingMode.HALF_UP) + ").");
            }
        } else {
            recommendedStake = preliminaryStake;
        }
        if ("LONG_HORIZON".equals(marketType) && risk.isKellyStakeEnabled()) {
            BigDecimal multiplier = risk.getLongHorizonStakeMultiplier() == null
                    ? new BigDecimal("0.50")
                    : safe(risk.getLongHorizonStakeMultiplier());
            recommendedStake = recommendedStake.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
            reasons.add("[LONG_HORIZON_STAKE] Long-horizon market Kelly stake multiplier applied: "
                    + multiplier.setScale(2, RoundingMode.HALF_UP) + ".");
        }
        recommendedStake = applyAdvancedStakeAdjustments(recommendedStake, behaviorProfile, profitabilityEstimate, event, risk, reasons);
        recommendedStake = safe(recommendedStake).min(availableCapital.max(BigDecimal.ZERO)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal stakeMultiplierApplied = null;
        if (manualAllowlistApplied) {
            stakeMultiplierApplied = manualAllowlistService.stakeMultiplier(manualAllowlist);
            recommendedStake = recommendedStake.multiply(stakeMultiplierApplied).setScale(2, RoundingMode.HALF_UP);
            reasons.add("[MANUAL_ALLOWLIST_STAKE_MULTIPLIER] Applied manual allowlist stake multiplier="
                    + stakeMultiplierApplied.setScale(4, RoundingMode.HALF_UP) + ".");
        }
        if (accepted && recommendedStake.compareTo(minOrderUsdc) < 0) {
            accepted = false;
            reasons.add("Recommended stake is below execution wallet minimum order after capital scaling.");
        }

        if (profitabilityEstimate.getEstimateSampleSize() != null && profitabilityEstimate.getEstimateSampleSize() > 0) {
            reasons.add("Local risk estimate sample size=" + profitabilityEstimate.getEstimateSampleSize() + ".");
        }
        reasons.add("Model follow probability=" + safe(profitabilityEstimate.getCalibratedFollowProbability()).setScale(4, RoundingMode.HALF_UP) + ".");
        reasons.add("Model slippage spike probability=" + safe(profitabilityEstimate.getSlippageSpikeProbability()).setScale(4, RoundingMode.HALF_UP) + ".");
        if (risk.isEnableProfitabilityGate()
                && defaultInt(risk.getProfitabilityMinSampleSize(), 0) > 0
                && defaultInt(profitabilityEstimate.getEstimateSampleSize(), 0) >= defaultInt(risk.getProfitabilityMinSampleSize(), 0)
                && safe(profitabilityEstimate.getEstimatedNetProfitabilityUsdc()).compareTo(safe(risk.getMinEstimatedNetProfitabilityUsdc())) < 0) {
            accepted = false;
            reasons.add("Local historical net profitability estimate is below threshold.");
        }
        if (risk.isEnableProbabilityGate()
                && safe(profitabilityEstimate.getCalibratedFollowProbability()).compareTo(safe(risk.getMinCalibratedFollowProbability())) < 0) {
            accepted = false;
            reasons.add("Calibrated follow probability is below threshold.");
        }
        if (risk.isEnableSlippageGate()
                && safe(profitabilityEstimate.getEstimatedSlippageUsdc()).compareTo(safe(risk.getMaxExpectedSlippageUsdc())) > 0) {
            accepted = false;
            reasons.add("Predicted slippage cost is above threshold.");
        }
        if (risk.isEnableSlippageGate()
                && safe(profitabilityEstimate.getSlippageSpikeProbability()).compareTo(safe(risk.getMaxSlippageSpikeProbability())) > 0) {
            accepted = false;
            reasons.add("Slippage spike probability is above threshold.");
        }

        List<SignalStrategyCheckResult> strategyChecks = buildEntryStrategyChecks(
                profile,
                behaviorProfile,
                risk,
                triggerSize,
                recommendedStake,
                consensus,
                consensusEntityCountFailed,
                consensusScoreFailed,
                marketType,
                suspiciouslyPerfectWinRate,
                event
        );
        SignalStrategyRouteResult strategyRoute = entryStrategyRouter.route(risk, strategyChecks, consensus.getDedupedEntityCount());
        if (!strategyRoute.isAccepted()) {
            if ("DYNAMIC_FOLLOW_EXIT".equals(entryLane)) {
                reasons.add("[DYNAMIC_FOLLOW_EXIT] Entry strategy gates are bypassed for exit/rebalance classification; execution is handled outside normal entry order submission.");
            } else if (manualAllowlistApplied && !consensusScoreFailed) {
                overrideApplied = true;
                reasons.add("[MANUAL_ALLOWLIST_PASS] Manual allowlist bypassed entry strategy address-admission blockers while preserving trade-level gates.");
            } else {
                accepted = false;
                reasons.addAll(strategyRoute.getReasons());
                if (manualAllowlistApplied && consensusScoreFailed) {
                    reasons.add("[MANUAL_ALLOWLIST_CONSENSUS_SCORE_BLOCK] Manual allowlist cannot bypass consensus score gate.");
                }
            }
        } else if (strategyRoute.getMatchedStrategies().contains("HIGH_QUALITY_SINGLE_WALLET")) {
            overrideApplied = true;
            entryLane = "HIGH_QUALITY_SINGLE_WALLET";
            reasons.add("[HIGH_QUALITY_SINGLE_WALLET] High-quality single-wallet strategy accepted this signal independently.");
        } else if (strategyRoute.getMatchedStrategies().contains("CONSENSUS")) {
            entryLane = "MULTI_ENTITY_CONSENSUS";
        }

        if (accepted) {
            reasons.add("Signal passed watchlist, freshness, size and price checks.");
        }

        // ── Consensus Stake Amplifier ─────────────────────────────────────────────
        if (accepted && strategyRoute.getStakeMultiplier().compareTo(BigDecimal.ONE) > 0) {
            recommendedStake = recommendedStake
                    .multiply(strategyRoute.getStakeMultiplier())
                    .min(properties.getMaxStakeUsdc())
                    .setScale(2, RoundingMode.HALF_UP);
            reasons.add("[CONSENSUS_AMPLIFIER] dedupedEntities=" + consensus.getDedupedEntityCount()
                    + ", stakeMultiplier=\u00d7" + strategyRoute.getStakeMultiplier().setScale(2, RoundingMode.HALF_UP) + ".");
        }

        // ── Late-phase duplicate-position check (scale-up path) ───────────────────
        boolean isScaleUpSignal = false;
        BigDecimal scaleUpAddOnStake = BigDecimal.ZERO;
        if (hasDuplicatePosition && !"DYNAMIC_FOLLOW_EXIT".equals(entryLane)) {
            if (risk.isEnableScaleUpOnHighQualityConsensus()) {
                SingleWalletOverrideAssessment scaleUpAssessment = assessHighQualitySingleWallet(
                        profile, behaviorProfile, risk, triggerSize,
                        recommendedStake, suspiciouslyPerfectWinRate, event);
                if (scaleUpAssessment.qualifies()) {
                    ExistingPositionStats existing = getExistingPositionStats(event.getMarketId(), event.getOutcome());
                    BigDecimal existingCost = existing.cost();
                    BigDecimal currentPrice = safe(event.getPrice());
                    BigDecimal avgCost = existing.avgPrice();
                    BigDecimal existingRoi = existing.roiAtPrice(currentPrice);
                    BigDecimal lossFloor = safe(risk.getAutoStopLossThresholdRate()).negate();

                    boolean lossOk = existingRoi.compareTo(lossFloor) >= 0;
                    boolean priceAbsOk = currentPrice.compareTo(safe(risk.getMaxFollowPrice())) <= 0;
                    boolean priceRelOk = avgCost.compareTo(BigDecimal.ZERO) <= 0
                            || currentPrice.compareTo(
                                    avgCost.multiply(BigDecimal.ONE.add(safe(risk.getScaleUpMaxPriceRelativeDrift())))
                            ) <= 0;
                    boolean independentOrFollowOn = isIndependentOrFollowOnScaleUp(existing, profile, event);
                    BigDecimal marketOutcomeBudget = scaleUpMarketOutcomeBudget(risk, executionWallet);
                    BigDecimal targetCost = recommendedStake.min(marketOutcomeBudget).setScale(2, RoundingMode.HALF_UP);

                    if (lossOk && priceAbsOk && priceRelOk && independentOrFollowOn) {
                        BigDecimal addOn = targetCost.subtract(existingCost)
                                .max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
                        if (existingCost.add(addOn).compareTo(marketOutcomeBudget) > 0) {
                            accepted = false;
                            reasons.add("[SCALE_UP_BUDGET_BLOCK] Scale-up would exceed market/outcome budget. existingCost="
                                    + existingCost + " addOn=" + addOn + " budget=" + marketOutcomeBudget + ".");
                        } else if (addOn.compareTo(minOrderUsdc) >= 0) {
                            isScaleUpSignal = true;
                            scaleUpAddOnStake = addOn;
                            reasons.add("[SCALE_UP] High-quality wallet triggered scale-up on existing position. addOn="
                                    + addOn + " existingCost=" + existingCost + " budget=" + marketOutcomeBudget + ".");
                        } else {
                            accepted = false;
                            reasons.add("[SCALE_UP_MIN_ORDER_BLOCK] Add-on is below minimum order. addOn="
                                    + addOn + " minOrder=" + minOrderUsdc + ".");
                        }
                    } else {
                        accepted = false;
                        if (!lossOk) {
                            reasons.add("[SCALE_UP_LOSS_BLOCK] Existing position ROI "
                                    + existingRoi.setScale(4, RoundingMode.HALF_UP)
                                    + " is below allowed loss floor " + lossFloor.setScale(4, RoundingMode.HALF_UP) + ".");
                        }
                        if (!priceAbsOk) {
                            reasons.add("[SCALE_UP_PRICE_BLOCK] Current price exceeds maxFollowPrice="
                                    + safe(risk.getMaxFollowPrice()) + ".");
                        }
                        if (!priceRelOk) {
                            reasons.add("[SCALE_UP_PRICE_BLOCK] Price drift exceeds relative limit. current="
                                    + currentPrice.setScale(4, RoundingMode.HALF_UP)
                                    + " avgCost=" + avgCost.setScale(4, RoundingMode.HALF_UP)
                                    + " maxDrift=" + risk.getScaleUpMaxPriceRelativeDrift() + ".");
                        }
                        if (!independentOrFollowOn) {
                            reasons.add("[SCALE_UP_SIGNAL_BLOCK] Scale-up requires a different existing entity/source or a verified follow-on BUY from the same source wallet.");
                        }
                    }
                } else {
                    accepted = false;
                    reasons.add("An open position already exists for the same market and outcome.");
                }
            } else {
                accepted = false;
                reasons.add("An open position already exists for the same market and outcome.");
            }
        }

        SignalRiskDecision result = SignalRiskDecision.builder()
                .accepted(accepted)
                .overrideApplied(overrideApplied)
                .manualAllowlistApplied(manualAllowlistApplied)
                .manualAllowlistReason(manualAllowlist == null ? null : manualAllowlist.getReason())
                .stakeMultiplierApplied(stakeMultiplierApplied)
                .recommendedStakeUsdc(recommendedStake)
                .marketType(marketType)
                .entryLane(entryLane)
                .exitIntent(exitIntent)
                .estimatedNetProfitabilityUsdc(profitabilityEstimate.getEstimatedNetProfitabilityUsdc())
                .estimatedFeeUsdc(profitabilityEstimate.getEstimatedFeeUsdc())
                .estimatedSlippageUsdc(profitabilityEstimate.getEstimatedSlippageUsdc())
                .followProbability(profitabilityEstimate.getFollowProbability())
                .calibratedFollowProbability(profitabilityEstimate.getCalibratedFollowProbability())
                .slippageSpikeProbability(profitabilityEstimate.getSlippageSpikeProbability())
                .estimateSampleSize(profitabilityEstimate.getEstimateSampleSize())
                .consensusWallets(consensus.getDistinctWallets())        // 原始钱包数（历史兼容）
                .consensusEntities(consensus.getDedupedEntityCount())    // 实体去重后数量（真实强度）
                .consensusScore(consensus.getConsensusScore())
                .qualityWeightedConsensusScore(consensus.getQualityWeightedConsensusScore())
                .topEntityWeightShare(consensus.getTopEntityWeightShare())
                .leaderFollowerScore(consensus.getLeaderFollowerScore())
                .entityIndependenceScore(consensus.getEntityIndependenceScore())
                .recentConsistencyScore(consensus.getRecentConsistencyScore())
                .hasSuspiciousMultiWallet(consensus.isHasSuspiciousMultiWallet())
                .policyTag(risk.getPolicyTag())
                .strategyRouteMode(strategyRoute.getRouteMode())
                .enabledStrategies(strategyRoute.getEnabledStrategies())
                .matchedStrategies(strategyRoute.getMatchedStrategies())
                .strategyChecks(strategyChecks)
                .scaleUpSignal(isScaleUpSignal)
                .scaleUpAddOnStake(isScaleUpSignal ? scaleUpAddOnStake : null)
                .reasons(reasons)
                .build();
        log.info("{} action=完成跟单风控评估 result={} followTaskId={} wallet={} marketId={} outcome={} side={} recommendedStakeUsdc={} consensusWallets={} consensusEntities={} consensusScore={} routeMode={} matchedStrategies={} reasons={}",
                LOG_PREFIX, result.isAccepted() ? "通过" : "拒绝", currentFollowTaskId,
                profile == null ? null : profile.getWalletAddress(), event == null ? null : event.getMarketId(),
                event == null ? null : event.getOutcome(), event == null ? null : event.getSide(),
                result.getRecommendedStakeUsdc(), result.getConsensusWallets(), result.getConsensusEntities(),
                result.getConsensusScore(), result.getStrategyRouteMode(), result.getMatchedStrategies(),
                String.join(" | ", result.getReasons()));
        return result;
    }

    private MarketRoutingContext classifyMarket(SmartWalletTradeEventEntity event,
                                                SmartWalletMarketStateService.MarketStateSnapshot marketState,
                                                Long marketEndAt,
                                                SmartWalletProperties.SignalRisk risk,
                                                boolean hasOppositeOpenPosition) {
        boolean sourceSell = event != null && "SELL".equalsIgnoreCase(event.getSide());
        boolean oppositeBuy = event != null && hasOppositeOpenPosition && "BUY".equalsIgnoreCase(event.getSide());
        String exitIntent = sourceSell ? "SOURCE_SELL" : (oppositeBuy ? "OPPOSITE_BUY" : null);
        String entryLane = StringUtils.hasText(exitIntent) ? "DYNAMIC_FOLLOW_EXIT" : "STANDARD_ENTRY";
        return new MarketRoutingContext(resolveMarketType(event, marketState, marketEndAt, risk), entryLane, exitIntent);
    }

    private String resolveMarketType(SmartWalletTradeEventEntity event,
                                     SmartWalletMarketStateService.MarketStateSnapshot marketState,
                                     Long marketEndAt,
                                     SmartWalletProperties.SignalRisk risk) {
        long fastHours = defaultInt(risk.getFastEventMaxHours(), 24);
        if (marketEndAt != null && marketEndAt > 0) {
            long remainingMs = marketEndAt - System.currentTimeMillis();
            if (remainingMs > 0 && remainingMs <= fastHours * 3600_000L) {
                return "FAST_EVENT";
            }
        }
        String text = ((event == null ? "" : nullToEmpty(event.getQuestion())) + " "
                + (marketState == null ? "" : nullToEmpty(marketState.normalizedCategory())) + " "
                + (marketState == null ? "" : nullToEmpty(marketState.sourceCategory()))).toLowerCase(Locale.ROOT);
        if (containsAny(text, List.of("sports", "sport", "nba", "nfl", "nhl", "mlb", "soccer", "football",
                "tennis", "ufc", "boxing", "mma", "esports", "e-sports", "cs2", "counter-strike",
                "league of legends", "dota", "fifa", "valorant", "overwatch", " vs "))) {
            return "FAST_EVENT";
        }
        if (marketEndAt != null && marketEndAt > 0) {
            long remainingMs = marketEndAt - System.currentTimeMillis();
            int longHorizonMinHours = risk.getLongHorizonMinHours() != null
                    ? risk.getLongHorizonMinHours()
                    : (defaultInt(risk.getMaxHoursToMarketEnd(), Integer.MAX_VALUE) / 2);
            if (remainingMs > longHorizonMinHours * 3600_000L) {
                return "LONG_HORIZON";
            }
        }
        String outcome = event == null ? null : event.getOutcome();
        if ("YES".equalsIgnoreCase(outcome) || "NO".equalsIgnoreCase(outcome)) {
            return "STANDARD_BINARY";
        }
        return "UNKNOWN";
    }

    private boolean containsAny(String value, List<String> needles) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private boolean isExecutableProfile(SmartWalletProfileEntity profile) {
        if (profile == null || isObserveCooldownActive(profile)) {
            return false;
        }
        return Boolean.TRUE.equals(profile.getWatchEnabled())
                && MONITOR_TIER_HOT.equalsIgnoreCase(profile.getMonitorTier());
    }

    private boolean isObserveCooldownActive(SmartWalletProfileEntity profile) {
        return profile != null
                && profile.getObserveCooldownUntil() != null
                && profile.getObserveCooldownUntil().after(new Date());
    }

    private String watchlistBlockReason(SmartWalletProfileEntity profile) {
        if (profile == null) {
            return "Wallet is not qualified in watchlist.";
        }
        if (Boolean.FALSE.equals(profile.getManualWatchEnabled())) {
            return "[MANUAL_WATCH_DISABLED] Wallet is manually forced to observe-only.";
        }
        if (isObserveCooldownActive(profile)) {
            return "[OBSERVE_COOLDOWN_BLOCK] Wallet is currently in recent-degradation observe cooldown until "
                    + profile.getObserveCooldownUntil() + ". " + profile.getObserveCooldownReason();
        }
        if (!Boolean.TRUE.equals(profile.getWatchEnabled())) {
            return "Wallet is not qualified in watchlist.";
        }
        return "Wallet is not qualified in watchlist. monitorTier="
                + (StringUtils.hasText(profile.getMonitorTier()) ? profile.getMonitorTier() : "UNKNOWN") + ".";
    }

    private long resolveEffectiveSignalAgeMs(SmartWalletProperties.SignalRisk risk, WalletArchetypeView archetype) {
        long baseMs = defaultInt(risk.getMaxSignalAgeSeconds(), 300) * 1000L;
        return switch (archetype.archetype()) {
            case "HF_SCALPER" -> Math.min(baseMs, 45_000L);
            case "THEMATIC" -> Math.max(baseMs, 1_800_000L);
            case "LOW_FREQ_VALUE" -> Math.max(baseMs, 2_700_000L);
            case "SWING" -> Math.max(baseMs, 900_000L);
            default -> baseMs;
        };
    }

    private BigDecimal applyArchetypeStakeAdjustments(BigDecimal stake,
                                                      WalletArchetypeView archetype,
                                                      SmartWalletTradeEventEntity event,
                                                      List<String> reasons) {
        BigDecimal adjusted = safe(stake);
        if ("HF_SCALPER".equals(archetype.archetype()) && event != null && event.getOpenedAt() != null) {
            long ageMs = Math.max(0L, System.currentTimeMillis() - event.getOpenedAt().getTime());
            if (ageMs > 30_000L) {
                adjusted = adjusted.multiply(new BigDecimal("0.25")).setScale(2, RoundingMode.HALF_UP);
                reasons.add("[ARCHETYPE_STAKE] HF signal older than 30s, stake reduced to 25%.");
            } else if (ageMs > 15_000L) {
                adjusted = adjusted.multiply(new BigDecimal("0.50")).setScale(2, RoundingMode.HALF_UP);
                reasons.add("[ARCHETYPE_STAKE] HF signal older than 15s, stake reduced to 50%.");
            }
        }
        if ("THEMATIC".equals(archetype.archetype())
                && safe(archetype.followFitScore()).compareTo(new BigDecimal("0.65")) >= 0) {
            adjusted = adjusted.multiply(new BigDecimal("1.10"))
                    .min(properties.getMaxStakeUsdc())
                    .setScale(2, RoundingMode.HALF_UP);
            reasons.add("[ARCHETYPE_STAKE] Thematic signal with strong fit score, stake boosted by 10% within cap.");
        }
        if ("LOW_FREQ_VALUE".equals(archetype.archetype())
                && safe(archetype.followFitScore()).compareTo(new BigDecimal("0.70")) >= 0) {
            adjusted = adjusted.multiply(new BigDecimal("1.05"))
                    .min(properties.getMaxStakeUsdc())
                    .setScale(2, RoundingMode.HALF_UP);
            reasons.add("[ARCHETYPE_STAKE] Low-frequency value signal with strong fit score, stake boosted by 5% within cap.");
        }
        if ("NOISE".equals(archetype.archetype())) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return adjusted;
    }

    private BigDecimal applyBehaviorStakeAdjustments(BigDecimal stake,
                                                     WalletBehaviorProfileView behaviorProfile,
                                                     List<String> reasons) {
        BigDecimal adjusted = safe(stake);
        if (behaviorProfile == null || !StringUtils.hasText(behaviorProfile.recommendedExitLane())) {
            return adjusted;
        }
        return switch (behaviorProfile.recommendedExitLane().trim().toUpperCase()) {
            case "EARLY_TAKE_PROFIT" -> {
                BigDecimal value = adjusted.multiply(new BigDecimal("0.90")).setScale(2, RoundingMode.HALF_UP);
                reasons.add("[BEHAVIOR_STAKE] Early-take-profit wallet, stake reduced to 90% for safer replication.");
                yield value;
            }
            case "FAST_STOP_LOSS" -> {
                BigDecimal value = adjusted.multiply(new BigDecimal("0.85")).setScale(2, RoundingMode.HALF_UP);
                reasons.add("[BEHAVIOR_STAKE] Fast-stop-loss wallet, stake reduced to 85% to keep exits nimble.");
                yield value;
            }
            case "HYBRID_EARLY_EXIT" -> {
                BigDecimal value = adjusted.multiply(new BigDecimal("0.75")).setScale(2, RoundingMode.HALF_UP);
                reasons.add("[BEHAVIOR_STAKE] Hybrid early-exit wallet, stake reduced to 75%.");
                yield value;
            }
            case "HOLD_TO_SETTLEMENT" -> adjusted;
            case "FOLLOW_ARCHETYPE" -> adjusted;
            default -> adjusted;
        };
    }

    private BigDecimal applyAdvancedStakeAdjustments(BigDecimal stake,
                                                     WalletBehaviorProfileView behaviorProfile,
                                                     TradeProfitabilityScoringService.ProfitabilityEstimate profitabilityEstimate,
                                                     SmartWalletTradeEventEntity event,
                                                     SmartWalletProperties.SignalRisk risk,
                                                     List<String> reasons) {
        BigDecimal adjusted = safe(stake);
        if (adjusted.compareTo(BigDecimal.ZERO) <= 0) {
            return adjusted;
        }
        if (behaviorProfile != null && behaviorProfile.stabilityScore() != null) {
            BigDecimal stability = safe(behaviorProfile.stabilityScore());
            if (stability.compareTo(safe(risk.getStakeStabilityLowScore())) < 0) {
                adjusted = adjusted.multiply(safe(risk.getStakeStabilityLowMultiplier())).setScale(2, RoundingMode.HALF_UP);
                reasons.add("[STABILITY_STAKE] Wallet stability score is low at "
                        + stability.setScale(3, RoundingMode.HALF_UP) + ", stake reduced.");
            } else if (stability.compareTo(safe(risk.getStakeStabilityHighScore())) >= 0) {
                adjusted = adjusted.multiply(safe(risk.getStakeStabilityHighMultiplier()))
                        .min(properties.getMaxStakeUsdc())
                        .setScale(2, RoundingMode.HALF_UP);
                reasons.add("[STABILITY_STAKE] Wallet stability is strong at "
                        + stability.setScale(3, RoundingMode.HALF_UP) + ", stake modestly boosted.");
            }
        }
        BigDecimal slippageRate = adjusted.compareTo(BigDecimal.ZERO) <= 0
                ? BigDecimal.ZERO
                : safe(profitabilityEstimate.getEstimatedSlippageUsdc()).divide(adjusted, 8, RoundingMode.HALF_UP);
        if (slippageRate.compareTo(safe(risk.getStakeSlippagePenaltyStartRate())) > 0) {
            BigDecimal severity = slippageRate.subtract(safe(risk.getStakeSlippagePenaltyStartRate()))
                    .divide(new BigDecimal("0.20"), 8, RoundingMode.HALF_UP)
                    .max(BigDecimal.ZERO)
                    .min(BigDecimal.ONE);
            BigDecimal multiplier = BigDecimal.ONE.subtract(severity.multiply(new BigDecimal("0.35")))
                    .max(safe(risk.getStakePenaltyFloorMultiplier()));
            adjusted = adjusted.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
            reasons.add("[SLIPPAGE_STAKE] Estimated slippage/stake ratio is "
                    + slippageRate.multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP)
                    + "%, stake reduced.");
        }
        if (safe(profitabilityEstimate.getSlippageSpikeProbability()).compareTo(new BigDecimal("0.45")) > 0) {
            BigDecimal multiplier = BigDecimal.ONE.subtract(
                            safe(profitabilityEstimate.getSlippageSpikeProbability()).subtract(new BigDecimal("0.45"))
                                    .multiply(new BigDecimal("0.40")))
                    .max(safe(risk.getStakePenaltyFloorMultiplier()));
            adjusted = adjusted.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
            reasons.add("[SPIKE_STAKE] Slippage spike probability is elevated at "
                    + safe(profitabilityEstimate.getSlippageSpikeProbability()).setScale(3, RoundingMode.HALF_UP)
                    + ", stake reduced.");
        }
        int correlatedOpenPositions = countCorrelatedOpenPositions(event);
        if (correlatedOpenPositions > 0) {
            adjusted = adjusted.multiply(safe(risk.getStakeThemeCorrelationPenaltyMultiplier())).setScale(2, RoundingMode.HALF_UP);
            reasons.add("[THEME_CORRELATION] Found " + correlatedOpenPositions
                    + " open positions with similar market theme, stake reduced.");
        }
        return adjusted.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    private CapitalContext resolveCapitalContext() {
        BigDecimal strategyCapital = safe(accountingService.availableCapitalUsdc());
        ExecutionWalletContextLoader.ExecutionWalletContext wallet = executionWalletContextLoader.load();
        if (wallet == null || !wallet.isEnabled() || !org.springframework.util.StringUtils.hasText(wallet.getAddress())) {
            return new CapitalContext(strategyCapital, null, strategyCapital, false);
        }
        try {
            BigDecimal liveWalletBalance = safe(polymarketApiClient.fetchBalance(wallet.getAddress()));
            BigDecimal effectiveCapital = strategyCapital.min(liveWalletBalance);
            return new CapitalContext(strategyCapital, liveWalletBalance, effectiveCapital, true);
        } catch (Exception ex) {
            return new CapitalContext(strategyCapital, null, strategyCapital, false);
        }
    }

    private BigDecimal applyDynamicCapitalScale(BigDecimal stake, CapitalContext capitalContext) {
        BigDecimal normalizedStake = safe(stake);
        BigDecimal configuredBudget = safe(properties.getSignalRisk().getMaxTotalOpenCostUsdc());
        if (normalizedStake.compareTo(BigDecimal.ZERO) <= 0
                || configuredBudget.compareTo(BigDecimal.ZERO) <= 0
                || capitalContext == null) {
            return normalizedStake.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal scale = capitalContext.effectiveCapital()
                .divide(configuredBudget, 8, RoundingMode.HALF_UP)
                .min(BigDecimal.ONE)
                .max(BigDecimal.ZERO);
        return normalizedStake.multiply(scale).setScale(2, RoundingMode.HALF_UP);
    }

    private SingleWalletOverrideAssessment assessHighQualitySingleWallet(SmartWalletProfileEntity profile,
                                                                     WalletBehaviorProfileView behaviorProfile,
                                                                     SmartWalletProperties.SignalRisk risk,
                                                                     BigDecimal triggerSize,
                                                                     BigDecimal recommendedStake,
                                                                     boolean suspiciouslyPerfectWinRate,
                                                                     SmartWalletTradeEventEntity event) {
        if (!risk.isEnableSingleWalletOverride()) {
            return new SingleWalletOverrideAssessment(false,
                    "SINGLE_OVERRIDE_DISABLED",
                    "Single-wallet override strategy is disabled.");
        }
        if (profile == null) {
            return new SingleWalletOverrideAssessment(false,
                    "SINGLE_OVERRIDE_BLOCK_LOW_SCORE",
                    "Single-wallet override requires a qualified wallet profile.");
        }
        if (suspiciouslyPerfectWinRate) {
            return new SingleWalletOverrideAssessment(false,
                    "SINGLE_OVERRIDE_BLOCK_SAMPLE",
                    "Single-wallet override is blocked by unreliable leaderboard win-rate protection.");
        }
        if (profile.getObserveCooldownUntil() != null && profile.getObserveCooldownUntil().after(new Date())) {
            return new SingleWalletOverrideAssessment(false,
                    "SINGLE_OVERRIDE_BLOCK_RECENT_DEGRADATION",
                    "Wallet is in recent-degradation observe cooldown until " + profile.getObserveCooldownUntil() + ".");
        }
        if ((profile.getTotalTrades() == null ? 0 : profile.getTotalTrades()) < defaultInt(risk.getSingleWalletOverrideMinTrades(), 0)
                || (profile.getRecentTradeCount7d() == null ? 0 : profile.getRecentTradeCount7d()) < defaultInt(risk.getSingleWalletOverrideMinRecentTrades7d(), 0)) {
            return new SingleWalletOverrideAssessment(false,
                    "SINGLE_OVERRIDE_BLOCK_SAMPLE",
                    "Single-wallet override sample thresholds are not met.");
        }
        if (safe(profile.getWalletScore()).compareTo(safe(risk.getSingleWalletOverrideMinWalletScore())) < 0
                || safe(profile.getWinRate()).compareTo(safe(risk.getSingleWalletOverrideMinWinRate())) < 0
                || safe(profile.getRecentWinRate7d()).compareTo(safe(risk.getSingleWalletOverrideMinRecentWinRate7d())) < 0
                || triggerSize.compareTo(safe(risk.getSingleWalletOverrideMinTriggerSizeUsdc())) < 0
                || recommendedStake.compareTo(safe(risk.getSingleWalletOverrideMinRecommendedStakeUsdc())) < 0) {
            return new SingleWalletOverrideAssessment(false,
                    "SINGLE_OVERRIDE_BLOCK_LOW_SCORE",
                    "Single-wallet override score, recent win-rate or stake thresholds are not met.");
        }
        if (behaviorProfile == null
                || safe(behaviorProfile.replicabilityScore()).compareTo(safe(risk.getSingleWalletOverrideMinReplicabilityScore())) < 0
                || safe(behaviorProfile.qualityScore()).compareTo(safe(risk.getSingleWalletOverrideMinQualityScore())) < 0) {
            return new SingleWalletOverrideAssessment(false,
                    "SINGLE_OVERRIDE_BLOCK_BEHAVIOR",
                    "Single-wallet override behavior quality thresholds are not met.");
        }
        if (!isSingleWalletOverrideSelected(profile.getWalletAddress(), event, risk)) {
            return new SingleWalletOverrideAssessment(false,
                    "SINGLE_OVERRIDE_BLOCK_CANARY",
                    "Single-wallet override is currently gated by whitelist/canary policy.");
        }
        return new SingleWalletOverrideAssessment(true,
                "SINGLE_OVERRIDE_PASS",
                "Single-wallet override thresholds are fully satisfied.");
    }

    private List<SignalStrategyCheckResult> buildEntryStrategyChecks(SmartWalletProfileEntity profile,
                                                                     WalletBehaviorProfileView behaviorProfile,
                                                                     SmartWalletProperties.SignalRisk risk,
                                                                     BigDecimal triggerSize,
                                                                     BigDecimal recommendedStake,
                                                                     ConsensusSignalView consensus,
                                                                     boolean consensusEntityCountFailed,
                                                                     boolean consensusScoreFailed,
                                                                     String marketType,
                                                                     boolean suspiciouslyPerfectWinRate,
                                                                     SmartWalletTradeEventEntity event) {
        List<SignalStrategyCheckResult> checks = new ArrayList<>();
        checks.add(buildConsensusStrategyCheck(risk, consensus, consensusEntityCountFailed, consensusScoreFailed, marketType));
        checks.add(buildHighQualitySingleWalletCheck(
                profile,
                behaviorProfile,
                risk,
                triggerSize,
                recommendedStake,
                suspiciouslyPerfectWinRate,
                event
        ));
        return checks;
    }

    private SignalStrategyCheckResult buildConsensusStrategyCheck(SmartWalletProperties.SignalRisk risk,
                                                                  ConsensusSignalView consensus,
                                                                  boolean consensusEntityCountFailed,
                                                                  boolean consensusScoreFailed,
                                                                  String marketType) {
        List<String> reasons = new ArrayList<>();
        boolean accepted = !risk.isEnableConsensusGate() || (!consensusEntityCountFailed && !consensusScoreFailed);
        if (consensusEntityCountFailed) {
            int effectiveWindow = risk.getConsensusWindowSeconds() == null ? 180 : risk.getConsensusWindowSeconds();
            reasons.add("Consensus wallet count (deduped=" + consensus.getDedupedEntityCount()
                    + ", raw wallets=" + consensus.getDistinctWallets()
                    + ", window=" + effectiveWindow + "s) is below threshold="
                    + ("LONG_HORIZON".equals(marketType)
                    ? Math.max(risk.getMinDistinctEntities() != null ? risk.getMinDistinctEntities() : risk.getMinDistinctWallets(), 2)
                    : (risk.getMinDistinctEntities() != null ? risk.getMinDistinctEntities() : risk.getMinDistinctWallets())) + ".");
        }
        if (consensusScoreFailed) {
            reasons.add("Consensus score (" + safe(consensus.getConsensusScore()).setScale(3, RoundingMode.HALF_UP)
                    + ") is below threshold (" + safe(risk.getMinConsensusScore()).setScale(3, RoundingMode.HALF_UP) + ").");
        }
        return SignalStrategyCheckResult.builder()
                .strategyKey("CONSENSUS")
                .accepted(accepted)
                .reasons(reasons)
                .build();
    }

    private SignalStrategyCheckResult buildHighQualitySingleWalletCheck(SmartWalletProfileEntity profile,
                                                                    WalletBehaviorProfileView behaviorProfile,
                                                                    SmartWalletProperties.SignalRisk risk,
                                                                    BigDecimal triggerSize,
                                                                    BigDecimal recommendedStake,
                                                                    boolean suspiciouslyPerfectWinRate,
                                                                    SmartWalletTradeEventEntity event) {
        List<String> reasons = new ArrayList<>();
        SingleWalletOverrideAssessment assessment = assessHighQualitySingleWallet(
                profile,
                behaviorProfile,
                risk,
                triggerSize,
                recommendedStake,
                suspiciouslyPerfectWinRate,
                event
        );
        boolean accepted = assessment.qualifies();

        if (!assessment.qualifies()) {
            reasons.add("[" + assessment.reasonCode() + "] " + assessment.reasonDetail());
        }
        if (accepted) {
            reasons.add("[HIGH_QUALITY_SINGLE_WALLET_PASS] High-quality single-wallet strategy passed independently.");
        }

        return SignalStrategyCheckResult.builder()
                .strategyKey("HIGH_QUALITY_SINGLE_WALLET")
                .accepted(accepted)
                .reasons(reasons)
                .build();
    }

    private record SingleWalletOverrideAssessment(boolean qualifies,
                                                  String reasonCode,
                                                  String reasonDetail) {
    }

    private Integer countOpenPositions() {
        Long count = positionMapper.selectCount(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .in(SmartWalletPositionEntity::getStatus, List.of("OPEN", "PARTIALLY_CLOSED")));
        return count == null ? 0 : count.intValue();
    }

    private int countCorrelatedOpenPositions(SmartWalletTradeEventEntity event) {
        if (event == null || !StringUtils.hasText(event.getQuestion())) {
            return 0;
        }
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .in(SmartWalletPositionEntity::getStatus, List.of("OPEN", "PARTIALLY_CLOSED")));
        marketInfoService.populatePositions(positions);
        Set<String> currentTokens = themeTokens(event.getQuestion());
        if (currentTokens.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (SmartWalletPositionEntity position : positions) {
            if (!StringUtils.hasText(position.getQuestion())) {
                continue;
            }
            Set<String> overlap = themeTokens(position.getQuestion());
            overlap.retainAll(currentTokens);
            if (overlap.size() >= 2) {
                count++;
            }
        }
        return count;
    }

    /**
     * Common tokens that appear in many unrelated prediction markets.
     * Filtering them prevents false-positive theme correlation (e.g. "2024/will" matching all markets).
     */
    private static final Set<String> THEME_STOP_WORDS = Set.of(
            "will", "what", "when", "this", "that", "with", "from", "have", "been", "more",
            "than", "then", "does", "would", "could", "should", "their", "there", "about",
            "2024", "2025", "2026", "high", "reach", "price", "year", "make", "time", "over",
            "first", "next", "ever", "most", "some", "into", "before", "after"
    );

    private Set<String> themeTokens(String text) {
        if (!StringUtils.hasText(text)) {
            return Set.of();
        }
        return List.of(text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")).stream()
                .filter(StringUtils::hasText)
                .filter(token -> token.length() >= 4)
                .filter(token -> !THEME_STOP_WORDS.contains(token))
                .collect(Collectors.toSet());
    }

    private boolean isSingleWalletOverrideSelected(String walletAddress,
                                                   SmartWalletTradeEventEntity event,
                                                   SmartWalletProperties.SignalRisk risk) {
        String normalizedWallet = normalizeWallet(walletAddress);
        if (!StringUtils.hasText(normalizedWallet)) {
            return false;
        }
        List<String> whitelist = risk.getSingleWalletOverrideWhitelist();
        if (whitelist != null && whitelist.stream().filter(StringUtils::hasText)
                .map(this::normalizeWallet)
                .anyMatch(normalizedWallet::equals)) {
            return true;
        }
        if (!risk.isEnableSingleWalletOverrideCanary()) {
            return true;
        }
        BigDecimal rate = safe(risk.getSingleWalletOverrideCanaryRate()).max(BigDecimal.ZERO).min(BigDecimal.ONE);
        String seed = normalizedWallet + "|" + (event == null ? "" : normalizeWallet(event.getMarketId()))
                + "|" + (event == null ? "" : normalizeWallet(event.getOutcome()));
        int bucket = Math.floorMod(Objects.hash(seed), 10_000);
        int threshold = rate.multiply(new BigDecimal("10000")).setScale(0, RoundingMode.HALF_UP).intValue();
        return bucket < threshold;
    }

    private String normalizeWallet(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeMode(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : fallback;
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
        // After migration, marketId and outcome are in smart_wallet_market_info table
        // Query via marketInfoId FK instead of direct columns
        List<Long> marketInfoIds = marketInfoService.findIdsByMarketId(marketId);
        if (marketInfoIds.isEmpty()) {
            return false;
        }
        for (Long marketInfoId : marketInfoIds) {
            Long count = positionMapper.selectCount(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                    .eq(SmartWalletPositionEntity::getMarketInfoId, marketInfoId)
                    .in(SmartWalletPositionEntity::getStatus, List.of("OPEN", "PARTIALLY_CLOSED")));
            if (count != null && count > 0) {
                // Found a matching position; now verify outcome
                List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                        .eq(SmartWalletPositionEntity::getMarketInfoId, marketInfoId)
                        .in(SmartWalletPositionEntity::getStatus, List.of("OPEN", "PARTIALLY_CLOSED")));
                for (SmartWalletPositionEntity pos : positions) {
                    if (outcome.equalsIgnoreCase(pos.getOutcome())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean hasPendingFollowTask(String marketId, String outcome, Long currentFollowTaskId) {
        if (marketId == null || outcome == null) {
            return false;
        }
        // After migration, marketId and outcome are in smart_wallet_market_info table
        // Query via marketInfoId FK instead of direct columns
        Long marketInfoId = marketInfoService.findIdByMarketIdAndOutcome(marketId, outcome);
        if (marketInfoId == null) {
            return false;
        }
        LambdaQueryWrapper<SmartWalletFollowTaskEntity> query = new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                .eq(SmartWalletFollowTaskEntity::getMarketInfoId, marketInfoId)
                .in(SmartWalletFollowTaskEntity::getStatus, List.of("PENDING", "EXECUTING"));
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
        // After migration, marketId and outcome are in smart_wallet_market_info table
        // Query via marketInfoId FK instead of direct columns
        List<Long> marketInfoIds = marketInfoService.findIdsByMarketId(marketId);
        if (marketInfoIds.isEmpty()) {
            return false;
        }
        for (Long marketInfoId : marketInfoIds) {
            List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                    .eq(SmartWalletPositionEntity::getMarketInfoId, marketInfoId)
                    .in(SmartWalletPositionEntity::getStatus, List.of("OPEN", "PARTIALLY_CLOSED")));
            for (SmartWalletPositionEntity pos : positions) {
                if (!outcome.equalsIgnoreCase(pos.getOutcome())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasPendingFollowTaskOnSameMarketDifferentOutcome(String marketId, String outcome, Long currentFollowTaskId) {
        if (marketId == null || outcome == null) {
            return false;
        }
        // After migration, marketId and outcome are in smart_wallet_market_info table
        // Query via marketInfoId FK instead of direct columns
        List<Long> marketInfoIds = marketInfoService.findIdsByMarketId(marketId);
        if (marketInfoIds.isEmpty()) {
            return false;
        }
        for (Long marketInfoId : marketInfoIds) {
            LambdaQueryWrapper<SmartWalletFollowTaskEntity> query = new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                    .eq(SmartWalletFollowTaskEntity::getMarketInfoId, marketInfoId)
                    .in(SmartWalletFollowTaskEntity::getStatus, List.of("PENDING", "EXECUTING"));
            if (currentFollowTaskId != null) {
                query.ne(SmartWalletFollowTaskEntity::getId, currentFollowTaskId);
            }
            List<SmartWalletFollowTaskEntity> tasks = followTaskMapper.selectList(query);
            for (SmartWalletFollowTaskEntity task : tasks) {
                if (!outcome.equalsIgnoreCase(task.getOutcome())) {
                    return true;
                }
            }
        }
        return false;
    }

    private int defaultInt(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private record CapitalContext(BigDecimal strategyCapital,
                                  BigDecimal liveWalletBalance,
                                  BigDecimal effectiveCapital,
                                  boolean liveWalletBalanceApplied) {
    }

    private BigDecimal getExistingPositionCost(String marketId, String outcome) {
        if (marketId == null || outcome == null) {
            return BigDecimal.ZERO;
        }
        List<Long> marketInfoIds = marketInfoService.findIdsByMarketId(marketId);
        BigDecimal total = BigDecimal.ZERO;
        for (Long marketInfoId : marketInfoIds) {
            List<SmartWalletPositionEntity> positions = positionMapper.selectList(
                    new LambdaQueryWrapper<SmartWalletPositionEntity>()
                            .eq(SmartWalletPositionEntity::getMarketInfoId, marketInfoId)
                            .in(SmartWalletPositionEntity::getStatus, List.of("OPEN", "PARTIALLY_CLOSED")));
            for (SmartWalletPositionEntity pos : positions) {
                if (outcome.equalsIgnoreCase(pos.getOutcome())) {
                    total = total.add(safe(pos.getCostBasisUsdc()));
                }
            }
        }
        return total;
    }

    private BigDecimal getExistingPositionAvgPrice(String marketId, String outcome) {
        if (marketId == null || outcome == null) {
            return BigDecimal.ZERO;
        }
        List<Long> marketInfoIds = marketInfoService.findIdsByMarketId(marketId);
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalQty = BigDecimal.ZERO;
        for (Long marketInfoId : marketInfoIds) {
            List<SmartWalletPositionEntity> positions = positionMapper.selectList(
                    new LambdaQueryWrapper<SmartWalletPositionEntity>()
                            .eq(SmartWalletPositionEntity::getMarketInfoId, marketInfoId)
                            .in(SmartWalletPositionEntity::getStatus, List.of("OPEN", "PARTIALLY_CLOSED")));
            for (SmartWalletPositionEntity pos : positions) {
                if (outcome.equalsIgnoreCase(pos.getOutcome())) {
                    BigDecimal qty = safe(pos.getQuantity());
                    BigDecimal price = safe(pos.getAverageEntryPrice());
                    totalCost = totalCost.add(qty.multiply(price));
                    totalQty = totalQty.add(qty);
                }
            }
        }
        if (totalQty.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return totalCost.divide(totalQty, 8, RoundingMode.HALF_UP);
    }

    private ExistingPositionStats getExistingPositionStats(String marketId, String outcome) {
        if (marketId == null || outcome == null) {
            return ExistingPositionStats.empty();
        }
        List<Long> marketInfoIds = marketInfoService.findIdsByMarketId(marketId);
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal weightedEntry = BigDecimal.ZERO;
        List<Long> positionIds = new ArrayList<>();
        Date earliestOpenedAt = null;
        for (Long marketInfoId : marketInfoIds) {
            List<SmartWalletPositionEntity> positions = positionMapper.selectList(
                    new LambdaQueryWrapper<SmartWalletPositionEntity>()
                            .eq(SmartWalletPositionEntity::getMarketInfoId, marketInfoId)
                            .in(SmartWalletPositionEntity::getStatus, List.of("OPEN", "PARTIALLY_CLOSED")));
            for (SmartWalletPositionEntity pos : positions) {
                if (!outcome.equalsIgnoreCase(pos.getOutcome())) {
                    continue;
                }
                positionIds.add(pos.getId());
                BigDecimal qty = safe(pos.getQuantity());
                BigDecimal avgPrice = safe(pos.getAverageEntryPrice());
                totalQty = totalQty.add(qty);
                totalCost = totalCost.add(safe(pos.getCostBasisUsdc()));
                weightedEntry = weightedEntry.add(qty.multiply(avgPrice));
                if (pos.getOpenedAt() != null && (earliestOpenedAt == null || pos.getOpenedAt().before(earliestOpenedAt))) {
                    earliestOpenedAt = pos.getOpenedAt();
                }
            }
        }
        BigDecimal avgPrice = totalQty.compareTo(BigDecimal.ZERO) <= 0
                ? BigDecimal.ZERO
                : weightedEntry.divide(totalQty, 8, RoundingMode.HALF_UP);
        return new ExistingPositionStats(positionIds, totalCost, totalQty, avgPrice, earliestOpenedAt,
                existingEntrySignalKeys(positionIds));
    }

    private Set<String> existingEntrySignalKeys(List<Long> positionIds) {
        if (positionIds == null || positionIds.isEmpty()) {
            return Set.of();
        }
        List<SmartWalletOrderEntity> orders = orderMapper.selectList(new LambdaQueryWrapper<SmartWalletOrderEntity>()
                .in(SmartWalletOrderEntity::getPositionId, positionIds)
                .eq(SmartWalletOrderEntity::getSide, "BUY")
                .eq(SmartWalletOrderEntity::getOrderType, "ENTRY"));
        Set<String> keys = new java.util.HashSet<>();
        for (SmartWalletOrderEntity order : orders) {
            if (StringUtils.hasText(order.getWalletAddress())) {
                keys.add(normalizeWallet(order.getWalletAddress()));
            }
            if (order.getFollowTaskId() != null) {
                SmartWalletFollowTaskEntity task = followTaskMapper.selectById(order.getFollowTaskId());
                if (task != null && StringUtils.hasText(task.getEntityId())) {
                    keys.add(normalizeWallet(task.getEntityId()));
                }
            }
        }
        return keys;
    }

    private boolean isIndependentOrFollowOnScaleUp(ExistingPositionStats existing,
                                                   SmartWalletProfileEntity profile,
                                                   SmartWalletTradeEventEntity event) {
        String walletKey = normalizeWallet(event == null ? null : event.getWalletAddress());
        if (!StringUtils.hasText(walletKey) && profile != null) {
            walletKey = normalizeWallet(profile.getWalletAddress());
        }
        if (!StringUtils.hasText(walletKey)) {
            return false;
        }
        boolean differentSource = existing.entrySignalKeys().isEmpty()
                || existing.entrySignalKeys().stream().noneMatch(walletKey::equals);
        return differentSource || hasVerifiedSameSourceFollowOnBuy(event, existing.earliestOpenedAt());
    }

    private boolean hasVerifiedSameSourceFollowOnBuy(SmartWalletTradeEventEntity event, Date positionOpenedAt) {
        if (event == null || !StringUtils.hasText(event.getWalletAddress())
                || !StringUtils.hasText(event.getMarketId()) || !StringUtils.hasText(event.getOutcome())) {
            return false;
        }
        LambdaQueryWrapper<SmartWalletTradeEventEntity> query = new LambdaQueryWrapper<SmartWalletTradeEventEntity>()
                .eq(SmartWalletTradeEventEntity::getWalletAddress, event.getWalletAddress())
                .eq(SmartWalletTradeEventEntity::getMarketId, event.getMarketId())
                .eq(SmartWalletTradeEventEntity::getOutcome, event.getOutcome())
                .eq(SmartWalletTradeEventEntity::getSide, "BUY")
                .gt(positionOpenedAt != null, SmartWalletTradeEventEntity::getOpenedAt, positionOpenedAt);
        if (event.getId() != null) {
            query.le(SmartWalletTradeEventEntity::getId, event.getId());
        }
        Long count = tradeEventMapper.selectCount(query);
        return count != null && count > 0;
    }

    private BigDecimal scaleUpMarketOutcomeBudget(SmartWalletProperties.SignalRisk risk,
                                                  ExecutionWalletContextLoader.ExecutionWalletContext wallet) {
        BigDecimal configured = risk.getScaleUpMaxMarketOutcomeCostUsdc() == null
                || risk.getScaleUpMaxMarketOutcomeCostUsdc().compareTo(BigDecimal.ZERO) <= 0
                ? properties.getMaxStakeUsdc()
                : risk.getScaleUpMaxMarketOutcomeCostUsdc();
        BigDecimal walletCap = wallet == null ? null : wallet.getMaxOrderUsdc();
        if (walletCap != null && walletCap.compareTo(BigDecimal.ZERO) > 0) {
            return configured.min(walletCap).setScale(2, RoundingMode.HALF_UP);
        }
        return configured.setScale(2, RoundingMode.HALF_UP);
    }

    private record MarketRoutingContext(String marketType, String entryLane, String exitIntent) {
    }

    private record ExistingPositionStats(List<Long> positionIds,
                                         BigDecimal cost,
                                         BigDecimal quantity,
                                         BigDecimal avgPrice,
                                         Date earliestOpenedAt,
                                         Set<String> entrySignalKeys) {
        private static ExistingPositionStats empty() {
            return new ExistingPositionStats(List.of(), BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, null, Set.of());
        }

        private BigDecimal roiAtPrice(BigDecimal price) {
            if (cost == null || cost.compareTo(BigDecimal.ZERO) <= 0
                    || quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0
                    || price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
                return BigDecimal.ZERO;
            }
            return price.multiply(quantity).subtract(cost).divide(cost, 8, RoundingMode.HALF_UP);
        }
    }
}
