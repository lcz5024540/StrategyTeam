package com.metaverse.coin.strategy.common.model.binance.entity.simpleearn;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceSimpleEarnFlexiblePosition {
    private String asset;
    private String productId;
    private BigDecimal totalAmount;
    private BigDecimal collateralAmount;
    private Map<String, BigDecimal> tierAnnualPercentageRate;
    private BigDecimal latestAnnualPercentageRate;
    private BigDecimal yesterdayAirdropPercentageRate;
    private String airDropAsset;
    private Boolean canRedeem;
    private BigDecimal yesterdayRealTimeRewards;
    private BigDecimal cumulativeBonusRewards;
    private BigDecimal cumulativeRealTimeRewards;
    private BigDecimal cumulativeTotalRewards;
    private Boolean autoSubscribe;
}
