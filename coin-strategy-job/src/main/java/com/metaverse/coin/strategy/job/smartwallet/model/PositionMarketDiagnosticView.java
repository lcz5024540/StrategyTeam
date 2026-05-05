package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
@Builder
public class PositionMarketDiagnosticView {
    private Long positionId;
    private Long orderId;
    private String lookupStatus;
    private String userProvidedUrl;
    private String userProvidedEventSlug;
    private Long marketInfoId;
    private String positionStatus;
    private String orderStatus;
    private String marketId;
    private String question;
    private String outcome;
    private String marketSlug;
    private String tokenId;
    private Integer indexSet;
    private Long marketEndAt;
    private String marketLifecycle;
    private Boolean closed;
    private Boolean acceptingOrders;
    private String resolvedOutcome;
    private String abnormalMarketReason;
    private Long followTaskId;
    private Long sourceEventId;
    private String sourceWalletAddress;
    private String externalTradeId;
    private String eventSide;
    private BigDecimal eventPrice;
    private BigDecimal eventSizeUsdc;
    private Date eventOpenedAt;
    private Boolean eventExactConditionMatched;
    private Boolean eventOutcomeTokenMatched;
    private String eventMatchedTokenId;
    private String eventMatchedQuestion;
    private String diagnosticNote;
}
