package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletManualAllowlistEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketSnapshotEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.ConsensusSignalView;
import com.metaverse.coin.strategy.job.smartwallet.model.EntryTimingDecision;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalRiskDecision;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalScoreModelResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalStrategyCheckResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalStrategyRouteResult;
import com.metaverse.coin.strategy.job.smartwallet.model.WalletArchetypeView;
import com.metaverse.coin.strategy.job.smartwallet.model.WalletBehaviorProfileView;
import com.metaverse.coin.strategy.job.strategy.application.StrategyFlowLogFormatter;
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
    private final SmartWalletManualBlocklistService manualBlocklistService;
    private final EntryTimingRiskService entryTimingRiskService;
    private final SignalScoreModelService signalScoreModelService;
    private final PolymarketFeeScheduleService feeScheduleService;
    private final MarketSnapshotCollectorService marketSnapshotCollectorService;
    private final AllowlistSignalAggregationService allowlistAggregationService;
    private final FollowExposureLimitService followExposureLimitService;
    private final FollowIntentAggregationService followIntentAggregationService;

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
        // ── 门控阶段追踪：记录信号最远到达的阶段和第一个拒绝的门控 ──
        int gateStage = 0;          // 当前已通过的最高阶段编号
        String firstRejectGate = null; // 第一个拒绝信号的门控名称
        SmartWalletManualAllowlistEntity manualAllowlist = manualAllowlistService
                .findActive(event == null ? null : event.getWalletAddress())
                .orElse(null);
        boolean manualAllowlistApplied = manualAllowlist != null;
        String signalWalletAddress = event != null && StringUtils.hasText(event.getWalletAddress())
                ? event.getWalletAddress()
                : profile == null ? null : profile.getWalletAddress();
        boolean manualBlocklistApplied = manualBlocklistService.isBlocked(signalWalletAddress);
        boolean manualWatchDisabled = profile != null && Boolean.FALSE.equals(profile.getManualWatchEnabled());
        boolean addressAdmissionRejected = false;
        // ── Gate 1: 钱包准入（手动禁用 / 观察冷却 / 白名单 / 资质） ──
        if (manualBlocklistApplied) {
            accepted = false;
            manualAllowlistApplied = false;
            addressAdmissionRejected = true;
            if (firstRejectGate == null) firstRejectGate = "G1_MANUAL_BLOCKLIST";
            reasons.add("[" + manualBlocklistService.activeReason(signalWalletAddress) + "] Wallet is blocked by smart-wallet blocklist.");
        } else if (manualWatchDisabled) {
            accepted = false;
            manualAllowlistApplied = false;
            addressAdmissionRejected = true;
            if (firstRejectGate == null) firstRejectGate = "G1_MANUAL_WATCH_DISABLED";
            reasons.add("[MANUAL_WATCH_DISABLED] Wallet is manually forced to observe-only; manual allowlist cannot override it.");
        } else if (isObserveCooldownActive(profile) && !manualAllowlistApplied) {
            accepted = false;
            addressAdmissionRejected = true;
            if (firstRejectGate == null) firstRejectGate = "G1_OBSERVE_COOLDOWN";
            reasons.add("[OBSERVE_COOLDOWN_BLOCK] Wallet is currently in recent-degradation observe cooldown until "
                    + profile.getObserveCooldownUntil() + ". " + profile.getObserveCooldownReason());
        } else if (manualAllowlistApplied) {
            if (isObserveCooldownActive(profile)) {
                reasons.add("[MANUAL_ALLOWLIST_COOLDOWN_BYPASS] Wallet is in observe cooldown until "
                        + profile.getObserveCooldownUntil()
                        + " but manual allowlist bypasses cooldown gate. Reason: " + profile.getObserveCooldownReason());
            }
            reasons.add("[MANUAL_ALLOWLIST_PASS] Wallet matched active manual allowlist. Address-profile admission checks are bypassed only.");
            if (profile == null || profile.getId() == null) {
                accepted = false;
                if (firstRejectGate == null) firstRejectGate = "G1_ALLOWLIST_PROFILE_MISSING";
                reasons.add("[MANUAL_ALLOWLIST_PROFILE_MISSING] Wallet has no profile yet. PAPER observation is allowed, but executable follow is blocked until profile metrics exist.");
            }
        }

        if (!manualAllowlistApplied && !addressAdmissionRejected && !isExecutableProfile(profile)) {
            accepted = false;
            if (firstRejectGate == null) firstRejectGate = "G1_WATCHLIST_UNQUALIFIED";
            reasons.add(watchlistBlockReason(profile));
        }
        if (accepted) gateStage = 1; // 通过钱包准入

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
        boolean paperCanaryAdmissionCandidate = isSingleWalletPaperCanaryAdmissionCandidate(profile, event, risk);
        boolean paperCanaryGateSoftened = false;
        // ── Gate 2: Archetype / Behavior 分类 ──
        if (!manualAllowlistApplied && "OBSERVE_ONLY".equalsIgnoreCase(archetype.followLane())) {
            if (paperCanaryAdmissionCandidate) {
                paperCanaryGateSoftened = true;
                reasons.add("[PAPER_CANARY_ARCHETYPE_SOFTENED] OBSERVE_ONLY archetype is allowed to proceed only for the paper canary strategy.");
            } else {
                accepted = false;
                if (firstRejectGate == null) firstRejectGate = "G2_ARCHETYPE_OBSERVE_ONLY";
                reasons.add("[ARCHETYPE_BLOCK] Wallet is currently classified as observe-only under local followability.");
            }
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
                if (firstRejectGate == null) firstRejectGate = "G2_BEHAVIOR_FAST_EXIT";
                reasons.add("[BEHAVIOR_BLOCK] Wallet relies on very fast exits or low-replicability timing. Skip follow entry.");
            }
        }
        if (accepted) gateStage = 2; // 通过 Archetype/Behavior

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
        // ── Gate 3: 触发金额 / 价格带 ──
        // 白名单地址：放宽价格/金额限制，使用更宽松的硬边界 [0.02, 0.98]，
        // 如果原价超出常规范围则 clamp 到边界，并按比例缩放 stake。
        BigDecimal allowlistMinPrice = new BigDecimal("0.02");
        BigDecimal allowlistMaxPrice = new BigDecimal("0.98");
        BigDecimal allowlistMinTriggerSizeUsdc = new BigDecimal("1");
        boolean allowlistPriceClampApplied = false;

        BigDecimal triggerSize = resolveAccumulatedTriggerSize(event);
        if (triggerSize.compareTo(safe(risk.getMinTriggerSizeUsdc())) < 0) {
            if (manualAllowlistApplied && triggerSize.compareTo(allowlistMinTriggerSizeUsdc) >= 0) {
                reasons.add("[MANUAL_ALLOWLIST_TRIGGER_SIZE_BYPASS] triggerNotionalUsdc=" + triggerSize
                        + " is below normal min=" + safe(risk.getMinTriggerSizeUsdc())
                        + " but above allowlist floor=" + allowlistMinTriggerSizeUsdc + "; gate bypassed.");
            } else {
                accepted = false;
                if (firstRejectGate == null) firstRejectGate = "G3_TRIGGER_SIZE_TOO_SMALL";
                reasons.add("[TRIGGER_SIZE_TOO_SMALL] triggerNotionalUsdc=" + triggerSize
                        + ", sizeShares=" + safe(event.getSizeShares())
                        + ", price=" + safe(event.getPrice())
                        + ", minTriggerSizeUsdc=" + (manualAllowlistApplied ? allowlistMinTriggerSizeUsdc : safe(risk.getMinTriggerSizeUsdc()))
                        + ". Polymarket raw size is shares; risk gate uses normalized USDC notional.");
            }
        }

        BigDecimal price = safe(event.getPrice());
        BigDecimal effectiveMinPrice = manualAllowlistApplied ? allowlistMinPrice : safe(risk.getMinFollowPrice());
        BigDecimal effectiveMaxPrice = manualAllowlistApplied ? allowlistMaxPrice : safe(risk.getMaxFollowPrice());
        if (price.compareTo(effectiveMinPrice) < 0 || price.compareTo(effectiveMaxPrice) > 0) {
            accepted = false;
            if (firstRejectGate == null) firstRejectGate = "G3_PRICE_OUT_OF_RANGE";
            reasons.add("Trigger price " + price + " is outside follow range [" + effectiveMinPrice + ", " + effectiveMaxPrice + "].");
        } else if (manualAllowlistApplied
                && (price.compareTo(safe(risk.getMinFollowPrice())) < 0 || price.compareTo(safe(risk.getMaxFollowPrice())) > 0)) {
            // 白名单地址在常规范围外但在宽松范围内 → clamp stake 比例
            BigDecimal normalMin = safe(risk.getMinFollowPrice());
            BigDecimal normalMax = safe(risk.getMaxFollowPrice());
            BigDecimal midPoint = normalMin.add(normalMax).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
            BigDecimal distFromMid = price.subtract(midPoint).abs();
            BigDecimal maxDist = effectiveMaxPrice.subtract(effectiveMinPrice).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
            BigDecimal normalMaxDist = normalMax.subtract(normalMin).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
            // 超出常规范围的距离比，用于缩放 stake（越远衰减越多，最低 30%）
            BigDecimal overflowRatio = maxDist.compareTo(BigDecimal.ZERO) > 0
                    ? BigDecimal.ONE.subtract(distFromMid.subtract(normalMaxDist).divide(maxDist, 4, RoundingMode.HALF_UP)).max(new BigDecimal("0.30"))
                    : BigDecimal.ONE;
            allowlistPriceClampApplied = true;
            reasons.add("[MANUAL_ALLOWLIST_PRICE_CLAMP] Price " + price
                    + " is outside normal range [" + normalMin + ", " + normalMax + "]"
                    + " but within allowlist range [" + effectiveMinPrice + ", " + effectiveMaxPrice + "]."
                    + " Stake will be scaled by " + overflowRatio.setScale(4, RoundingMode.HALF_UP) + ".");
        }
        if (accepted) gateStage = 3; // 通过触发金额/价格带

        // ── Gate 4: 组合约束（持仓数 / 总成本 / 可用资金）──
        Integer openPositions = countOpenPositions();
        if (openPositions >= defaultInt(risk.getMaxOpenPositions(), Integer.MAX_VALUE)) {
            accepted = false;
            if (firstRejectGate == null) firstRejectGate = "G4_MAX_OPEN_POSITIONS";
            reasons.add("Open position count has reached the portfolio limit.");
        }

        BigDecimal totalOpenCost = totalOpenCostUsdc();
        if (totalOpenCost.compareTo(safe(risk.getMaxTotalOpenCostUsdc())) >= 0) {
            accepted = false;
            if (firstRejectGate == null) firstRejectGate = "G4_MAX_TOTAL_OPEN_COST";
            reasons.add("Total open position cost has reached the portfolio budget limit.");
        }

        CapitalContext capitalContext = resolveCapitalContext();
        BigDecimal availableCapital = capitalContext.effectiveCapital();
        if (availableCapital.compareTo(BigDecimal.ZERO) <= 0) {
            accepted = false;
            if (firstRejectGate == null) firstRejectGate = "G4_CAPITAL_DEPLETED";
            reasons.add("Available capital is depleted. Strategy is paused to avoid bankroll penetration.");
        }
        if (accepted) gateStage = 4; // 通过组合约束
        if (capitalContext.liveWalletBalanceApplied()) {
            reasons.add("Live wallet balance sizing applied. liveBalanceUsdc="
                    + capitalContext.liveWalletBalance().setScale(2, RoundingMode.HALF_UP)
                    + ", effectiveCapitalUsdc="
                    + capitalContext.effectiveCapital().setScale(2, RoundingMode.HALF_UP) + ".");
        }

        // ── Gate 5: 信号新鲜度 ──
        if (event.getOpenedAt() != null) {
            long ageMs = Math.max(0L, System.currentTimeMillis() - event.getOpenedAt().getTime());
            long effectiveMaxSignalAgeMs = resolveEffectiveSignalAgeMs(risk, archetype);
            if (ageMs > effectiveMaxSignalAgeMs) {
                long paperCanaryMaxAgeMs = defaultInt(risk.getPaperCanaryMaxSignalAgeSeconds(), 0) * 1000L;
                if (paperCanaryAdmissionCandidate && paperCanaryMaxAgeMs > 0 && ageMs <= paperCanaryMaxAgeMs) {
                    paperCanaryGateSoftened = true;
                    reasons.add("[PAPER_CANARY_STALE_SOFTENED] Signal age=" + (ageMs / 1000)
                            + "s exceeds lane max=" + (effectiveMaxSignalAgeMs / 1000)
                            + "s but remains within paper canary max=" + (paperCanaryMaxAgeMs / 1000) + "s.");
                } else {
                    accepted = false;
                    if (firstRejectGate == null) firstRejectGate = "G5_SIGNAL_STALE(age=" + (ageMs / 1000) + "s,max=" + (effectiveMaxSignalAgeMs / 1000) + "s)";
                    reasons.add("Signal is stale for lane " + archetype.followLane() + ".");
                }
            }
        }
        if (accepted) gateStage = 5; // 通过信号新鲜度

        // ── Gate 6: 市场状态 / 时间窗口 ──
        SmartWalletMarketStateService.MarketStateSnapshot marketState = marketStateService.refreshMarket(
                event.getMarketId(),
                event.getQuestion(),
                StringUtils.hasText(event.getOutcome()) ? List.of(event.getOutcome()) : List.of()
        );
        if (!marketState.isActive()) {
            accepted = false;
            if (firstRejectGate == null) firstRejectGate = "G6_MARKET_NOT_ACTIVE";
            reasons.add(marketState.blockingReasonMessage());
        }
        Long endAt = marketState.marketEndAt();
        if (endAt != null && endAt > 0) {
            long remainingMs = endAt - System.currentTimeMillis();
            if (remainingMs <= 0) {
                accepted = false;
                if (firstRejectGate == null) firstRejectGate = "G6_MARKET_EXPIRED";
                reasons.add("Market is already at or past expected end time.");
            } else {
                long maxHours = defaultInt(risk.getMaxHoursToMarketEnd(), Integer.MAX_VALUE);
                long obviousFutureMs = (long) risk.getObviouslyFutureMarketEndDays() * 24L * 3600_000L;
                if (remainingMs > obviousFutureMs) {
                    accepted = false;
                    if (firstRejectGate == null) firstRejectGate = "G6_MARKET_END_DATA_QUALITY";
                    reasons.add("[MARKET_END_AT_DATA_QUALITY] Market end time is unexpectedly far in the future; verify metadata before following.");
                } else if (remainingMs > maxHours * 3600_000L) {
                    accepted = false;
                    if (firstRejectGate == null) firstRejectGate = "G6_MARKET_END_TOO_FAR";
                    reasons.add("Market end time is too far away for the current capital-turnover policy.");
                }
            }
        } else if (accepted) {
            // marketEndAt 为空时，强制 forceRefresh 再次确认市场是否仍然活跃
            SmartWalletMarketStateService.MarketStateSnapshot freshState = marketStateService.refreshMarket(
                    event.getMarketId(),
                    event.getQuestion(),
                    StringUtils.hasText(event.getOutcome()) ? List.of(event.getOutcome()) : List.of(),
                    true // forceRefresh
            );
            if (!freshState.isActive()) {
                accepted = false;
                if (firstRejectGate == null) firstRejectGate = "G6_MARKET_NOT_ACTIVE_AFTER_FORCE_REFRESH";
                reasons.add("[MARKET_END_AT_MISSING_FORCE_REFRESH] marketEndAt is null; forced refresh shows market is no longer active: " + freshState.blockingReasonMessage());
            }
            Long freshEndAt = freshState.marketEndAt();
            if (freshEndAt != null && freshEndAt > 0 && freshEndAt <= System.currentTimeMillis()) {
                accepted = false;
                if (firstRejectGate == null) firstRejectGate = "G6_MARKET_EXPIRED_AFTER_FORCE_REFRESH";
                reasons.add("[MARKET_END_AT_MISSING_FORCE_REFRESH] marketEndAt was null; forced refresh reveals market is already expired.");
                endAt = freshEndAt;
            } else if (freshEndAt != null && freshEndAt > 0) {
                endAt = freshEndAt;
            }
        }
        if (accepted) gateStage = 6; // 通过市场状态

        boolean hasOppositeOpenPosition = hasOpenPositionOnSameMarketDifferentOutcome(event.getMarketId(), event.getOutcome());
        MarketRoutingContext marketRouting = classifyMarket(event, marketState, endAt, risk, hasOppositeOpenPosition);
        String marketType = marketRouting.marketType();
        String entryLane = marketRouting.entryLane();
        String exitIntent = marketRouting.exitIntent();
        reasons.add("[MARKET_TYPE] marketType=" + marketType + ", entryLane=" + entryLane
                + (StringUtils.hasText(exitIntent) ? ", exitIntent=" + exitIntent : "") + ".");
        // ── Gate 7: 方向 / 市场类型 / 重复持仓 ──
        if (!risk.isAllowSellSignal() && "SELL".equalsIgnoreCase(event.getSide())
                && !"DYNAMIC_FOLLOW_EXIT".equals(entryLane)) {
            accepted = false;
            if (firstRejectGate == null) firstRejectGate = "G7_SELL_DISABLED";
            reasons.add("SELL signals are disabled by risk policy.");
        }
        if ("FAST_EVENT".equals(marketType)
                && !"DYNAMIC_FOLLOW_EXIT".equals(entryLane)) {
            accepted = false;
            if (firstRejectGate == null) firstRejectGate = "G7_FAST_EVENT_BLOCKED";
            reasons.add("[FAST_EVENT_ENTRY_BLOCK] Fast-event markets are blocked for new entry; only dynamic follow-exit signals are allowed."
                    + (manualAllowlistApplied ? " [MANUAL_ALLOWLIST_FAST_EVENT_NOTE] Allowlist wallet also blocked — API rate-limit constraints prevent fast-event tracking." : ""));
        }

        boolean hasDuplicatePosition = risk.isBlockDuplicateOpenPosition()
                && hasOpenPosition(event.getMarketId(), event.getOutcome());

        if (risk.isBlockOppositeOpenPositionSameMarket()
                && hasOppositeOpenPosition
                && !"DYNAMIC_FOLLOW_EXIT".equals(entryLane)) {
            accepted = false;
            if (firstRejectGate == null) firstRejectGate = "G7_OPPOSITE_POSITION_EXISTS";
            reasons.add("An open position already exists for the same market with a different outcome.");
        }

        if (risk.isBlockDuplicatePendingTask()
                && hasPendingFollowTask(event.getMarketId(), event.getOutcome(), currentFollowTaskId)
                && !"DYNAMIC_FOLLOW_EXIT".equals(entryLane)) {
            // P0-2: 如果意图聚合已启用且在同一聚合窗口内，不拦截（走聚合合并路径）
            boolean withinIntentWindow = false;
            try {
                SmartWalletProperties.IntentAggregationConfig aggConfig = properties.getIntentAggregation();
                if (aggConfig != null && aggConfig.isEnabled()) {
                    Date eventTime = event.getOpenedAt() != null ? event.getOpenedAt() : new Date();
                    withinIntentWindow = followIntentAggregationService.isWithinActiveIntentWindow(
                            event.getMarketId(), event.getOutcome(), event.getSide(),
                            event.getWalletAddress(), eventTime);
                }
            } catch (Exception e) {
                log.debug("{} action=意图聚合窗口判定异常 eventId={} error={}", LOG_PREFIX, event.getId(), e.getMessage());
            }

            if (!withinIntentWindow) {
                accepted = false;
                if (firstRejectGate == null) firstRejectGate = "G7_DUPLICATE_PENDING_TASK";
                reasons.add("A pending follow task already exists for the same market and outcome.");
            } else {
                reasons.add("[INTENT_AGGREGATION_BYPASS] Duplicate pending task exists but within intent aggregation window — allowing merge.");
            }
        }

        if (risk.isBlockOppositePendingTaskSameMarket()
                && hasPendingFollowTaskOnSameMarketDifferentOutcome(event.getMarketId(), event.getOutcome(), currentFollowTaskId)
                && !"DYNAMIC_FOLLOW_EXIT".equals(entryLane)) {
            accepted = false;
            if (firstRejectGate == null) firstRejectGate = "G7_OPPOSITE_PENDING_TASK";
            reasons.add("A pending follow task already exists for the same market with a different outcome.");
        }
        if (risk.isBlockMarketOutcomeOrderLimit() && !"DYNAMIC_FOLLOW_EXIT".equals(entryLane)) {
            int maxOrders = defaultInt(risk.getMaxOrdersPerMarketOutcome(), 3);
            int entryOrderCount = countEntryOrdersOnMarketOutcome(event.getMarketId(), event.getOutcome());
            int pendingTaskCount = countPendingFollowTasksOnMarketOutcome(event.getMarketId(), event.getOutcome(), currentFollowTaskId);
            int totalOrderSlots = entryOrderCount + pendingTaskCount;
            if (totalOrderSlots >= maxOrders) {
                accepted = false;
                if (firstRejectGate == null) firstRejectGate = "G7_MARKET_OUTCOME_ORDER_LIMIT";
                reasons.add("[MARKET_OUTCOME_ORDER_LIMIT_BLOCK] marketId=" + event.getMarketId()
                        + ", outcome=" + event.getOutcome()
                        + ", entryOrders=" + entryOrderCount
                        + ", pendingTasks=" + pendingTaskCount
                        + ", maxOrdersPerMarketOutcome=" + maxOrders + ".");
                log.info("{}", flow("S5_RISK_EVALUATED", "同市场同预测订单上限", "拒绝", event,
                        "entryLane", entryLane,
                        "entryOrderCount", entryOrderCount,
                        "pendingTaskCount", pendingTaskCount,
                        "maxOrdersPerMarketOutcome", maxOrders,
                        "firstRejectGate", "G7_MARKET_OUTCOME_ORDER_LIMIT",
                        "blockerCategories", "ORDER_LIMIT",
                        "reasonSummary", "同一 marketId + outcome 的入场订单/待执行任务数量已达到上限"));
            }
        }
        if (risk.isRequireLeaderProfitableActivePosition()
                && !"DYNAMIC_FOLLOW_EXIT".equals(entryLane)
                && "BUY".equalsIgnoreCase(event.getSide())) {
            if (manualAllowlistApplied) {
                // Allowlist wallets: only check PnL on existing positions that have had time to accumulate profit data.
                // New BUY entries (leader just opened or has no matched position) skip the PnL gate.
                LeaderPositionCheck leaderPositionCheck = checkLeaderProfitableActivePosition(event);
                boolean isNewEntry = leaderPositionCheck.reason() != null
                        && leaderPositionCheck.reason().contains("no active position");
                if (isNewEntry) {
                    reasons.add("[MANUAL_ALLOWLIST_LEADER_PNL_BYPASS] Leader has no active position for this market/outcome — "
                            + "treated as new entry, PnL gate skipped for allowlist wallet.");
                } else if (!leaderPositionCheck.accepted()) {
                    // Existing position with non-positive PnL — still allow but log a warning
                    reasons.add("[MANUAL_ALLOWLIST_LEADER_PNL_WARN] " + leaderPositionCheck.reason()
                            + " — allowlist wallet bypasses rejection but leader position is not profitable.");
                    log.warn("[MANUAL_ALLOWLIST_LEADER_PNL_WARN] wallet={} marketId={} outcome={} leaderPnlCheck={}",
                            event.getWalletAddress(), event.getMarketId(), event.getOutcome(), leaderPositionCheck.reason());
                } else {
                    reasons.add(leaderPositionCheck.reason());
                }
            } else {
                LeaderPositionCheck leaderPositionCheck = checkLeaderProfitableActivePosition(event);
                reasons.add(leaderPositionCheck.reason());
                if (!leaderPositionCheck.accepted()) {
                    accepted = false;
                    if (firstRejectGate == null) firstRejectGate = "G7_LEADER_POSITION_NOT_PROFITABLE";
                }
            }
        }
        if (accepted) gateStage = 7; // 通过方向/重复检查

        EntryTimingDecision entryTimingDecision = entryTimingRiskService.evaluate(event, risk, marketType);
        if (entryTimingDecision != null && entryTimingDecision.getReasons() != null) {
            reasons.addAll(entryTimingDecision.getReasons());
        }
        if (entryTimingDecision != null && !entryTimingDecision.isAccepted()
                && !"DYNAMIC_FOLLOW_EXIT".equals(entryLane)) {
            accepted = false;
            if (firstRejectGate == null) {
                firstRejectGate = "G7_ENTRY_TIMING";
            }
        }
        if (entryTimingDecision != null && risk.isEnableEntryTimingGate()) {
            log.info("{}", flow("S5_RISK_EVALUATED", "入场时机风控",
                    entryTimingDecision.isAccepted() ? "通过" : "拒绝", event,
                    "entryLane", entryLane,
                    "marketType", marketType,
                    "currentEntryPrice", entryTimingDecision.getCurrentEntryPrice(),
                    "driftRate", entryTimingDecision.getDriftRate(),
                    "allowedDriftRate", entryTimingDecision.getAllowedDriftRate(),
                    "spreadPct", entryTimingDecision.getSpreadPct(),
                    "firstRejectGate", entryTimingDecision.isAccepted() ? null : "G7_ENTRY_TIMING",
                    "blockerCategories", entryTimingDecision.isAccepted() ? null : "ENTRY_TIMING",
                    "reasonSummary", summarizeReasons(entryTimingDecision.getReasons())));
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
            int minEntities = resolveMinConsensusEntities(risk);
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
        BigDecimal capitalRatioCap = risk.getMaxSingleOrderCapitalRatio() == null
                ? BigDecimal.ZERO
                : safe(risk.getMaxSingleOrderCapitalRatio());
        if (capitalRatioCap.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal capitalScaledCap = availableCapital.max(BigDecimal.ZERO)
                    .multiply(capitalRatioCap)
                    .setScale(2, RoundingMode.HALF_UP);
            if (capitalScaledCap.compareTo(BigDecimal.ZERO) > 0 && preliminaryStake.compareTo(capitalScaledCap) > 0) {
                reasons.add("[CAPITAL_RATIO_STAKE_CAP] Stake capped from " + preliminaryStake
                        + " to " + capitalScaledCap
                        + " by maxSingleOrderCapitalRatio=" + capitalRatioCap + ".");
                preliminaryStake = capitalScaledCap;
            }
        }
        BigDecimal triggerRatioCap = risk.getMaxStakeToTriggerNotionalRatio() == null
                ? BigDecimal.ZERO
                : safe(risk.getMaxStakeToTriggerNotionalRatio());
        if (triggerRatioCap.compareTo(BigDecimal.ZERO) > 0 && triggerSize.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal triggerScaledCap = triggerSize.multiply(triggerRatioCap).setScale(2, RoundingMode.HALF_UP);
            if (triggerScaledCap.compareTo(BigDecimal.ZERO) > 0 && preliminaryStake.compareTo(triggerScaledCap) > 0) {
                reasons.add("[TRIGGER_RATIO_STAKE_CAP] Stake capped from " + preliminaryStake
                        + " to " + triggerScaledCap
                        + " by maxStakeToTriggerNotionalRatio=" + triggerRatioCap + ".");
                preliminaryStake = triggerScaledCap;
            }
        }
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
        // ── Gate 8: 最低下单额检查（初步仓位）──
        if (preliminaryStake.compareTo(minOrderUsdc) < 0) {
            reasons.add("[PRELIMINARY_STAKE_BELOW_MIN] Preliminary stake=" + preliminaryStake
                    + " is below minOrder=" + minOrderUsdc
                    + "; final min-order enforcement is deferred until final stake is computed.");
        }
        if (accepted) gateStage = 8; // 通过初步仓位检查

        // ── Gate 8b: Taker Fee 预扣减 ──────────────────────────────────────────
        SmartWalletProperties.FeeSchedule feeSchedule = properties.getFeeSchedule();
        if (feeSchedule != null && feeSchedule.isEnablePreTradeFeeDeduction()) {
            BigDecimal takerFeeRate = feeScheduleService.getTakerFeeRate(
                    event.getMarketId(), resolveMarketCategory(event));
            if (takerFeeRate.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal preFeStake = preliminaryStake;
                preliminaryStake = feeScheduleService.adjustStakeForFee(
                        preliminaryStake, event.getMarketId(), resolveMarketCategory(event));
                BigDecimal estimatedFee = preFeStake.subtract(preliminaryStake);
                reasons.add("[FEE_ADJUSTED] Stake adjusted from " + preFeStake
                        + " to " + preliminaryStake
                        + " after taker fee " + takerFeeRate.setScale(4, RoundingMode.HALF_UP)
                        + " (estimated fee=" + estimatedFee.setScale(4, RoundingMode.HALF_UP) + " USDC).");
            }
        }

        // ── Gate 8c: Orderbook 深度感知仓位调整 ──────────────────────────────────
        if (risk.isEnableDepthAwareSizing() && accepted) {
            BigDecimal depthUsdc = resolveAskDepth(event, risk);
            if (depthUsdc != null && depthUsdc.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal minDepth = safe(risk.getMinAcceptableDepthUsdc());
                if (depthUsdc.compareTo(minDepth) < 0) {
                    accepted = false;
                    if (firstRejectGate == null) firstRejectGate = "G8c_DEPTH_INSUFFICIENT";
                    reasons.add("[DEPTH_INSUFFICIENT] Orderbook ask depth=" + depthUsdc.setScale(2, RoundingMode.HALF_UP)
                            + " USDC is below minimum acceptable depth=" + minDepth.setScale(2, RoundingMode.HALF_UP)
                            + " USDC. Refusing entry to avoid sweeping thin book.");
                } else {
                    BigDecimal maxConsumption = safe(risk.getDepthConsumptionMaxRate());
                    BigDecimal depthCap = depthUsdc.multiply(maxConsumption).setScale(2, RoundingMode.HALF_UP);
                    if (preliminaryStake.compareTo(depthCap) > 0) {
                        reasons.add("[DEPTH_DOWNSIZE] Stake reduced from " + preliminaryStake
                                + " to " + depthCap
                                + " (depth=" + depthUsdc.setScale(2, RoundingMode.HALF_UP)
                                + " × maxConsumption=" + maxConsumption + ").");
                        preliminaryStake = depthCap;
                        if (preliminaryStake.compareTo(minOrderUsdc) < 0) {
                            accepted = false;
                            if (firstRejectGate == null) firstRejectGate = "G8c_DEPTH_DOWNSIZE_BELOW_MIN";
                            reasons.add("[DEPTH_DOWNSIZE_BELOW_MIN] After depth-aware downsizing, stake="
                                    + preliminaryStake + " is below minOrder=" + minOrderUsdc + ".");
                        }
                    } else {
                        reasons.add("[DEPTH_OK] Orderbook depth=" + depthUsdc.setScale(2, RoundingMode.HALF_UP)
                                + " USDC is sufficient for stake=" + preliminaryStake + ".");
                    }
                }
            }
            // depthUsdc == null → 快照不可用，不阻断（fail-open），留给 slippage gate 兜底
        }

        // ── Gate 9: 集中度门控（防控盘）──────────────────────────────────────────
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
                    // Gate 9a：极端集中市场优先处理，避免和普通高集中共用同一套 downsizing
                    if (snap.isExtremelyConcentrated()) {
                        String mode = normalizeMode(risk.getExtremeConcentrationMode(), "DOWNSIZE");
                        switch (mode) {
                            case "BLOCK" -> {
                                accepted = false;
                                if (firstRejectGate == null) firstRejectGate = "G9_EXTREME_CONCENTRATION_BLOCK";
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
                        // Gate 9b：普通高集中市场 → 根据模式选择 BLOCK / DOWNSIZE / WARN_ONLY
                        String mode = normalizeMode(risk.getConcentrationHighRiskMode(), "DOWNSIZE");
                        switch (mode) {
                            case "BLOCK" -> {
                                accepted = false;
                                if (firstRejectGate == null) firstRejectGate = "G9_HIGH_CONCENTRATION_BLOCK";
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

                    // Gate 9c：信号源钱包本身是鲸鱼
                    String triggerWallet = event.getWalletAddress();
                    if (org.springframework.util.StringUtils.hasText(triggerWallet)
                            && snap.topHolderAddresses().stream()
                                    .anyMatch(addr -> addr.equalsIgnoreCase(triggerWallet))) {
                        String mode = risk.getWhaleSignalSourceMode() == null ? "BLOCK" : risk.getWhaleSignalSourceMode().toUpperCase();
                        switch (mode) {
                            case "BLOCK" -> {
                                accepted = false;
                                if (firstRejectGate == null) firstRejectGate = "G9_WHALE_SIGNAL_BLOCK";
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
        if (accepted) gateStage = 9; // 通过集中度门控

        TradeProfitabilityScoringService.ProfitabilityEstimate profitabilityEstimate =
                tradeProfitabilityScoringService.estimate(profile, event, consensus, preliminaryStake);

        // ── Gate 10: Kelly 仓位计算 ─────────────────────────────────────────────
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
                if (firstRejectGate == null) firstRejectGate = "G10_KELLY_NEGATIVE_EV(p=" + safe(profitabilityEstimate.getCalibratedFollowProbability()).setScale(4, RoundingMode.HALF_UP) + ",price=" + safe(event.getPrice()).setScale(4, RoundingMode.HALF_UP) + ")";
                recommendedStake = BigDecimal.ZERO;
                reasons.add("Kelly stake: negative EV, signal rejected.");
                log.warn("[KELLY_NEGATIVE_EV] wallet={} marketId={} outcome={} price={} calibratedP={} side={} triggerSizeUsdc={} allowlisted={}",
                        event.getWalletAddress(), event.getMarketId(), event.getOutcome(),
                        safe(event.getPrice()).setScale(4, RoundingMode.HALF_UP),
                        safe(profitabilityEstimate.getCalibratedFollowProbability()).setScale(4, RoundingMode.HALF_UP),
                        event.getSide(), safe(event.getSizeUsdc()).setScale(2, RoundingMode.HALF_UP),
                        manualAllowlistApplied);
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
            if (allowlistPriceClampApplied) {
                BigDecimal normalMin = safe(risk.getMinFollowPrice());
                BigDecimal normalMax = safe(risk.getMaxFollowPrice());
                BigDecimal midPoint = normalMin.add(normalMax).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
                BigDecimal distFromMid = price.subtract(midPoint).abs();
                BigDecimal maxDist = effectiveMaxPrice.subtract(effectiveMinPrice).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
                BigDecimal normalMaxDist = normalMax.subtract(normalMin).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
                BigDecimal overflowRatio = maxDist.compareTo(BigDecimal.ZERO) > 0
                        ? BigDecimal.ONE.subtract(distFromMid.subtract(normalMaxDist).divide(maxDist, 4, RoundingMode.HALF_UP)).max(new BigDecimal("0.30"))
                        : BigDecimal.ONE;
                recommendedStake = recommendedStake.multiply(overflowRatio).setScale(2, RoundingMode.HALF_UP);
                reasons.add("[MANUAL_ALLOWLIST_PRICE_CLAMP_STAKE] Stake scaled by " + overflowRatio.setScale(4, RoundingMode.HALF_UP)
                        + " due to price " + price + " outside normal range.");
            }
        }
        BigDecimal triggerStakeCapRatio = safe(risk.getMaxStakeToTriggerNotionalRatio());
        if (manualAllowlistApplied) {
            triggerStakeCapRatio = BigDecimal.ONE; // Allowlist wallets use 1.0 ratio
            reasons.add("[MANUAL_ALLOWLIST_TRIGGER_RATIO_OVERRIDE] maxStakeToTriggerNotionalRatio overridden from "
                    + safe(risk.getMaxStakeToTriggerNotionalRatio()) + " to 1.0 for allowlist wallet.");
        }
        if (triggerStakeCapRatio.compareTo(BigDecimal.ZERO) > 0 && triggerSize.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal triggerStakeCap = triggerSize.multiply(triggerStakeCapRatio).setScale(2, RoundingMode.HALF_UP);
            if (recommendedStake.compareTo(triggerStakeCap) > 0) {
                reasons.add("[TRIGGER_NOTIONAL_STAKE_CAP] recommendedStake=" + recommendedStake
                        + " capped to " + triggerStakeCap
                        + " by triggerNotionalUsdc=" + triggerSize
                        + ", maxStakeToTriggerNotionalRatio=" + triggerStakeCapRatio + ".");
                recommendedStake = triggerStakeCap;
            }
        }
        if ((paperCanaryGateSoftened || consensusEntityCountFailed || consensusScoreFailed)
                && isPaperCanaryStakeFloorEligible(paperCanaryAdmissionCandidate, risk, recommendedStake, profitabilityEstimate)) {
            BigDecimal canaryStake = safe(risk.getPaperCanaryStakeUsdc()).max(minOrderUsdc);
            if (recommendedStake.compareTo(canaryStake) < 0) {
                recommendedStake = canaryStake
                        .min(properties.getMaxStakeUsdc())
                        .min(availableCapital.max(BigDecimal.ZERO))
                        .setScale(2, RoundingMode.HALF_UP);
                reasons.add("[PAPER_CANARY_STAKE_FLOOR] Raised positive trial stake to paper canary floor="
                        + recommendedStake + ".");
            }
        }
        // ── Gate 10a: 白名单小额高频聚合 + 固定最低保底 ──
        // 当白名单地址等比缩放后 stake < minOrder 时，尝试聚合或固定保底
        if (accepted && manualAllowlistApplied && recommendedStake.compareTo(minOrderUsdc) < 0) {
            SmartWalletProperties.AllowlistAggregationConfig aggConfig = properties.getAllowlistAggregation();
            if (aggConfig != null && aggConfig.isEnabled()) {
                AllowlistSignalAggregationService.AggregationResult aggResult =
                        allowlistAggregationService.aggregate(
                                event.getMarketId(), event.getOutcome(), event.getWalletAddress(),
                                recommendedStake, triggerSize, price);
                if (aggResult.getAction() == AllowlistSignalAggregationService.AggregationAction.EXECUTE_NOW) {
                    recommendedStake = aggResult.getExecuteStake()
                            .min(properties.getMaxStakeUsdc())
                            .min(availableCapital.max(BigDecimal.ZERO))
                            .setScale(2, RoundingMode.HALF_UP);
                    reasons.add("[ALLOWLIST_AGGREGATION_EXECUTE] " + aggResult.getReason()
                            + " executeStake=" + recommendedStake + ".");
                } else if (aggResult.getAction() == AllowlistSignalAggregationService.AggregationAction.BUFFERED) {
                    // 信号已缓冲，暂不下单，但不算拒绝（信号被"暂存"而非丢弃）
                    accepted = false;
                    if (firstRejectGate == null) firstRejectGate = "G10a_ALLOWLIST_SIGNAL_BUFFERED";
                    reasons.add("[ALLOWLIST_SIGNAL_BUFFERED] " + aggResult.getReason()
                            + ". Signal accumulated, will execute when threshold reached.");
                }
            }
        }
        // ── Gate 10b: 常规最低下单额检查 ──
        if (accepted && recommendedStake.compareTo(minOrderUsdc) < 0) {
            BigDecimal flooredStake = minOrderUsdc
                    .min(properties.getMaxStakeUsdc())
                    .min(availableCapital.max(BigDecimal.ZERO))
                    .setScale(2, RoundingMode.HALF_UP);
            if (flooredStake.compareTo(minOrderUsdc) >= 0) {
                recommendedStake = flooredStake;
                reasons.add("[MIN_ORDER_STAKE_FLOOR] Raised positive accepted stake to execution wallet minimum order="
                        + recommendedStake + ".");
            } else {
                accepted = false;
                if (firstRejectGate == null) firstRejectGate = "G10b_FINAL_STAKE_BELOW_MIN(stake=" + recommendedStake + ")";
                reasons.add("Recommended stake is below execution wallet minimum order after capital scaling.");
            }
        }
        // ── Gate 10c: 暴露上限检查 (单市场/单地址/总TVL) ──
        FollowExposureLimitService.ExposureCheckResult lastExposureResult = null;
        if (accepted) {
            SmartWalletProperties.ExposureLimitConfig exposureConfig = properties.getExposureLimit();
            if (exposureConfig != null && exposureConfig.isEnabled()) {
                FollowExposureLimitService.ExposureCheckResult exposureResult =
                        followExposureLimitService.check(
                                event.getMarketId(), event.getOutcome(), event.getWalletAddress(),
                                recommendedStake, availableCapital);
                lastExposureResult = exposureResult;
                if (!exposureResult.isAllowed()) {
                    accepted = false;
                    if (firstRejectGate == null) firstRejectGate = "G10c_EXPOSURE_LIMIT(" + exposureResult.getReason() + ")";
                    reasons.add("[EXPOSURE_LIMIT_BLOCK] " + exposureResult.getReason()
                            + " marketExposure=" + safe(exposureResult.getMarketExposure()).setScale(2, RoundingMode.HALF_UP)
                            + " walletExposure=" + safe(exposureResult.getWalletExposure()).setScale(2, RoundingMode.HALF_UP)
                            + " totalExposure=" + safe(exposureResult.getTotalExposure()).setScale(2, RoundingMode.HALF_UP));
                } else if (exposureResult.isClipped()) {
                    BigDecimal clippedStake = exposureResult.getClippedStake();
                    if (clippedStake.compareTo(minOrderUsdc) >= 0) {
                        recommendedStake = clippedStake;
                        reasons.add("[EXPOSURE_CLIPPED] Stake reduced from " + recommendedStake
                                + " to " + clippedStake + " by exposure limits.");
                    } else {
                        accepted = false;
                        if (firstRejectGate == null) firstRejectGate = "G10c_EXPOSURE_CLIP_BELOW_MIN(stake=" + clippedStake + ")";
                        reasons.add("[EXPOSURE_CLIP_BELOW_MIN] Clipped stake=" + clippedStake
                                + " is below minOrder=" + minOrderUsdc + ".");
                    }
                }
            }
        }
        if (accepted) gateStage = 10; // 通过 Kelly / 仓位计算 + 聚合 + 暴露上限

        if (profitabilityEstimate.getEstimateSampleSize() != null && profitabilityEstimate.getEstimateSampleSize() > 0) {
            reasons.add("Local risk estimate sample size=" + profitabilityEstimate.getEstimateSampleSize() + ".");
        }
        reasons.add("Model follow probability=" + safe(profitabilityEstimate.getCalibratedFollowProbability()).setScale(4, RoundingMode.HALF_UP) + ".");
        reasons.add("Model slippage spike probability=" + safe(profitabilityEstimate.getSlippageSpikeProbability()).setScale(4, RoundingMode.HALF_UP) + ".");
        // ── Gate 11: 模型门控（盈利性 / 概率 / 滑点）──
        if (risk.isEnableProfitabilityGate()
                && defaultInt(risk.getProfitabilityMinSampleSize(), 0) > 0
                && defaultInt(profitabilityEstimate.getEstimateSampleSize(), 0) >= defaultInt(risk.getProfitabilityMinSampleSize(), 0)
                && safe(profitabilityEstimate.getEstimatedNetProfitabilityUsdc()).compareTo(safe(risk.getMinEstimatedNetProfitabilityUsdc())) < 0) {
            accepted = false;
            if (firstRejectGate == null) firstRejectGate = "G11_PROFITABILITY_TOO_LOW";
            reasons.add("Local historical net profitability estimate is below threshold.");
        }
        if (risk.isEnableProbabilityGate()
                && safe(profitabilityEstimate.getCalibratedFollowProbability()).compareTo(safe(risk.getMinCalibratedFollowProbability())) < 0) {
            accepted = false;
            if (firstRejectGate == null) firstRejectGate = "G11_PROBABILITY_BELOW_THRESHOLD(p=" + safe(profitabilityEstimate.getCalibratedFollowProbability()).setScale(4, RoundingMode.HALF_UP) + ",min=" + safe(risk.getMinCalibratedFollowProbability()) + ")";
            reasons.add("Calibrated follow probability is below threshold.");
        }
        if (risk.isEnableSlippageGate()
                && safe(profitabilityEstimate.getEstimatedSlippageUsdc()).compareTo(safe(risk.getMaxExpectedSlippageUsdc())) > 0) {
            accepted = false;
            if (firstRejectGate == null) firstRejectGate = "G11_SLIPPAGE_COST_TOO_HIGH(slippage=" + safe(profitabilityEstimate.getEstimatedSlippageUsdc()).setScale(4, RoundingMode.HALF_UP) + ")";
            reasons.add("Predicted slippage cost is above threshold.");
        }
        if (risk.isEnableSlippageGate()
                && safe(profitabilityEstimate.getSlippageSpikeProbability()).compareTo(safe(risk.getMaxSlippageSpikeProbability())) > 0) {
            accepted = false;
            if (firstRejectGate == null) firstRejectGate = "G11_SLIPPAGE_SPIKE_PROB_TOO_HIGH(prob=" + safe(profitabilityEstimate.getSlippageSpikeProbability()).setScale(4, RoundingMode.HALF_UP) + ")";
            reasons.add("Slippage spike probability is above threshold.");
        }
        if (accepted) gateStage = 11; // 通过模型门控

        SignalScoreModelResult signalScore = signalScoreModelService.score(
                profile,
                event,
                consensus,
                profitabilityEstimate,
                entryTimingDecision,
                recommendedStake
        );
        if (signalScore != null && signalScore.getReasons() != null) {
            reasons.addAll(signalScore.getReasons());
        }
        if (accepted
                && risk.isEnableSignalScoreGate()
                && signalScore != null
                && safe(signalScore.getSignalScore()).compareTo(safe(risk.getMinLiveSignalScore())) < 0
                && !"DYNAMIC_FOLLOW_EXIT".equals(entryLane)
                && !manualAllowlistApplied) {
            accepted = false;
            if (firstRejectGate == null) {
                firstRejectGate = "G11_SIGNAL_SCORE_TOO_LOW(score=" + safe(signalScore.getSignalScore()) + ")";
            }
            reasons.add("[SIGNAL_SCORE_BLOCK] signalScore=" + safe(signalScore.getSignalScore())
                    + " is below minLiveSignalScore=" + safe(risk.getMinLiveSignalScore()) + ".");
        }
        if (manualAllowlistApplied
                && risk.isEnableSignalScoreGate()
                && signalScore != null
                && safe(signalScore.getSignalScore()).compareTo(safe(risk.getMinLiveSignalScore())) < 0
                && !"DYNAMIC_FOLLOW_EXIT".equals(entryLane)) {
            reasons.add("[MANUAL_ALLOWLIST_SIGNAL_SCORE_BYPASS] signalScore=" + safe(signalScore.getSignalScore())
                    + " is below minLiveSignalScore=" + safe(risk.getMinLiveSignalScore())
                    + " but allowlist wallet bypasses signal score gate.");
        }
        if (signalScore != null) {
            boolean scoreAccepted = !risk.isEnableSignalScoreGate()
                    || safe(signalScore.getSignalScore()).compareTo(safe(risk.getMinLiveSignalScore())) >= 0
                    || "DYNAMIC_FOLLOW_EXIT".equals(entryLane)
                    || manualAllowlistApplied;
            log.info("{}", flow("S5_RISK_EVALUATED", "信号评分模型",
                    scoreAccepted ? "通过" : "拒绝", event,
                    "entryLane", entryLane,
                    "stakeUsdc", recommendedStake,
                    "signalScore", signalScore.getSignalScore(),
                    "profitProbability", signalScore.getProfitProbability(),
                    "liquidityFactor", signalScore.getLiquidityFactor(),
                    "leaderReliabilityFactor", signalScore.getLeaderReliabilityFactor(),
                    "riskPenalty", signalScore.getRiskPenalty(),
                    "firstRejectGate", scoreAccepted ? null : "G11_SIGNAL_SCORE_TOO_LOW",
                    "blockerCategories", scoreAccepted ? null : "SIGNAL_SCORE",
                    "reasonSummary", summarizeReasons(signalScore.getReasons())));
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
                event,
                profitabilityEstimate,
                paperCanaryGateSoftened
        );
        // ── Gate 12: 入场策略路由（共识 / 单钱包Override）──
        SignalStrategyRouteResult strategyRoute = entryStrategyRouter.route(risk, strategyChecks, consensus.getDedupedEntityCount());
        if (!strategyRoute.isAccepted()) {
            if ("DYNAMIC_FOLLOW_EXIT".equals(entryLane)) {
                reasons.add("[DYNAMIC_FOLLOW_EXIT] Entry strategy gates are bypassed for exit/rebalance classification; execution is handled outside normal entry order submission.");
            } else if (manualAllowlistApplied && !consensusScoreFailed) {
                overrideApplied = true;
                reasons.add("[MANUAL_ALLOWLIST_PASS] Manual allowlist bypassed entry strategy address-admission blockers while preserving trade-level gates.");
            } else {
                accepted = false;
                if (firstRejectGate == null) firstRejectGate = "G12_ENTRY_STRATEGY_REJECTED(route=" + strategyRoute.getRouteMode() + ")";
                reasons.addAll(strategyRoute.getReasons());
                if (manualAllowlistApplied && consensusScoreFailed) {
                    reasons.add("[MANUAL_ALLOWLIST_CONSENSUS_SCORE_BLOCK] Manual allowlist cannot bypass consensus score gate.");
                }
            }
        } else if (strategyRoute.getMatchedStrategies().contains("SINGLE_WALLET_PAPER_CANARY")) {
            overrideApplied = true;
            entryLane = "SINGLE_WALLET_PAPER_CANARY";
            BigDecimal canaryStake = safe(risk.getPaperCanaryStakeUsdc()).max(minOrderUsdc);
            recommendedStake = recommendedStake.max(canaryStake)
                    .min(properties.getMaxStakeUsdc())
                    .min(availableCapital.max(BigDecimal.ZERO))
                    .setScale(2, RoundingMode.HALF_UP);
            reasons.add("[SINGLE_WALLET_PAPER_CANARY] Paper canary strategy accepted this signal with capped trial stake.");
        } else if (strategyRoute.getMatchedStrategies().contains("HIGH_QUALITY_SINGLE_WALLET")) {
            overrideApplied = true;
            entryLane = "HIGH_QUALITY_SINGLE_WALLET";
            reasons.add("[HIGH_QUALITY_SINGLE_WALLET] High-quality single-wallet strategy accepted this signal independently.");
        } else if (strategyRoute.getMatchedStrategies().contains("CONSENSUS")) {
            entryLane = "MULTI_ENTITY_CONSENSUS";
        }

        if (accepted) {
            gateStage = 12; // 通过入场策略路由
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

        // ── Gate 13: Late-phase duplicate-position check (scale-up path) ─────────
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
                            if (firstRejectGate == null) firstRejectGate = "G13_SCALE_UP_BUDGET";
                            reasons.add("[SCALE_UP_BUDGET_BLOCK] Scale-up would exceed market/outcome budget. existingCost="
                                    + existingCost + " addOn=" + addOn + " budget=" + marketOutcomeBudget + ".");
                        } else if (addOn.compareTo(minOrderUsdc) >= 0) {
                            isScaleUpSignal = true;
                            scaleUpAddOnStake = addOn;
                            reasons.add("[SCALE_UP] High-quality wallet triggered scale-up on existing position. addOn="
                                    + addOn + " existingCost=" + existingCost + " budget=" + marketOutcomeBudget + ".");
                        } else {
                            accepted = false;
                            if (firstRejectGate == null) firstRejectGate = "G13_SCALE_UP_MIN_ORDER";
                            reasons.add("[SCALE_UP_MIN_ORDER_BLOCK] Add-on is below minimum order. addOn="
                                    + addOn + " minOrder=" + minOrderUsdc + ".");
                        }
                    } else {
                        accepted = false;
                        if (firstRejectGate == null) firstRejectGate = "G13_SCALE_UP_CONDITIONS_FAILED";
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
                    if (firstRejectGate == null) firstRejectGate = "G13_DUPLICATE_POSITION_NO_SCALEUP_QUALIFY";
                    reasons.add("An open position already exists for the same market and outcome.");
                }
            } else {
                accepted = false;
                if (firstRejectGate == null) firstRejectGate = "G13_DUPLICATE_POSITION_SCALEUP_DISABLED";
                reasons.add("An open position already exists for the same market and outcome.");
            }
        }
        if (accepted) gateStage = 13; // 全部门控通过

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
                .signalScore(signalScore == null ? null : signalScore.getSignalScore())
                .signalScoreLiquidityFactor(signalScore == null ? null : signalScore.getLiquidityFactor())
                .signalScoreRiskPenalty(signalScore == null ? null : signalScore.getRiskPenalty())
                .signalScoreLeaderReliabilityFactor(signalScore == null ? null : signalScore.getLeaderReliabilityFactor())
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
                .gateHighWaterMark(gateStage)
                .firstRejectGate(firstRejectGate)
                .enabledStrategies(strategyRoute.getEnabledStrategies())
                .matchedStrategies(strategyRoute.getMatchedStrategies())
                .strategyChecks(strategyChecks)
                .scaleUpSignal(isScaleUpSignal)
                .scaleUpAddOnStake(isScaleUpSignal ? scaleUpAddOnStake : null)
                .grossMarketExposure(lastExposureResult != null ? lastExposureResult.getGrossMarketExposure() : null)
                .netDirectionalExposure(lastExposureResult != null ? lastExposureResult.getNetDirectionalExposure() : null)
                .reducesNetExposure(lastExposureResult != null && lastExposureResult.isReducesNetExposure())
                .reasons(reasons)
                .build();
        // ── 门控阶段追踪汇总日志 ──
        String gateTrackingSummary = "gateHighWaterMark=" + gateStage + "/13"
                + (firstRejectGate != null ? " firstRejectGate=" + firstRejectGate : " firstRejectGate=NONE(ALL_PASSED)");
        log.info("{} action=完成跟单风控评估 result={} {} followTaskId={} wallet={} marketId={} outcome={} side={} recommendedStakeUsdc={} consensusWallets={} consensusEntities={} consensusScore={} routeMode={} matchedStrategies={} reasons={}",
                LOG_PREFIX, result.isAccepted() ? "通过" : "拒绝", gateTrackingSummary, currentFollowTaskId,
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
        if (marketEndAt != null && marketEndAt > 0) {
            long remainingMs = marketEndAt - System.currentTimeMillis();
            int longHorizonMinHours = risk.getLongHorizonMinHours() != null
                    ? risk.getLongHorizonMinHours()
                    : (defaultInt(risk.getMaxHoursToMarketEnd(), Integer.MAX_VALUE) / 2);
            if (remainingMs > longHorizonMinHours * 3600_000L) {
                return "LONG_HORIZON";
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

    private String flow(String stage,
                        String action,
                        String result,
                        SmartWalletTradeEventEntity event,
                        Object... extra) {
        List<Object> fields = new ArrayList<>();
        fields.add("flowStage");
        fields.add(stage);
        fields.add("action");
        fields.add(action);
        fields.add("result");
        fields.add(result);
        fields.add("eventId");
        fields.add(event == null ? null : event.getId());
        fields.add("wallet");
        fields.add(event == null ? null : event.getWalletAddress());
        fields.add("marketId");
        fields.add(event == null ? null : event.getMarketId());
        fields.add("outcome");
        fields.add(event == null ? null : event.getOutcome());
        fields.add("side");
        fields.add(event == null ? null : event.getSide());
        if (extra != null) {
            for (Object item : extra) {
                fields.add(item);
            }
        }
        return StrategyFlowLogFormatter.flow(fields.toArray());
    }

    private String summarizeReasons(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return null;
        }
        return reasons.stream()
                .filter(StringUtils::hasText)
                .limit(4)
                .collect(Collectors.joining(" | "));
    }

    /**
     * 解析累计 triggerSize：在意图聚合时间窗口内，将同一 wallet+market+outcome+side 的
     * 所有 trade event 的 sizeUsdc 加总，作为"领单地址在本轮的累计开仓规模"。
     *
     * 目的：避免 triggerStakeCapRatio 按单笔碎片金额 cap，导致每笔跟单仅 1U。
     * 当累计值 > 单笔值时，使用累计值可使 cap 更合理。
     *
     * @param event 当前触发事件
     * @return max(累计sizeUsdc, 单笔sizeUsdc)
     */
    private BigDecimal resolveAccumulatedTriggerSize(SmartWalletTradeEventEntity event) {
        BigDecimal singleSize = safe(event.getSizeUsdc());

        // 仅在意图聚合启用时累计
        SmartWalletProperties.IntentAggregationConfig config = properties.getIntentAggregation();
        if (config == null || !config.isEnabled() || config.getWindowMinutes() <= 0) {
            return singleSize;
        }

        String walletAddress = event.getWalletAddress();
        String marketId = event.getMarketId();
        String outcome = event.getOutcome();
        String side = event.getSide();
        if (!StringUtils.hasText(walletAddress) || !StringUtils.hasText(marketId)
                || !StringUtils.hasText(outcome) || !StringUtils.hasText(side)) {
            return singleSize;
        }

        try {
            long windowMs = config.getWindowMinutes() * 60_000L;
            Date eventTime = event.getCreatedAt() != null ? event.getCreatedAt() : new Date();
            Date windowStart = new Date(eventTime.getTime() - windowMs);

            // 查询窗口内同维度的所有 trade event
            List<SmartWalletTradeEventEntity> windowEvents = tradeEventMapper.selectList(
                    new LambdaQueryWrapper<SmartWalletTradeEventEntity>()
                            .eq(SmartWalletTradeEventEntity::getWalletAddress, walletAddress)
                            .eq(SmartWalletTradeEventEntity::getMarketId, marketId)
                            .eq(SmartWalletTradeEventEntity::getOutcome, outcome)
                            .eq(SmartWalletTradeEventEntity::getSide, side)
                            .ge(SmartWalletTradeEventEntity::getCreatedAt, windowStart)
                            .le(SmartWalletTradeEventEntity::getCreatedAt, eventTime)
            );

            if (windowEvents == null || windowEvents.isEmpty()) {
                return singleSize;
            }

            BigDecimal accumulated = windowEvents.stream()
                    .map(e -> safe(e.getSizeUsdc()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (accumulated.compareTo(singleSize) > 0) {
                log.info("{} action=累计triggerSize result=使用累计值 wallet={} market={} outcome={} side={} " +
                                "singleSize={} accumulated={} windowEvents={}",
                        LOG_PREFIX, walletAddress, marketId, outcome, side,
                        singleSize.setScale(2, RoundingMode.HALF_UP),
                        accumulated.setScale(2, RoundingMode.HALF_UP),
                        windowEvents.size());
            }

            return accumulated.max(singleSize);
        } catch (Exception ex) {
            log.warn("{} resolveAccumulatedTriggerSize failed, fallback to single event size: {}",
                    LOG_PREFIX, ex.getMessage());
            return singleSize;
        }
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * 从 MarketInfo 或 event metadata 中解析 market 品类，用于 fee rate 查询。
     * 当前简化实现：返回 null（走 defaultTakerFeeRate）。
     * 后续可扩展为从 marketInfoService 读取 category/tags 字段。
     */
    private String resolveMarketCategory(SmartWalletTradeEventEntity event) {
        if (event == null || !StringUtils.hasText(event.getMarketId())
                || !StringUtils.hasText(event.getOutcome())) {
            return null;
        }
        try {
            SmartWalletMarketInfoEntity info = marketInfoService.getByMarketIdAndOutcome(
                    event.getMarketId(), event.getOutcome());
            if (info != null) {
                String cat = StringUtils.hasText(info.getNormalizedCategory())
                        ? info.getNormalizedCategory()
                        : info.getSourceCategory();
                if (StringUtils.hasText(cat)) {
                    return cat.toLowerCase(Locale.ROOT);
                }
            }
        } catch (Exception ignored) {
            // fail-open: 品类解析失败不影响下单
        }
        return null;
    }

    /**
     * 从最近的 market snapshot 中读取对应深度（ask 侧）。
     *
     * @return ask depth USDC 金额，null 表示快照不可用
     */
    private BigDecimal resolveAskDepth(SmartWalletTradeEventEntity event,
                                       SmartWalletProperties.SignalRisk risk) {
        if (event == null || !StringUtils.hasText(event.getMarketId())
                || !StringUtils.hasText(event.getOutcome())) {
            return null;
        }
        try {
            SmartWalletMarketSnapshotEntity snapshot = marketSnapshotCollectorService
                    .findLatestSnapshotAtOrBefore(event.getMarketId(), event.getOutcome(), new Date());
            if (snapshot == null) {
                return null;
            }
            // 快照超过 5 分钟视为过期，不使用
            if (snapshot.getSnapshotAt() != null
                    && System.currentTimeMillis() - snapshot.getSnapshotAt().getTime() > 300_000L) {
                return null;
            }
            String range = risk.getDepthCheckRange() == null ? "3c" : risk.getDepthCheckRange().toLowerCase();
            return switch (range) {
                case "1c" -> snapshot.getAskDepth1c();
                case "5c" -> snapshot.getAskDepth5c();
                default -> snapshot.getAskDepth3c(); // 3c
            };
        } catch (Exception e) {
            log.debug("Failed to resolve ask depth for marketId={}: {}", event.getMarketId(), e.getMessage());
            return null;
        }
    }

    private boolean isExecutableProfile(SmartWalletProfileEntity profile) {
        if (profile == null || isObserveCooldownActive(profile)) {
            return false;
        }
        return Boolean.TRUE.equals(profile.getWatchEnabled())
                && MONITOR_TIER_HOT.equalsIgnoreCase(profile.getMonitorTier());
    }

    private boolean isSingleWalletPaperCanaryAdmissionCandidate(SmartWalletProfileEntity profile,
                                                                SmartWalletTradeEventEntity event,
                                                                SmartWalletProperties.SignalRisk risk) {
        if (risk == null
                || !risk.isEnableSingleWalletPaperCanary()
                || !isEntryStrategyEnabled(risk, "SINGLE_WALLET_PAPER_CANARY")
                || profile == null
                || event == null) {
            return false;
        }
        if (!isExecutableProfile(profile) || Boolean.FALSE.equals(profile.getManualWatchEnabled())) {
            return false;
        }
        return safe(profile.getWalletScore()).compareTo(safe(risk.getPaperCanaryMinWalletScore())) >= 0
                && safe(event.getSizeUsdc()).compareTo(safe(risk.getPaperCanaryMinTriggerSizeUsdc())) >= 0;
    }

    private boolean isEntryStrategyEnabled(SmartWalletProperties.SignalRisk risk, String strategyKey) {
        if (risk == null || risk.getEnabledEntryStrategies() == null || !StringUtils.hasText(strategyKey)) {
            return false;
        }
        return risk.getEnabledEntryStrategies().stream()
                .filter(StringUtils::hasText)
                .map(item -> item.trim().toUpperCase(Locale.ROOT))
                .anyMatch(item -> item.equals(strategyKey));
    }

    private boolean isPaperCanaryStakeFloorEligible(boolean admissionCandidate,
                                                    SmartWalletProperties.SignalRisk risk,
                                                    BigDecimal recommendedStake,
                                                    TradeProfitabilityScoringService.ProfitabilityEstimate profitabilityEstimate) {
        if (!admissionCandidate || recommendedStake == null || recommendedStake.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        BigDecimal followProbability = profitabilityEstimate == null
                ? BigDecimal.ZERO
                : safe(profitabilityEstimate.getCalibratedFollowProbability());
        return followProbability.compareTo(safe(risk.getPaperCanaryMinFollowProbability())) >= 0;
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
            case "HF_SCALPER" -> baseMs;
            case "THEMATIC" -> Math.max(baseMs, 1_800_000L);
            case "LOW_FREQ_VALUE" -> Math.max(baseMs, 2_700_000L);
            case "SWING" -> Math.max(baseMs, 900_000L);
            case "LONG_HORIZON_SPECULATOR" -> Math.max(baseMs, 1_800_000L);
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
                                                                     SmartWalletTradeEventEntity event,
                                                                     TradeProfitabilityScoringService.ProfitabilityEstimate profitabilityEstimate,
                                                                     boolean paperCanaryGateSoftened) {
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
        checks.add(buildSingleWalletPaperCanaryCheck(
                profile,
                risk,
                triggerSize,
                recommendedStake,
                profitabilityEstimate,
                paperCanaryGateSoftened || consensusEntityCountFailed || consensusScoreFailed
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
                    + resolveMinConsensusEntities(risk) + ".");
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

    private int resolveMinConsensusEntities(SmartWalletProperties.SignalRisk risk) {
        return risk.getMinDistinctEntities() != null
                ? risk.getMinDistinctEntities()
                : defaultInt(risk.getMinDistinctWallets(), 1);
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

    private SignalStrategyCheckResult buildSingleWalletPaperCanaryCheck(SmartWalletProfileEntity profile,
                                                                        SmartWalletProperties.SignalRisk risk,
                                                                        BigDecimal triggerSize,
                                                                        BigDecimal recommendedStake,
                                                                        TradeProfitabilityScoringService.ProfitabilityEstimate profitabilityEstimate,
                                                                        boolean canaryContext) {
        List<String> reasons = new ArrayList<>();
        boolean accepted = true;
        if (risk == null || !risk.isEnableSingleWalletPaperCanary()) {
            accepted = false;
            reasons.add("[PAPER_CANARY_DISABLED] Single-wallet paper canary strategy is disabled.");
        }
        if (accepted && !canaryContext) {
            accepted = false;
            reasons.add("[PAPER_CANARY_CONTEXT_BLOCK] Normal entry strategies did not need paper canary fallback.");
        }
        if (accepted && (profile == null || !isExecutableProfile(profile))) {
            accepted = false;
            reasons.add("[PAPER_CANARY_WATCHLIST_BLOCK] Paper canary requires a HOT watch-enabled wallet.");
        }
        if (accepted && safe(profile.getWalletScore()).compareTo(safe(risk.getPaperCanaryMinWalletScore())) < 0) {
            accepted = false;
            reasons.add("[PAPER_CANARY_SCORE_BLOCK] Wallet score is below paper canary threshold.");
        }
        if (accepted && safe(triggerSize).compareTo(safe(risk.getPaperCanaryMinTriggerSizeUsdc())) < 0) {
            accepted = false;
            reasons.add("[PAPER_CANARY_SIZE_BLOCK] Trigger size is below paper canary threshold.");
        }
        if (accepted && safe(recommendedStake).compareTo(BigDecimal.ZERO) <= 0) {
            accepted = false;
            reasons.add("[PAPER_CANARY_STAKE_BLOCK] Recommended stake must be positive.");
        }
        BigDecimal followProbability = profitabilityEstimate == null
                ? BigDecimal.ZERO
                : safe(profitabilityEstimate.getCalibratedFollowProbability());
        if (accepted && followProbability.compareTo(safe(risk.getPaperCanaryMinFollowProbability())) < 0) {
            accepted = false;
            reasons.add("[PAPER_CANARY_PROBABILITY_BLOCK] Calibrated follow probability is below paper canary threshold.");
        }
        if (accepted) {
            reasons.add("[PAPER_CANARY_PASS] Single-wallet paper canary strategy passed.");
        }
        return SignalStrategyCheckResult.builder()
                .strategyKey("SINGLE_WALLET_PAPER_CANARY")
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
        Set<String> currentTokens = themeTokens(event.getQuestion());
        if (currentTokens.isEmpty()) {
            return 0;
        }
        int count = 0;
        long lastId = 0L;
        final int pageSize = 1000;
        while (true) {
            List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                    .in(SmartWalletPositionEntity::getStatus, List.of("OPEN", "PARTIALLY_CLOSED"))
                    .gt(SmartWalletPositionEntity::getId, lastId)
                    .orderByAsc(SmartWalletPositionEntity::getId)
                    .last("limit " + pageSize));
            if (positions == null || positions.isEmpty()) {
                break;
            }
            marketInfoService.populatePositions(positions);
            for (SmartWalletPositionEntity position : positions) {
                if (StringUtils.hasText(position.getQuestion())) {
                    Set<String> overlap = themeTokens(position.getQuestion());
                    overlap.retainAll(currentTokens);
                    if (overlap.size() >= 2) {
                        count++;
                    }
                }
                if (position.getId() != null && position.getId() > lastId) {
                    lastId = position.getId();
               }
            }
            if (positions.size() < pageSize) {
                break;
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

    private int countEntryOrdersOnMarketOutcome(String marketId, String outcome) {
        List<Long> marketInfoIds = marketOutcomeMarketInfoIds(marketId, outcome);
        if (marketInfoIds.isEmpty()) {
            return 0;
        }
        Long count = orderMapper.selectCount(new LambdaQueryWrapper<SmartWalletOrderEntity>()
                .in(SmartWalletOrderEntity::getMarketInfoId, marketInfoIds)
                .notIn(SmartWalletOrderEntity::getStatus, List.of("FAILED", "CANCELLED", "CANCELED", "REJECTED"))
                .ne(SmartWalletOrderEntity::getOrderType, "EXIT"));
        return count == null ? 0 : count.intValue();
    }

    private int countPendingFollowTasksOnMarketOutcome(String marketId, String outcome, Long currentFollowTaskId) {
        List<Long> marketInfoIds = marketOutcomeMarketInfoIds(marketId, outcome);
        if (marketInfoIds.isEmpty()) {
            return 0;
        }
        LambdaQueryWrapper<SmartWalletFollowTaskEntity> query = new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                .in(SmartWalletFollowTaskEntity::getMarketInfoId, marketInfoIds)
                .in(SmartWalletFollowTaskEntity::getStatus, List.of("PENDING", "EXECUTING"));
        if (currentFollowTaskId != null) {
            query.ne(SmartWalletFollowTaskEntity::getId, currentFollowTaskId);
        }
        Long count = followTaskMapper.selectCount(query);
        return count == null ? 0 : count.intValue();
    }

    private List<Long> marketOutcomeMarketInfoIds(String marketId, String outcome) {
        if (!StringUtils.hasText(marketId) || !StringUtils.hasText(outcome)) {
            return List.of();
        }
        return marketInfoService.listByMarketId(marketId).stream()
                .filter(info -> info != null && outcome.equalsIgnoreCase(info.getOutcome()))
                .map(SmartWalletMarketInfoEntity::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
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

    private LeaderPositionCheck checkLeaderProfitableActivePosition(SmartWalletTradeEventEntity event) {
        if (event == null || !StringUtils.hasText(event.getWalletAddress())
                || !StringUtils.hasText(event.getMarketId()) || !StringUtils.hasText(event.getOutcome())) {
            return new LeaderPositionCheck(false, "[LEADER_POSITION_NOT_PROFITABLE] Missing leader wallet/market/outcome for profitability validation.");
        }
        try {
            List<PolymarketApiClient.ActivePosition> positions = polymarketApiClient.fetchActivePositions(event.getWalletAddress());
            PolymarketApiClient.ActivePosition matched = positions.stream()
                    .filter(position -> sameText(position.getMarketId(), event.getMarketId()))
                    .filter(position -> sameText(position.getOutcome(), event.getOutcome()))
                    .findFirst()
                    .orElse(null);
            if (matched == null) {
                return new LeaderPositionCheck(false, "[LEADER_POSITION_NOT_PROFITABLE] Leader has no active position for the same market/outcome.");
            }
            BigDecimal pnl = safe(matched.getUnrealizedPnlUsdc());
            if (pnl.compareTo(BigDecimal.ZERO) <= 0) {
                return new LeaderPositionCheck(false, "[LEADER_POSITION_NOT_PROFITABLE] Leader active position is not profitable. unrealizedPnlUsdc="
                        + pnl.setScale(2, RoundingMode.HALF_UP) + ".");
            }
            return new LeaderPositionCheck(true, "[LEADER_POSITION_PROFITABLE] Leader active position is profitable. unrealizedPnlUsdc="
                    + pnl.setScale(2, RoundingMode.HALF_UP) + ".");
        } catch (Exception ex) {
            log.warn("{} action=leader持仓盈利校验 result=失败 wallet={} marketId={} outcome={} error={}",
                    LOG_PREFIX, event.getWalletAddress(), event.getMarketId(), event.getOutcome(), ex.getMessage());
            return new LeaderPositionCheck(false, "[LEADER_POSITION_NOT_PROFITABLE] Unable to validate leader active position profitability: "
                    + ex.getMessage());
        }
    }

    private boolean sameText(String left, String right) {
        return StringUtils.hasText(left)
                && StringUtils.hasText(right)
                && left.trim().equalsIgnoreCase(right.trim());
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

    private record LeaderPositionCheck(boolean accepted, String reason) {
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
