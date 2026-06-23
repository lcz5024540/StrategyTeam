package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class SignalRiskDecision {
    private boolean accepted;
    private boolean overrideApplied;
    private boolean manualAllowlistApplied;
    private String manualAllowlistReason;
    private BigDecimal stakeMultiplierApplied;
    private BigDecimal recommendedStakeUsdc;
    private String marketType;
    private String entryLane;
    private String exitIntent;
    private BigDecimal estimatedNetProfitabilityUsdc;
    private BigDecimal estimatedFeeUsdc;
    private BigDecimal estimatedSlippageUsdc;
    private BigDecimal followProbability;
    private BigDecimal calibratedFollowProbability;
    private BigDecimal slippageSpikeProbability;
    private Integer estimateSampleSize;
    private BigDecimal signalScore;
    private BigDecimal signalScoreLiquidityFactor;
    private BigDecimal signalScoreRiskPenalty;
    private BigDecimal signalScoreLeaderReliabilityFactor;
    /** 原始参与钱包数（历史兼容，勿直接用于风控决策） */
    private Integer consensusWallets;
    /** 实体去重后的参与实体数（真实共识强度，风控门控使用此字段） */
    private Integer consensusEntities;
    private BigDecimal consensusScore;
    private BigDecimal qualityWeightedConsensusScore;
    private BigDecimal topEntityWeightShare;
    private BigDecimal leaderFollowerScore;
    private BigDecimal entityIndependenceScore;
    private BigDecimal recentConsistencyScore;
    /** 是否检测到同一实体多钱包触发同一信号（告警标志） */
    private boolean hasSuspiciousMultiWallet;
    /** true = 追加加仓信号（已有仓位 + 高质量钱包），非新开仓 */
    private boolean scaleUpSignal;
    /** 本次应追加的金额（仅当 scaleUpSignal=true 时有意义） */
    private BigDecimal scaleUpAddOnStake;
    private String policyTag;
    private String strategyRouteMode;
    private Integer gateHighWaterMark;
    private String firstRejectGate;
    @Builder.Default
    private List<String> enabledStrategies = new ArrayList<>();
    @Builder.Default
    private List<String> matchedStrategies = new ArrayList<>();
    @Builder.Default
    private List<SignalStrategyCheckResult> strategyChecks = new ArrayList<>();
    @Builder.Default
    private List<String> reasons = new ArrayList<>();

    // ── P0-3: gross/net 双口径暴露信息 ──
    /** 市场毛暴露 (所有 outcome 总成本) */
    private BigDecimal grossMarketExposure;
    /** 市场净方向性暴露 (|YES_cost - NO_cost|) */
    private BigDecimal netDirectionalExposure;
    /** 该笔新订单是否降低净暴露 */
    private boolean reducesNetExposure;

    // ── W2-T2: Leader 滑动窗口胜率门状态信息 ──
    /** Leader 滑动窗口状态: ACTIVE / COOLING / SUSPENDED / null（门控未启用） */
    private String leaderRollingStatus;
    /** 该 Leader 滑动窗口实际跟单胜率（跟单后胜率，与 profile.winRate 不同） */
    private BigDecimal leaderRollingWinRate;
    /** 该 Leader 滑动窗口样本数 */
    private Integer leaderRollingSampleCount;
    /** 该 Leader 滑动窗口累计跟单 PnL（USDC） */
    private BigDecimal leaderRollingTotalPnlUsdc;

    // ── W2-T6: Leader 行为漂移门状态信息 ──
    /** Leader 行为漂移门状态: ACTIVE / DRIFTED / DRIFTED_SEVERE / null（门控未启用） */
    private String leaderDriftStatus;
    /** 当前 trade event 与 leader 历史画像 centroid 的 cosine 距离（0~2，越接近 0 越相似） */
    private BigDecimal leaderDriftCosineDistance;
    /** 用于构建画像 centroid 的历史样本数 */
    private Integer leaderDriftSampleCount;

    // ── W2-T4.3: 价格冲击三段式门控派生的下游路由提示 ──
    /**
     * 由 G11 价格冲击门导出, 供 {@code PolymarketOrderRequestFactory} 决定下单策略。
     * 取值集合：
     * <ul>
     *   <li>{@code NORMAL} / {@code null} ── 维持钱包默认 orderStrategy（TAKER / MAKER / ADAPTIVE）。</li>
     *   <li>{@code MAKER_FIRST} ── 中等滑点（100bps ≤ bps &lt; severeBps），降级 0.5x 仓位 + 优先挂 maker 单。</li>
     *   <li>{@code MAKER_ONLY} ── 重段滑点（severeBps ≤ bps &lt; rejectBps），降级 0.25x 仓位 + 强制 maker、
     *       到时撤单。</li>
     * </ul>
     * 详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md W2-T4。
     */
    private String priceImpactExecutionMode;

    /**
     * 第十四批 #1: 价格冲击 bps 的算法 source — "VWAP(OK)" / "VWAP(DRY_FALLBACK)" / "PROXY" / null.
     * 由 SignalRiskService 在 priceImpact 三段式段设置, FollowTaskService 透传到 Shadow recorder,
     * 落 smart_wallet_signal_shadow.entry_estimator_source 列, 供 ShadowVsLive 周报按 source 分桶.
     */
    private String priceImpactBpsSource;
}
