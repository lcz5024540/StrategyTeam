package com.metaverse.coin.strategy.job.smartwallet.service;
import com.metaverse.coin.strategy.common.service.smartwallet.model.MarketStateSnapshot;
import com.metaverse.coin.strategy.common.service.smartwallet.model.ConcentrationSnapshot;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletManualBlocklistService;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletManualAllowlistService;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletMarketInfoService;

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
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.ConsensusSignalView;
import com.metaverse.coin.strategy.job.smartwallet.model.EntryTimingDecision;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalRiskDecision;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalScoreModelResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalStrategyCheckResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalStrategyRouteResult;
import com.metaverse.coin.strategy.common.service.smartwallet.model.WalletArchetypeView;
import com.metaverse.coin.strategy.common.service.smartwallet.model.WalletBehaviorProfileView;
import com.metaverse.coin.strategy.job.strategy.application.StrategyFlowLogFormatter;
import com.metaverse.coin.strategy.common.service.smartwallet.model.ConcentrationSnapshot;
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
    private final LeaderRollingPerformanceService leaderRollingPerformanceService;
    private final LeaderBehaviorDriftService leaderBehaviorDriftService;
    /** W2-T1.4 (2026-05-16 第十一批): 价差 P70 hot path 门控所需的品类分位查询服务. */
    private final MarketLiquidityPercentileService marketLiquidityPercentileService;

    /**
     * 第十三批 #1: VWAP 逐档估算器, 可选注入 (避免破坏既有 @RequiredArgsConstructor 测试构造器).
     * 在 priceImpact 三段式 hot path 优先调用; 拿不到 estimate / estimator 为 null
     * 时 fallback 到既有的 profitabilityEstimate.getEstimatedSlippageUsdc() 代理算法.
     * 默认配置 enable-vwap-slippage-estimator=false, 启用前需 estimator 灰度验证.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MarketPriceImpactEstimator marketPriceImpactEstimator;

    public SignalRiskDecision evaluate(SmartWalletProfileEntity profile, SmartWalletTradeEventEntity event) {
        return evaluate(profile, event, null, null, null);
    }

    public SignalRiskDecision evaluate(SmartWalletProfileEntity profile,
                                       SmartWalletTradeEventEntity event,
                                       SmartWalletProperties.SignalRisk overrideRisk) {
        return evaluate(profile, event, overrideRisk, null, null);
    }

    public SignalRiskDecision evaluate(SmartWalletProfileEntity profile,
                                       SmartWalletTradeEventEntity event,
                                       SmartWalletProperties.SignalRisk overrideRisk,
                                       Long currentFollowTaskId) {
        return evaluate(profile, event, overrideRisk, currentFollowTaskId, null);
    }

    /**
     * 修复六（6.6）：单事件市场状态上下文复用。
     * {@code preResolvedMarketState} 非空时，G6 直接复用调用方（{@code createFollowTaskIfQualified}
     * 的市场状态前移预检）已解析的快照，避免同一事件重复解析市场状态；为 null 时 G6 自行 refreshMarket。
     */
    public SignalRiskDecision evaluate(SmartWalletProfileEntity profile,
                                       SmartWalletTradeEventEntity event,
                                       SmartWalletProperties.SignalRisk overrideRisk,
                                       Long currentFollowTaskId,
                                       MarketStateSnapshot preResolvedMarketState) {
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

        // ── Gate 2b: Leader 滑动窗口胜率门（W2-T2） ──
        // 数据驱动状态机：ACTIVE / COOLING / SUSPENDED
        //   ACTIVE     正常下单
        //   COOLING    不拒绝信号，但下方 stake 计算阶段强制 ×leaderRollingCoolingStakeMultiplier
        //   SUSPENDED  直接拒绝（连续多个 canary 窗口仍未恢复的 leader）
        // 配置开关：smart-wallet.signal-risk.enable-leader-rolling-gate（默认 false）
        LeaderRollingPerformanceService.LeaderRollingState leaderRollingState =
                leaderRollingPerformanceService.evaluate(signalWalletAddress);
        boolean leaderRollingCooling = false;
        if (leaderRollingState.isGateEnabled()) {
            switch (leaderRollingState.getStatus()) {
                case SUSPENDED:
                    if (manualAllowlistApplied
                            && risk.isManualAllowlistBypassLeaderRollingGate()) {
                        reasons.add("[LEADER_ROLLING_SUSPEND_BYPASS] " + leaderRollingState.getReason()
                                + " — bypassed by manual allowlist.");
                    } else {
                        accepted = false;
                        if (firstRejectGate == null) firstRejectGate = "G2_LEADER_ROLLING_SUSPENDED";
                        reasons.add("[LEADER_ROLLING_SUSPENDED] " + leaderRollingState.getReason());
                    }
                    break;
                case COOLING:
                    if (manualAllowlistApplied
                            && risk.isManualAllowlistBypassLeaderRollingGate()) {
                        reasons.add("[LEADER_ROLLING_COOLING_BYPASS] " + leaderRollingState.getReason()
                                + " — bypassed by manual allowlist.");
                    } else {
                        leaderRollingCooling = true;
                        reasons.add("[LEADER_ROLLING_COOLING] " + leaderRollingState.getReason()
                                + " — stake will be reduced to canary level "
                                + safe(risk.getLeaderRollingCoolingStakeMultiplier())
                                .setScale(4, RoundingMode.HALF_UP) + "× Kelly.");
                    }
                    break;
                case ACTIVE:
                default:
                    if (leaderRollingState.getMetrics() != null) {
                        reasons.add("[LEADER_ROLLING_ACTIVE] " + leaderRollingState.getReason()
                                + " samples=" + leaderRollingState.getMetrics().sampleCount()
                                + " wins=" + leaderRollingState.getMetrics().wins()
                                + " losses=" + leaderRollingState.getMetrics().losses());
                    }
                    break;
            }
        }

        // ── Gate 2c: Leader 行为漂移门（W2-T6） ──
        // 数据驱动：cosine 距离( current 特征向量, 历史 N 天 centroid ) > 阈值 → DRIFTED / DRIFTED_SEVERE
        //   ACTIVE         正常下单
        //   DRIFTED        不拒绝，但下方 stake 计算阶段强制 ×leaderDriftStakeMultiplier
        //   DRIFTED_SEVERE 直接拒绝（与 W2-T2 SUSPENDED 等价）
        // 配置开关：smart-wallet.signal-risk.enable-leader-drift-gate（默认 false）
        LeaderBehaviorDriftService.LeaderDriftState leaderDriftState =
                leaderBehaviorDriftService.evaluate(signalWalletAddress, event);
        boolean leaderDriftCooling = false;
        if (leaderDriftState.isGateEnabled()) {
            switch (leaderDriftState.getStatus()) {
                case DRIFTED_SEVERE:
                    if (manualAllowlistApplied && risk.isManualAllowlistBypassLeaderDriftGate()) {
                        reasons.add("[LEADER_DRIFT_SEVERE_BYPASS] " + leaderDriftState.getReason()
                                + " — bypassed by manual allowlist.");
                    } else {
                        accepted = false;
                        if (firstRejectGate == null) firstRejectGate = "G2c_LEADER_DRIFT_SEVERE";
                        reasons.add("[LEADER_DRIFT_SEVERE] " + leaderDriftState.getReason());
                    }
                    break;
                case DRIFTED:
                    if (manualAllowlistApplied && risk.isManualAllowlistBypassLeaderDriftGate()) {
                        reasons.add("[LEADER_DRIFT_BYPASS] " + leaderDriftState.getReason()
                                + " — bypassed by manual allowlist.");
                    } else {
                        leaderDriftCooling = true;
                        reasons.add("[LEADER_DRIFT_COOLING] " + leaderDriftState.getReason()
                                + " — stake will be reduced to "
                                + safe(risk.getLeaderDriftStakeMultiplier())
                                .setScale(4, RoundingMode.HALF_UP) + "× normal Kelly.");
                    }
                    break;
                case ACTIVE:
                default:
                    if (leaderDriftState.getMetrics() != null) {
                        reasons.add("[LEADER_DRIFT_ACTIVE] " + leaderDriftState.getReason());
                    }
                    break;
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

        // ── Gate 4: 组合约束（总成本 / 可用资金）──
        // G4_MAX_OPEN_POSITIONS 已硬移除：取消组合层「持仓数」上限拦截，由总成本 / 可用资金两道阀控制规模。
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
        // 修复六（6.6）：优先复用调用方已解析的市场状态快照，避免单事件重复解析。
        MarketStateSnapshot marketState = preResolvedMarketState != null
                ? preResolvedMarketState
                : marketStateService.refreshMarket(
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
                // 2026-05-18 ray@cowork: 修复 G6_MARKET_EXPIRED 误判 (占 56% 拒绝率)。
                // Polymarket 长尾市场 endAt 经常陈旧, 但 lifecycle=ACTIVE+accepting_orders=true 仍可正常交易。
                // 只有 marketState 同时确认非活跃或不接单时, 才视为真过期。
                if (!marketState.isActive() || !marketState.acceptingOrders()) {
                    accepted = false;
                    if (firstRejectGate == null) firstRejectGate = "G6_MARKET_EXPIRED";
                    reasons.add("Market is already at or past expected end time.");
                } else {
                    reasons.add("[MARKET_END_AT_STALE_BUT_ACTIVE] endAt=" + endAt
                            + " in past but lifecycle=ACTIVE & acceptingOrders=true; accepting follow.");
                }
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
            MarketStateSnapshot freshState = marketStateService.refreshMarket(
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
                // 2026-05-18 ray@cowork: 同上短路修复, force refresh 后 endAt 过期也要看 lifecycle/acceptingOrders。
                if (!freshState.isActive() || !freshState.acceptingOrders()) {
                    accepted = false;
                    if (firstRejectGate == null) firstRejectGate = "G6_MARKET_EXPIRED_AFTER_FORCE_REFRESH";
                    reasons.add("[MARKET_END_AT_MISSING_FORCE_REFRESH] marketEndAt was null; forced refresh reveals market is already expired.");
                } else {
                    reasons.add("[MARKET_END_AT_STALE_BUT_ACTIVE_AFTER_FORCE_REFRESH] endAt=" + freshEndAt
                            + " past but market still lifecycle=ACTIVE & acceptingOrders=true; accepting follow.");
                }
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
                && !"DYNAMIC_FOLLOW_EXIT".equals(entryLane)
                && !(manualAllowlistApplied && risk.isManualAllowlistBypassFastEventBlock())) {
            accepted = false;
            if (firstRejectGate == null) firstRejectGate = "G7_FAST_EVENT_BLOCKED";
            reasons.add("[FAST_EVENT_ENTRY_BLOCK] Fast-event markets are blocked for new entry; only dynamic follow-exit signals are allowed."
                    + (manualAllowlistApplied ? " [MANUAL_ALLOWLIST_FAST_EVENT_NOTE] Allowlist wallet also blocked — API rate-limit constraints prevent fast-event tracking." : ""));
        } else if ("FAST_EVENT".equals(marketType)
                && !"DYNAMIC_FOLLOW_EXIT".equals(entryLane)
                && manualAllowlistApplied) {
            reasons.add("[MANUAL_ALLOWLIST_FAST_EVENT_BYPASS] Fast-event entry block bypassed for manual allowlist paper validation.");
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
        // 2026-05-19 ray@cowork: LONG_HORIZON stake multiplier 之前在两处独立应用 (本块 + Kelly 块末),
        // 当 Kelly 返回 null 或 negativeEV+allowlistBypass 走 fallback 时, recommendedStake = preliminaryStake,
        // 紧接着又被乘一次 → 同一 multiplier 累积 0.50 × 0.50 = 0.25, 把好信号砍到 minOrder 以下.
        // 现统一: 本块只对"legacy formula path (Kelly 未采用)"生效; Kelly 真正采用时跳过这次, 由
        // 下面 Kelly 块末单点应用.
        if ("LONG_HORIZON".equals(marketType)) {
            BigDecimal multiplier = risk.getLongHorizonStakeMultiplier() == null
                    ? new BigDecimal("0.50")
                    : safe(risk.getLongHorizonStakeMultiplier());
            preliminaryStake = preliminaryStake.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
            reasons.add("[LONG_HORIZON_STAKE] Long-horizon market stake multiplier applied (preliminary): "
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
            // F-P0-3: 数据不可用时按 enableConcentrationFailOpen 决定行为
            //   false (默认/修复后) → fail-closed: 拒绝信号，避免集中度风控被静默绕过
            //   true  (紧急回滚开关) → fail-open: 跳过本门控，保留旧行为
            if (!snap.isDataAvailable() && !risk.isEnableConcentrationFailOpen()) {
                accepted = false;
                if (firstRejectGate == null) firstRejectGate = "G9_CONCENTRATION_DATA_UNAVAILABLE";
                reasons.add("[CONCENTRATION_DATA_UNAVAILABLE] Concentration snapshot is missing for market="
                        + event.getMarketId() + " outcome=" + event.getOutcome()
                        + " (API failure or empty data). G9 is fail-closed; set "
                        + "smart-wallet.signal-risk.enable-concentration-fail-open=true to bypass.");
            } else if (snap.isDataAvailable()) {
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
            // F-P0-3: 数据可用分支结束。fail-open 紧急回滚开关打开时，跳过本门控（保留旧行为）。
        }
        if (accepted) gateStage = 9; // 通过集中度门控

        TradeProfitabilityScoringService.ProfitabilityEstimate profitabilityEstimate =
                tradeProfitabilityScoringService.estimate(profile, event, consensus, preliminaryStake);

        // ── Gate 10: Kelly 仓位计算 ─────────────────────────────────────────────
        BigDecimal recommendedStake;
        // 2026-05-19 ray@cowork: 仅当 Kelly 真正返回 > 0 stake 时才置 true; null / 0 EV fallback 走 preliminaryStake
        // 路径不应再二次应用 LONG_HORIZON multiplier (preliminaryStake 已经在上面乘过一次).
        boolean kellyStakeAdopted = false;
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
                if (manualAllowlistApplied && risk.isManualAllowlistBypassKellyNegativeEv()) {
                    recommendedStake = preliminaryStake;
                    reasons.add("[MANUAL_ALLOWLIST_KELLY_NEGATIVE_EV_BYPASS] Kelly stake is negative EV; manual allowlist paper validation falls back to preliminary stake.");
                } else {
                    accepted = false;
                    if (firstRejectGate == null) firstRejectGate = "G10_KELLY_NEGATIVE_EV(p=" + safe(profitabilityEstimate.getCalibratedFollowProbability()).setScale(4, RoundingMode.HALF_UP) + ",price=" + safe(event.getPrice()).setScale(4, RoundingMode.HALF_UP) + ")";
                    recommendedStake = BigDecimal.ZERO;
                    reasons.add("Kelly stake: negative EV, signal rejected.");
                }
                log.warn("[KELLY_NEGATIVE_EV] wallet={} marketId={} outcome={} price={} calibratedP={} side={} triggerSizeUsdc={} allowlisted={}",
                        event.getWalletAddress(), event.getMarketId(), event.getOutcome(),
                        safe(event.getPrice()).setScale(4, RoundingMode.HALF_UP),
                        safe(profitabilityEstimate.getCalibratedFollowProbability()).setScale(4, RoundingMode.HALF_UP),
                        event.getSide(), safe(event.getSizeUsdc()).setScale(2, RoundingMode.HALF_UP),
                        manualAllowlistApplied);
            } else {
                recommendedStake = kellyStake;
                kellyStakeAdopted = true;
                reasons.add("Kelly stake=" + kellyStake + " (p=" +
                        safe(profitabilityEstimate.getCalibratedFollowProbability()).setScale(4, RoundingMode.HALF_UP) +
                        " price=" + safe(event.getPrice()).setScale(4, RoundingMode.HALF_UP) + ").");
            }
        } else {
            recommendedStake = preliminaryStake;
        }
        // 2026-05-19 ray@cowork: 仅在"真正采用 Kelly stake"路径下应用一次; preliminaryStake fallback / Kelly 未启用
        // 都不再二次乘 — preliminaryStake 已经在上方 LONG_HORIZON 块里乘过.
        if ("LONG_HORIZON".equals(marketType) && kellyStakeAdopted) {
            BigDecimal multiplier = risk.getLongHorizonStakeMultiplier() == null
                    ? new BigDecimal("0.50")
                    : safe(risk.getLongHorizonStakeMultiplier());
            recommendedStake = recommendedStake.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
            reasons.add("[LONG_HORIZON_STAKE] Long-horizon market Kelly stake multiplier applied: "
                    + multiplier.setScale(2, RoundingMode.HALF_UP) + ".");
        }
        recommendedStake = applyAdvancedStakeAdjustments(recommendedStake, behaviorProfile, profitabilityEstimate, event, risk, reasons);
        recommendedStake = safe(recommendedStake).min(availableCapital.max(BigDecimal.ZERO)).setScale(2, RoundingMode.HALF_UP);
        // ── Leader 滑动窗口 COOLING 期 stake 强制降级（W2-T2）──
        if (leaderRollingCooling && recommendedStake.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal coolingMultiplier = safe(risk.getLeaderRollingCoolingStakeMultiplier());
            if (coolingMultiplier.signum() > 0 && coolingMultiplier.compareTo(BigDecimal.ONE) < 0) {
                BigDecimal before = recommendedStake;
                recommendedStake = recommendedStake.multiply(coolingMultiplier).setScale(2, RoundingMode.HALF_UP);
                reasons.add("[LEADER_ROLLING_COOLING_STAKE] stake reduced from " + before
                        + " to " + recommendedStake
                        + " (multiplier=" + coolingMultiplier.setScale(4, RoundingMode.HALF_UP) + ").");
            }
        }
        // ── Leader 行为漂移门 DRIFTED 期 stake 降级（W2-T6）──
        // 与 W2-T2 共享 cooling 语义；多门叠加时各自按比例累乘（与 W2-T4/W2-T5 一致）。
        if (leaderDriftCooling && recommendedStake.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal driftMultiplier = safe(risk.getLeaderDriftStakeMultiplier());
            if (driftMultiplier.signum() > 0 && driftMultiplier.compareTo(BigDecimal.ONE) < 0) {
                BigDecimal before = recommendedStake;
                recommendedStake = recommendedStake.multiply(driftMultiplier).setScale(2, RoundingMode.HALF_UP);
                reasons.add("[LEADER_DRIFT_COOLING_STAKE] stake reduced from " + before
                        + " to " + recommendedStake
                        + " (multiplier=" + driftMultiplier.setScale(4, RoundingMode.HALF_UP) + ").");
            }
        }
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
        // ── Gate 10c: 暴露上限检查 (单市场/单地址) ──
        FollowExposureLimitService.ExposureCheckResult lastExposureResult = null;
        if (accepted) {
            SmartWalletProperties.ExposureLimitConfig exposureConfig = properties.getExposureLimit();
            if (exposureConfig != null && exposureConfig.isEnabled()) {
                FollowExposureLimitService.ExposureCheckResult exposureResult =
                        followExposureLimitService.check(
                                event.getMarketId(), event.getOutcome(), event.getWalletAddress(),
                                recommendedStake);
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
        // ── W2-T4 价格冲击三段式门控 ───────────────────────────────────────────
        // 在 enablePriceImpactGate=true 时优先用 bps 三段式判定（避免一刀切拒绝），
        // 否则回退到既有的 maxExpectedSlippageUsdc 硬阈值（向后兼容）。
        // W2-T4.3: 同时导出 priceImpactExecutionMode 供 PolymarketOrderRequestFactory
        // 真正路由 MAKER_FIRST / MAKER_ONLY，而不仅仅是日志标签。
        boolean priceImpactGateApplied = false;
        String priceImpactExecutionMode = null;
        // 第十四批 #1: 把 bps source 提到 block 外, 让 Builder 段可访问;
        // 当 priceImpactGate 未启用 / 未命中时保持 null, Shadow recorder 也按 null 落表.
        String priceImpactBpsSource = null;
        if (risk.isEnableSlippageGate() && risk.isEnablePriceImpactGate() && accepted) {
            // 第十三批 #1: 优先走 VWAP 逐档估算 (基于 market_snapshot 的真实订单簿深度);
            // estimator 缺失 / 关闭 / 失败 → fallback 到既有 profitabilityEstimate 代理算法.
            int bps;
            String bpsSource;
            MarketPriceImpactEstimator.PriceImpactEstimate vwapEstimate = null;
            if (marketPriceImpactEstimator != null && risk.isEnableVwapSlippageEstimator()) {
                try {
                    vwapEstimate = marketPriceImpactEstimator.estimate(event, recommendedStake);
                } catch (RuntimeException ex) {
                    log.warn("[SignalRiskService] VWAP estimator failed eventId={} reason={}",
                            event == null ? null : event.getId(), ex.toString());
                }
            }
            if (vwapEstimate != null && vwapEstimate.isOk() && vwapEstimate.getEstimatedSlippageBps() != null) {
                long vwapBps = vwapEstimate.getEstimatedSlippageBps().longValue();
                bps = vwapBps > Integer.MAX_VALUE ? Integer.MAX_VALUE
                        : vwapBps < Integer.MIN_VALUE ? Integer.MIN_VALUE
                        : (int) vwapBps;
                bpsSource = "VWAP(" + vwapEstimate.getStatus() + ")";
            } else {
                BigDecimal estSlippage = safe(profitabilityEstimate.getEstimatedSlippageUsdc());
                BigDecimal stakeForBps = recommendedStake == null || recommendedStake.compareTo(BigDecimal.ZERO) <= 0
                        ? BigDecimal.ONE
                        : recommendedStake;
                // bps = (slippageUsdc / stakeUsdc) * 10000
                long bpsLong = estSlippage.multiply(new BigDecimal("10000"))
                        .divide(stakeForBps, 0, RoundingMode.HALF_UP)
                        .longValue();
                // 截断到 int 范围，避免极端值溢出
                bps = bpsLong > Integer.MAX_VALUE ? Integer.MAX_VALUE
                        : bpsLong < Integer.MIN_VALUE ? Integer.MIN_VALUE
                        : (int) bpsLong;
                bpsSource = vwapEstimate != null && vwapEstimate.getStatus() != null
                        ? "PROXY(vwap=" + vwapEstimate.getStatus() + ")"
                        : "PROXY";
            }
            reasons.add("[PRICE_IMPACT_SRC] bps=" + bps + " source=" + bpsSource);
            int rejectBps = risk.getPriceImpactRejectBps();
            int severeBps = risk.getPriceImpactSevereDowngradeBps();
            int downBps = risk.getPriceImpactDowngradeBps();
            if (bps >= rejectBps) {
                accepted = false;
                if (firstRejectGate == null) firstRejectGate = "G11_PRICE_IMPACT_REJECT(bps=" + bps + ")";
                reasons.add("[PRICE_IMPACT_REJECT] Estimated slippage=" + bps + "bps ≥ rejectBps="
                        + rejectBps + ". Rejecting signal.");
                priceImpactExecutionMode = "REJECTED";
            } else if (bps >= severeBps) {
                BigDecimal mult = safe(risk.getPriceImpactSevereDowngradeMultiplier()).max(new BigDecimal("0.05"));
                BigDecimal before = recommendedStake;
                recommendedStake = recommendedStake.multiply(mult).setScale(2, RoundingMode.HALF_UP);
                // 防御性下限：降级后若低于 minOrderUsdc 则提升到 minOrder（避免被后续无价值地拒绝）
                if (recommendedStake.compareTo(minOrderUsdc) < 0) {
                    recommendedStake = minOrderUsdc.setScale(2, RoundingMode.HALF_UP);
                    reasons.add("[PRICE_IMPACT_SEVERE_FLOOR] Severe-downgrade pushed stake below minOrder; raised to "
                            + recommendedStake + ".");
                }
                reasons.add("[PRICE_IMPACT_SEVERE_DOWNGRADE] slippage=" + bps + "bps in [" + severeBps + "," + rejectBps
                        + "). Stake " + before + " → " + recommendedStake + " (×" + mult + ", MAKER_ONLY, "
                        + risk.getPriceImpactMakerOnlyTimeoutSeconds() + "s timeout).");
                priceImpactExecutionMode = "MAKER_ONLY";
            } else if (bps >= downBps) {
                BigDecimal mult = safe(risk.getPriceImpactDowngradeMultiplier()).max(new BigDecimal("0.05"));
                BigDecimal before = recommendedStake;
                recommendedStake = recommendedStake.multiply(mult).setScale(2, RoundingMode.HALF_UP);
                if (recommendedStake.compareTo(minOrderUsdc) < 0) {
                    recommendedStake = minOrderUsdc.setScale(2, RoundingMode.HALF_UP);
                    reasons.add("[PRICE_IMPACT_DOWNGRADE_FLOOR] Downgrade pushed stake below minOrder; raised to "
                            + recommendedStake + ".");
                }
                reasons.add("[PRICE_IMPACT_DOWNGRADE] slippage=" + bps + "bps in [" + downBps + "," + severeBps
                        + "). Stake " + before + " → " + recommendedStake + " (×" + mult + ", MAKER_FIRST).");
                priceImpactExecutionMode = "MAKER_FIRST";
            } else {
                reasons.add("[PRICE_IMPACT_OK] slippage=" + bps + "bps < downgradeBps=" + downBps + "; full stake.");
                priceImpactExecutionMode = "NORMAL";
            }
            priceImpactGateApplied = true;
            // 第十四批 #1: 把 bpsSource 透到 decision (供 Shadow recorder 落表)
            priceImpactBpsSource = bpsSource;
        }
        // 兼容路径：未启用 priceImpact 三段式时，沿用既有 USDC 硬阈值。
        if (!priceImpactGateApplied
                && risk.isEnableSlippageGate()
                && safe(profitabilityEstimate.getEstimatedSlippageUsdc()).compareTo(safe(risk.getMaxExpectedSlippageUsdc())) > 0) {
            accepted = false;
            if (firstRejectGate == null) firstRejectGate = "G11_SLIPPAGE_COST_TOO_HIGH(slippage=" + safe(profitabilityEstimate.getEstimatedSlippageUsdc()).setScale(4, RoundingMode.HALF_UP) + ")";
            reasons.add("Predicted slippage cost is above threshold.");
        }
        // ── W2-T5 距结算时间门（time-to-expiry, 三段式）─────────────────────────
        // 决议文档:
        //   remainingMs < rejectMin        → 拒绝（高速结算 + 滑点风险叠加，弊大于利）
        //   remainingMs in [reject, downgrade) → 仓位 ×downgradeMultiplier（默认 0.5）
        //   remainingMs ≥ downgrade            → 全仓
        // 与既有 G6 "maxHoursToMarketEnd 太远拒绝" 互补；本段处理"太近"侧。
        // marketType 在豁免列表（如未来的 weather / btc_minute 等短期结构化市场）则跳过。
        if (risk.isEnableTimeToExpiryGate() && accepted && endAt != null && endAt > 0) {
            long remainingMs = endAt - System.currentTimeMillis();
            long rejectMs = (long) Math.max(0, risk.getTimeToExpiryRejectMinutes()) * 60_000L;
            long downgradeMs = (long) Math.max(0, risk.getTimeToExpiryDowngradeMinutes()) * 60_000L;
            List<String> exempts = risk.getTimeToExpiryExemptMarketTypes();
            boolean exempted = exempts != null && marketType != null
                    && exempts.stream().anyMatch(t -> t != null && t.equalsIgnoreCase(marketType));
            if (exempted) {
                reasons.add("[TIME_TO_EXPIRY_EXEMPT] marketType=" + marketType
                        + " 在豁免列表中, 跳过 W2-T5 距结算时间门.");
            } else if (remainingMs > 0 && remainingMs < rejectMs) {
                accepted = false;
                if (firstRejectGate == null) {
                    firstRejectGate = "G11_TIME_TO_EXPIRY_REJECT(remainingMin="
                            + (remainingMs / 60_000L) + ")";
                }
                reasons.add("[TIME_TO_EXPIRY_REJECT] remainingMin=" + (remainingMs / 60_000L)
                        + " < rejectMin=" + risk.getTimeToExpiryRejectMinutes()
                        + ". Rejecting new entry near settlement.");
            } else if (remainingMs > 0 && remainingMs < downgradeMs) {
                BigDecimal mult = safe(risk.getTimeToExpiryDowngradeMultiplier()).max(new BigDecimal("0.05"));
                BigDecimal before = recommendedStake;
                recommendedStake = recommendedStake.multiply(mult).setScale(2, RoundingMode.HALF_UP);
                if (recommendedStake.compareTo(minOrderUsdc) < 0) {
                    recommendedStake = minOrderUsdc.setScale(2, RoundingMode.HALF_UP);
                    reasons.add("[TIME_TO_EXPIRY_FLOOR] Downgrade pushed stake below minOrder; raised to "
                            + recommendedStake + ".");
                }
                reasons.add("[TIME_TO_EXPIRY_DOWNGRADE] remainingMin=" + (remainingMs / 60_000L)
                        + " in [" + risk.getTimeToExpiryRejectMinutes() + ","
                        + risk.getTimeToExpiryDowngradeMinutes() + ") min. Stake "
                        + before + " → " + recommendedStake + " (×" + mult + ").");
            } else {
                reasons.add("[TIME_TO_EXPIRY_OK] remainingMin=" + (remainingMs / 60_000L)
                        + " ≥ downgradeMin=" + risk.getTimeToExpiryDowngradeMinutes() + "; full stake.");
            }
        }
        if (risk.isEnableSlippageGate()
                && safe(profitabilityEstimate.getSlippageSpikeProbability()).compareTo(safe(risk.getMaxSlippageSpikeProbability())) > 0) {
            accepted = false;
            if (firstRejectGate == null) firstRejectGate = "G11_SLIPPAGE_SPIKE_PROB_TOO_HIGH(prob=" + safe(profitabilityEstimate.getSlippageSpikeProbability()).setScale(4, RoundingMode.HALF_UP) + ")";
            reasons.add("Slippage spike probability is above threshold.");
        }
        // ── W2-T1.4 价差 P70 hot path 门 (2026-05-16 第十一批) ─────────────────
        // 决议: 单 leader 单市场 spread 超过该品类历史 P70 → 降级 / 拒绝。
        //   spread ≤ P70                  → 全仓
        //   spread ∈ (P70, P70*downFac]   → ×spreadP70DowngradeMultiplier
        //   spread > P70*rejectFac        → 拒绝
        // 与 W2-T1 流动性 P30 互补 (一个看深度, 一个看挂单价差); 与 W2-T4 priceImpact
        // 独立 (前者用历史品类分位, 后者用 profitability 即时估算)。
        // 数据源依赖 pm_market_liquidity_percentile_daily 的 spread_bps_p70 列, 由
        // MarketLiquidityPercentileSnapshotService 在第十批已写入。
        if (risk.isEnableSpreadP70Gate() && accepted && marketLiquidityPercentileService != null) {
            String category = marketState == null ? null : marketState.normalizedCategory();
            if (category == null || category.isBlank()) {
                category = marketState == null ? null : marketState.sourceCategory();
            }
            BigDecimal p70 = marketLiquidityPercentileService.spreadP70ForCategory(category);
            if (p70 != null && p70.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal currentSpread = resolveSpreadBps(event, risk);
                if (currentSpread != null && currentSpread.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal downgradeFactor = safe(risk.getSpreadP70DowngradeFactor()).max(BigDecimal.ONE);
                    BigDecimal rejectFactor = safe(risk.getSpreadP70RejectFactor()).max(downgradeFactor);
                    BigDecimal downgradeCap = p70.multiply(downgradeFactor);
                    BigDecimal rejectCap = p70.multiply(rejectFactor);
                    if (currentSpread.compareTo(rejectCap) > 0) {
                        accepted = false;
                        if (firstRejectGate == null) {
                            firstRejectGate = "G11_SPREAD_P70_REJECT(spread=" + currentSpread.setScale(2, RoundingMode.HALF_UP)
                                    + "bps,p70=" + p70.setScale(2, RoundingMode.HALF_UP)
                                    + ",rejectCap=" + rejectCap.setScale(2, RoundingMode.HALF_UP) + ")";
                        }
                        reasons.add("[SPREAD_P70_REJECT] category=" + category
                                + " spread=" + currentSpread.setScale(2, RoundingMode.HALF_UP)
                                + "bps > p70(" + p70.setScale(2, RoundingMode.HALF_UP)
                                + ")×rejectFactor(" + rejectFactor + ")=" + rejectCap.setScale(2, RoundingMode.HALF_UP)
                                + "bps. Rejecting wide-spread entry.");
                    } else if (currentSpread.compareTo(downgradeCap) > 0) {
                        BigDecimal mult = safe(risk.getSpreadP70DowngradeMultiplier()).max(new BigDecimal("0.05"));
                        BigDecimal before = recommendedStake;
                        recommendedStake = recommendedStake.multiply(mult).setScale(2, RoundingMode.HALF_UP);
                        if (recommendedStake.compareTo(minOrderUsdc) < 0) {
                            recommendedStake = minOrderUsdc.setScale(2, RoundingMode.HALF_UP);
                            reasons.add("[SPREAD_P70_FLOOR] Downgrade pushed stake below minOrder; raised to "
                                    + recommendedStake + ".");
                        }
                        reasons.add("[SPREAD_P70_DOWNGRADE] category=" + category
                                + " spread=" + currentSpread.setScale(2, RoundingMode.HALF_UP)
                                + "bps in (p70=" + p70.setScale(2, RoundingMode.HALF_UP)
                                + ", p70×" + downgradeFactor + "=" + downgradeCap.setScale(2, RoundingMode.HALF_UP)
                                + "]. Stake " + before + " → " + recommendedStake + " (×" + mult + ").");
                    } else {
                        reasons.add("[SPREAD_P70_OK] category=" + category
                                + " spread=" + currentSpread.setScale(2, RoundingMode.HALF_UP)
                                + "bps ≤ p70=" + p70.setScale(2, RoundingMode.HALF_UP) + "bps; full stake.");
                    }
                }
            }
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
                .leaderRollingStatus(leaderRollingState.isGateEnabled()
                        ? leaderRollingState.getStatus().name() : null)
                .leaderRollingWinRate(leaderRollingState.getMetrics() == null
                        ? null : leaderRollingState.getMetrics().winRate())
                .leaderRollingSampleCount(leaderRollingState.getMetrics() == null
                        ? null : leaderRollingState.getMetrics().sampleCount())
                .leaderRollingTotalPnlUsdc(leaderRollingState.getMetrics() == null
                        ? null : leaderRollingState.getMetrics().totalPnlUsdc())
                .leaderDriftStatus(leaderDriftState.isGateEnabled()
                        ? leaderDriftState.getStatus().name() : null)
                .leaderDriftCosineDistance(leaderDriftState.getMetrics() == null
                        ? null : leaderDriftState.getMetrics().cosineDistance())
                .leaderDriftSampleCount(leaderDriftState.getMetrics() == null
                        ? null : leaderDriftState.getMetrics().sampleCount())
                .priceImpactExecutionMode(priceImpactExecutionMode) // W2-T4.3
                .priceImpactBpsSource(priceImpactBpsSource) // 第十四批 #1
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
                                                MarketStateSnapshot marketState,
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
                                     MarketStateSnapshot marketState,
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
    /**
     * W2-T1.4 (2026-05-16 第十一批): 读取最近一笔 market snapshot 的 spread bps。
     *
     * <p>陈旧度超过 {@code spreadP70SnapshotMaxAgeMs} 的快照返回 null，让 W2-T1.4 门控
     * 直接跳过, 避免"陈旧 spread + 高 P70" 造成的误降级 / 误拒绝。</p>
     */
    private BigDecimal resolveSpreadBps(SmartWalletTradeEventEntity event,
                                        SmartWalletProperties.SignalRisk risk) {
        if (event == null || !StringUtils.hasText(event.getMarketId())
                || !StringUtils.hasText(event.getOutcome())) {
            return null;
        }
        try {
            // F-P1-7: hot-path 走 findFreshSnapshot, 开关 enable-ws-hot-path-snapshot=true 时优先 WS,
            // 开关关闭时与原 findLatestSnapshotAtOrBefore(..., now) 完全一致
            SmartWalletMarketSnapshotEntity snapshot = marketSnapshotCollectorService
                    .findFreshSnapshot(event.getMarketId(), event.getOutcome());
            if (snapshot == null || snapshot.getSpreadBps() == null) {
                return null;
            }
            long maxAge = risk == null || risk.getSpreadP70SnapshotMaxAgeMs() <= 0
                    ? 300_000L
                    : risk.getSpreadP70SnapshotMaxAgeMs();
            if (snapshot.getSnapshotAt() != null
                    && System.currentTimeMillis() - snapshot.getSnapshotAt().getTime() > maxAge) {
                return null;
            }
            return snapshot.getSpreadBps();
        } catch (Exception e) {
            log.debug("[SPREAD_P70] failed to resolve spread bps marketId={} reason={}",
                    event.getMarketId(), e.toString());
            return null;
        }
    }

    private BigDecimal resolveAskDepth(SmartWalletTradeEventEntity event,
                                       SmartWalletProperties.SignalRisk risk) {
        if (event == null || !StringUtils.hasText(event.getMarketId())
                || !StringUtils.hasText(event.getOutcome())) {
            return null;
        }
        try {
            // F-P1-7: hot-path 走 findFreshSnapshot
            SmartWalletMarketSnapshotEntity snapshot = marketSnapshotCollectorService
                    .findFreshSnapshot(event.getMarketId(), event.getOutcome());
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
        // W2-T3 落实调整 1：BOOST_ONLY 模式下共识不足不作为门槛，仅作为加分项。
        // HARD_GATE 模式保留原硬门控行为（监控地址 ≥ 100 高质量 leader 后启用）。
        String gateMode = risk.getConsensusGateMode() == null
                ? "BOOST_ONLY"
                : risk.getConsensusGateMode().trim().toUpperCase(Locale.ROOT);
        boolean hardGate = "HARD_GATE".equals(gateMode);
        // 修复（回归）：CONSENSUS 策略是否"匹配"只取决于共识本身是否充分，与 gateMode 无关。
        // 此前 BOOST_ONLY（默认）下无条件 accepted=true，经 EntryStrategyRouter(ANY) 后
        // CONSENSUS 永远算"已匹配策略" → 整个信号永久放行，使
        // HIGH_QUALITY_SINGLE_WALLET 行为门 / SINGLE_WALLET_PAPER_CANARY canary 门彻底失效。
        // gateMode（BOOST_ONLY vs HARD_GATE）只决定 reason 文案/是否硬拒绝，
        // 绝不能让 CONSENSUS 在 router 里伪装成已匹配策略。
        // 共识门关闭时 *Failed 标志恒为 false → CONSENSUS 仍匹配（保持"不校验即放行"语义）；
        // 共识不足时 CONSENSUS 不匹配，但不是硬 blocker —— 能否进场交给其它 fallback 策略。
        boolean accepted = !consensusEntityCountFailed && !consensusScoreFailed;
        if (consensusEntityCountFailed) {
            int effectiveWindow = risk.getConsensusWindowSeconds() == null ? 180 : risk.getConsensusWindowSeconds();
            String prefix = hardGate ? "" : "[CONSENSUS_BOOST_ONLY_NOTICE] ";
            reasons.add(prefix + "Consensus wallet count (deduped=" + consensus.getDedupedEntityCount()
                    + ", raw wallets=" + consensus.getDistinctWallets()
                    + ", window=" + effectiveWindow + "s) is below threshold="
                    + resolveMinConsensusEntities(risk) + (hardGate ? "." : " — boost-only mode, not blocking."));
        }
        if (consensusScoreFailed) {
            String prefix = hardGate ? "" : "[CONSENSUS_BOOST_ONLY_NOTICE] ";
            reasons.add(prefix + "Consensus score (" + safe(consensus.getConsensusScore()).setScale(3, RoundingMode.HALF_UP)
                    + ") is below threshold (" + safe(risk.getMinConsensusScore()).setScale(3, RoundingMode.HALF_UP) + ")"
                    + (hardGate ? "." : " — boost-only mode, not blocking."));
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
