package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

@Data
@Builder
public class OrderDiagnosticView {
    private Long orderId;
    private String lookupStatus;
    private Long followTaskId;
    private Long positionId;
    private String walletAddress;
    private String executionAddress;
    private String externalOrderId;
    private String status;
    private String side;
    private String orderType;
    private BigDecimal price;
    private BigDecimal size;
    private BigDecimal notionalUsdc;
    private Date placedAt;
    private Date filledAt;
    private Long marketInfoId;
    private String marketId;
    private String marketSlug;
    private String question;
    private String outcome;
    private String tokenId;
    private Long marketEndAt;
    private String marketLifecycle;
    private Boolean closed;
    private Boolean acceptingOrders;
    private String abnormalMarketReason;
    private Long sourceEventId;
    private String eventWalletAddress;
    private String externalTradeId;
    private String eventSide;
    private BigDecimal eventPrice;
    private BigDecimal eventSizeUsdc;
    private Date eventOpenedAt;
    private List<OrderSimilarTradeEventView> similarTradeEvents;
}
