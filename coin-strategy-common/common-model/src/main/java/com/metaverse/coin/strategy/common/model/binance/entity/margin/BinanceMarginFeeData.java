package com.metaverse.coin.strategy.common.model.binance.entity.margin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceMarginFeeData {
    private Integer vipLevel;
    private String coin;
    private Boolean transferIn;
    private Boolean borrowable;
    private BigDecimal dailyInterest;
    private BigDecimal yearlyInterest;
    private BigDecimal borrowLimit;
    private List<String> marginablePairs;
}
