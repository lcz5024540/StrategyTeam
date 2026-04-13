package com.metaverse.coin.strategy.job.hedge.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/** REST API 视图 — 单个对冲持仓 */
@Data
@Builder
public class HedgePositionView {
    private Long id;
    private String marketId;
    private String question;
    private String status;
    private BigDecimal yesEntryPrice;
    private BigDecimal noEntryPrice;
    private BigDecimal probDeviation;
    private BigDecimal grossEdgeRate;
    private BigDecimal quantity;
    private BigDecimal totalCostUsdc;
    private BigDecimal expectedProfitUsdc;
    private BigDecimal realizedPnlUsdc;
    private String finalOutcome;
    private Date openedAt;
    private Date settledAt;
}
