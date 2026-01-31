package com.metaverse.coin.strategy.common.model.binance.entity.margin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceMarginAsset {
    private String assetFullName;
    private String assetName;
    private Boolean isBorrowable;
    private Boolean isMortgageable;
    private BigDecimal userMinBorrow;
    private BigDecimal userMinRepay;
}
