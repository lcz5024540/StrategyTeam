package com.metaverse.coin.strategy.common.model.binance.entity.futures;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceFuturesOpenInterestHist {
    private String symbol;
    private String pair;
    private BigDecimal sumOpenInterest;
    private BigDecimal sumOpenInterestValue;
    private Long timestamp;
}
