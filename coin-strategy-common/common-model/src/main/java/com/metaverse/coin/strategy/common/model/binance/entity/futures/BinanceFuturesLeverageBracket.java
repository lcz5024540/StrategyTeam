package com.metaverse.coin.strategy.common.model.binance.entity.futures;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceFuturesLeverageBracket {
    private String symbol;
    private List<Bracket> brackets;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Bracket {
        private Integer bracket;
        private Integer initialLeverage;
        private BigDecimal notionalCap;
        private BigDecimal notionalFloor;
        private BigDecimal maintMarginRatio;
        private BigDecimal cum;
    }
}
