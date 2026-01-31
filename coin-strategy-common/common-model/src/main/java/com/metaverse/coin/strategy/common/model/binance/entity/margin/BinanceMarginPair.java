package com.metaverse.coin.strategy.common.model.binance.entity.margin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceMarginPair {
    private Long id;
    private String symbol;
    private String base;
    private String quote;
    private Boolean isMarginTrade;
    private Boolean isBuyAllowed;
    private Boolean isSellAllowed;
}
