package com.metaverse.coin.strategy.job.grid.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 一个市场上的完整网格实例。
 */
@Data
@Builder
public class GridInstance {

    /** Polymarket conditionId */
    private String marketId;

    /** 市场标题 */
    private String marketTitle;

    /** YES token ID */
    private String yesTokenId;

    /** NO token ID */
    private String noTokenId;

    /** 网格中心价格 */
    private BigDecimal centerPrice;

    /** 网格间距 */
    private BigDecimal gridSpacing;

    /** 所有网格层 */
    private List<GridLevel> levels;

    /** 当前净库存（正=持有 YES shares，负=持有 NO shares） */
    private BigDecimal netInventory;

    /** 已实现利润（USDC） */
    private BigDecimal realizedProfitUsdc;

    /** 总成交次数 */
    private int totalFills;

    /** 网格状态: ACTIVE / PAUSED / STOPPED */
    private String status;

    /** 创建时间 */
    private Instant createdAt;

    /** 市场结算时间 */
    private Instant settlementTime;
}
