package com.metaverse.coin.strategy.common.client.htx.model;

import lombok.Data;

import java.math.BigDecimal;

/** Current spot fee rates returned by HTX V2 reference API. */
@Data
public class HtxSpotFeeRate {
    private String symbol;
    private BigDecimal makerFeeRate;
    private BigDecimal takerFeeRate;
    private BigDecimal actualMakerRate;
    private BigDecimal actualTakerRate;
    private BigDecimal rpiFeeRate;
}
