package com.metaverse.coin.strategy.common.model.binance.entity.margin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceMarginForceLiquidationRecord {
    private BigDecimal avgPrice;
    private BigDecimal executedQty;
    private Long orderId;
    private BigDecimal price;
    private BigDecimal qty;
    private String side;
    private String symbol;
    private String timeInForce;
    private Boolean isIsolated;
    private Long updatedTime;
}
