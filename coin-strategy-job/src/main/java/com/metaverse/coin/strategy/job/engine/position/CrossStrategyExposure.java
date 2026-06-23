package com.metaverse.coin.strategy.job.engine.position;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 跨策略暴露检查结果。
 * 用于检测同一 market 上不同策略的仓位冲突。
 */
@Data
@Builder
public class CrossStrategyExposure {

    /** 被检查的 marketId */
    private String marketId;

    /** 该 market 上的持仓列表（来自不同策略） */
    private List<UnifiedPosition> positions;

    /** 该 market 上的净暴露（USDC，正=偏多，负=偏空） */
    private BigDecimal netExposureUsdc;

    /** 是否存在反向持仓（不同策略在同一 market 上方向相反） */
    private boolean hasConflict;

    /** 持仓的策略数 */
    private int strategiesInvolved;
}
