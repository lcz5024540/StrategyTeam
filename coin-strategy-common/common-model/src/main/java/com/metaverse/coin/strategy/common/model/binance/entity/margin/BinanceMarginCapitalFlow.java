package com.metaverse.coin.strategy.common.model.binance.entity.margin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceMarginCapitalFlow {
    private Long id; // tranId
    private Long tranId;
    private String asset;
    private String symbol;
    private String type;
    private BigDecimal amount;
    private Long timestamp;
}
