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
public class BinanceSimpleEarnLockedProduct {
    private String projectId;
    private String asset;
    private String rewardAsset;
    private Integer duration;
    private BigDecimal interestRate;
    private String extraRewardAsset;
    private BigDecimal extraRewardAPR;
    private BigDecimal minPurchaseAmount; // quota.minimum
    private BigDecimal maxPurchaseAmount; // quota.totalPersonalQuota
    private String status;
    private Boolean autoSubscribe;
}
