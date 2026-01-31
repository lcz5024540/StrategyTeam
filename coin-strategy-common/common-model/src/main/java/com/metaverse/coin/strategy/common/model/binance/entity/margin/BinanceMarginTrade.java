package com.metaverse.coin.strategy.common.model.binance.entity.margin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceMarginTrade {
    private String symbol;
    private Long id;
    private Long orderId;
    private BigDecimal price;
    private BigDecimal qty;
    private BigDecimal commission;
    private String commissionAsset;
    private Long time;
    private Boolean isBuyer;
    private Boolean isMaker;
    private Boolean isBestMatch;
    private Boolean isIsolated;
}
