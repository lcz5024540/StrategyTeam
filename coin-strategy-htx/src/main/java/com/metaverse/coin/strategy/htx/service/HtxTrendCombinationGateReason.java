package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.Regime;
import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.TrendCandidateSignal;

/** Stable storage codes for combination decisions; Chinese text is the Dashboard-facing description. */
public enum HtxTrendCombinationGateReason {
    NO_ENABLED_POLICY("没有启用的组合策略"),
    NO_DIRECTION_SIGNAL("没有明确方向信号"),
    MARKET_CHOP("震荡行情，不开趋势仓"),
    LOW_VOLATILITY("波动不足，预期收益难覆盖成本"),
    MISSING_BOX_BREAKOUT("未形成箱体突破"),
    MISSING_DONCHIAN_BREAKOUT("未形成通道突破"),
    MISSING_BREAKOUT_SOURCE("缺少有效历史突破来源"),
    FIB_RETRACE_OUTSIDE_ZONE("价格未回踩至 Fibonacci 区间"),
    FIB_RECLAIM_NOT_CONFIRMED("回踩后尚未重新转强"),
    POLICY_NOT_MATCHED("组合未匹配启用政策"),
    FACTOR_SNAPSHOT_FAILED("因子快照落库失败"),
    LOWER_PRIORITY_FOR_SYMBOL("同币种存在更高分组合");

    private final String zhDescription;

    HtxTrendCombinationGateReason(String zhDescription) {
        this.zhDescription = zhDescription;
    }

    public String code() { return name(); }
    public String zhDescription() { return zhDescription; }

    static HtxTrendCombinationGateReason classifyNoCandidate(TrendCandidateSignal signal) {
        if (signal == null || signal.direction() == HtxTrendEnsembleSignalCalculator.Direction.FLAT) {
            return NO_DIRECTION_SIGNAL;
        }
        if (signal.regime() == Regime.LOW_VOLATILITY) {
            return LOW_VOLATILITY;
        }
        if (signal.regime() == Regime.RANGE) {
            return MARKET_CHOP;
        }
        return MISSING_BOX_BREAKOUT;
    }
}
