package com.metaverse.coin.strategy.common.model.binance.entity.margin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceMarginInterestRateHistory {
    private String asset;
    private BigDecimal dailyInterestRate;
    private Long timestamp;
    private Integer vipLevel;
}
