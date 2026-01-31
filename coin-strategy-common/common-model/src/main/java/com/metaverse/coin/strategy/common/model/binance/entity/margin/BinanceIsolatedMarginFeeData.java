package com.metaverse.coin.strategy.common.model.binance.entity.margin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceIsolatedMarginFeeData {
    private Integer vipLevel;
    private String symbol;
    private String leverage;
    private List<IsolatedData> data;
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IsolatedData {
        private String coin;
        private BigDecimal dailyInterest;
        private BigDecimal borrowLimit;
    }
}
