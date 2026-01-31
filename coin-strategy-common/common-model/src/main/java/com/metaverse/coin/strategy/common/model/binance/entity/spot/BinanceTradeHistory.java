package com.metaverse.coin.strategy.common.model.binance.entity.spot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceTradeHistory {
    private String symbol;
    private Long id;
    private Long orderId;
    private Long orderListId;
    private BigDecimal price;
    private BigDecimal qty;
    private BigDecimal quoteQty;
    private BigDecimal commission;
    private String commissionAsset;
    private Long time;
    private Boolean isBuyer;
    private Boolean isMaker;
    private Boolean isBestMatch;
}
