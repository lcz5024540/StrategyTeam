package com.metaverse.coin.strategy.common.model.binance.entity.simpleearn;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceSimpleEarnLockedPosition {
    private String positionId;
    private String parentPositionId;
    private String projectId; // Also productId
    private String asset;
    private BigDecimal amount; // or principal
    private BigDecimal purchaseTime;
    private String duration; // String or Integer? usually Integer days, but sometimes string
    private Long startTime;
    private Long endTime;
    private Long createTimestamp;
    private Long nextPay;
    private Long nextPayDate;
    private Integer payPeriod;
    private Long rewardsEndDate;
    private Long deliverDate;
    private Integer redeemPeriod;
    private String redeemTo; // SPOT or FUNDING
    private Boolean autoSubscribe;
    private String status; // HOLDING, REDEEMING, etc.
    private String type; // CUSTOMIZED_FIXED
    private BigDecimal interestRate;
    private BigDecimal rewardAmt;
    private String extraRewardAsset;
    private BigDecimal extraRewardAPR;
    private BigDecimal estExtraRewardAmt;
    private BigDecimal redeemAmountEarly;
    private BigDecimal redeemingAmt;
    private Long partialAmtDeliverDate;
    private Boolean canRedeemEarly;
    private Boolean canFastRedemption;
    private Boolean canReStake;
    private Boolean canTransfer;
}
