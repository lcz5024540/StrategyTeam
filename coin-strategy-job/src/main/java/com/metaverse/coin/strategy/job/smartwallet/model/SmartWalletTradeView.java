package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

@Data
@Builder
public class SmartWalletTradeView {
    private Long tradeId;
    private String executionAddress;
    private String marketId;
    private String marketSlug;
    private String tokenId;
    private String question;
    private String outcome;
    private String status;
    private String netSide;
    private BigDecimal entryQuantity;
    private BigDecimal entryAvgPrice;
    private BigDecimal entryCostUsdc;
    private BigDecimal exitQuantity;
    private BigDecimal exitAvgPrice;
    private BigDecimal exitProceedsUsdc;
    private BigDecimal remainingQuantity;
    private BigDecimal realizedPnlUsdc;
    private BigDecimal unrealizedPnlUsdc;
    private BigDecimal totalPnlUsdc;
    private BigDecimal roiRate;
    private BigDecimal roiBaseUsdc;
    private String roiAnomalyReason;
    private BigDecimal lastMarkPrice;
    private String exitReason;
    private Integer entryOrderCount;
    private Integer exitOrderCount;
    private List<Long> entryOrderIds;
    private List<Long> exitOrderIds;
    private Long marketEndAt;
    private String marketEndAtBjt;
    private Date openedAt;
    private Date lastFilledAt;
    private Date closedAt;
}
