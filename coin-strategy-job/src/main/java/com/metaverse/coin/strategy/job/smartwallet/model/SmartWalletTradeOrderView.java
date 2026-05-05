package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

@Data
@Builder
public class SmartWalletTradeOrderView {
    private Long id;
    private Long positionId;
    private Long marketInfoId;
    private String executionAddress;
    private String walletAddress;
    private Long sourceFollowTaskId;
    private String marketId;
    private String marketSlug;
    private String marketUrl;
    private String externalMarketUrl;
    private String internalMarketUrl;
    private String marketLinkStatus;
    private String tokenId;
    private String question;
    private String outcome;
    private Long marketEndAt;
    private String marketEndAtBjt;
    private String marketLifecycle;
    private Boolean activeMarket;
    private Boolean closed;
    private Boolean acceptingOrders;
    private String normalizedCategory;
    private String sourceCategory;
    private BigDecimal entryQuantity;
    private BigDecimal entryAvgPrice;
    private BigDecimal entryCostUsdc;
    private BigDecimal entryFeeUsdc;
    private Date openedAt;
    private BigDecimal exitQuantity;
    private BigDecimal exitAvgPrice;
    private BigDecimal exitProceedsUsdc;
    private BigDecimal exitRealizedPnlUsdc;
    private BigDecimal exitFeeUsdc;
    private Date lastExitAt;
    private BigDecimal remainingQuantity;
    private BigDecimal remainingCostBasisUsdc;
    private BigDecimal lastMarkPrice;
    private BigDecimal unrealizedPnlUsdc;
    private BigDecimal settlementPrice;
    private BigDecimal settlementProceedsUsdc;
    private BigDecimal settlementPnlUsdc;
    private Date settledAt;
    private BigDecimal totalRealizedPnlUsdc;
    private BigDecimal totalPnlUsdc;
    private BigDecimal roiRate;
    private String status;
    private List<Long> entryOrderIds;
    private List<Long> exitOrderIds;
    private List<Long> fillIds;
}
