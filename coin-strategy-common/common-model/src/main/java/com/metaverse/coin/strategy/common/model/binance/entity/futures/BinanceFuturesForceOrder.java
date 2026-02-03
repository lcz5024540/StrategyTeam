package com.metaverse.coin.strategy.common.model.binance.entity.futures;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Binance Futures Force Order (Liquidation Order)
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceFuturesForceOrder {
    private String symbol;
    private BigDecimal price;
    private BigDecimal origQty;
    private BigDecimal executedQty;
    
    @JsonProperty("avgPrice")
    @JsonAlias("averagePrice")
    private BigDecimal avgPrice;
    
    private String status;
    private String timeInForce;
    private String type;
    private String side;
    private Long time;
}
