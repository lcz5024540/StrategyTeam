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
public class BinanceSimpleEarnFlexibleProduct {
    private String asset;
    private String productId;
    private BigDecimal latestAnnualPercentageRate;
    private Map<String, BigDecimal> tierAnnualPercentageRate;
    private Boolean canPurchase;
    private Boolean canRedeem;
    private Boolean isSoldOut;
    private Boolean hot;
    private BigDecimal minPurchaseAmount;
    private BigDecimal maxPurchaseAmount;
    private BigDecimal minRedeemAmount;
    private String status;
}
