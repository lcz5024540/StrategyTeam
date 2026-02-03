package com.metaverse.coin.strategy.common.model.binance.entity.futures;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceFuturesAccountTrade {
    private String symbol;
    private Long id;
    private Long orderId;
    private String side;
    private BigDecimal price;
    private BigDecimal qty;
    private BigDecimal quoteQty;
    private BigDecimal realizedPnl;
    private String marginAsset;
    private BigDecimal commission;
    private String commissionAsset;
    private Long time;
    private Boolean buyer;
    private Boolean maker;
    private String positionSide;
}
