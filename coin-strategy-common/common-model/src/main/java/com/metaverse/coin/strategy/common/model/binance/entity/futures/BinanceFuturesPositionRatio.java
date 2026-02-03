package com.metaverse.coin.strategy.common.model.binance.entity.futures;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceFuturesPositionRatio {
    private String symbol;
    private BigDecimal longShortRatio;
    private BigDecimal longPosition;
    private BigDecimal shortPosition;
    private Long timestamp;
}
