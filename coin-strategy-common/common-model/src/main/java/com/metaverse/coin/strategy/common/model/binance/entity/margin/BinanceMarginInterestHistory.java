package com.metaverse.coin.strategy.common.model.binance.entity.margin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceMarginInterestHistory {
    private Long txId;
    private Long interestAccuredTime;
    private String asset;
    private BigDecimal rawAsset; // Principal
    private BigDecimal principal;
    private BigDecimal interest;
    private String interestRate;
    private String type;
    private String isolatedSymbol;
}
