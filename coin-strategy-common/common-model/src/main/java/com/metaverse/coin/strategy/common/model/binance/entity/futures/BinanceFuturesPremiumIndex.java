package com.metaverse.coin.strategy.common.model.binance.entity.futures;

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
public class BinanceFuturesPremiumIndex {
    private String symbol;
    private BigDecimal markPrice;
    private BigDecimal indexPrice;
    private BigDecimal estimatedSettlePrice;
    private BigDecimal lastFundingRate;
    private Long nextFundingTime;
    private BigDecimal interestRate;
    private Long time;
}
