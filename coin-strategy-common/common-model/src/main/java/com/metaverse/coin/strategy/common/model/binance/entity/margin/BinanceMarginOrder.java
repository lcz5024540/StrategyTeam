package com.metaverse.coin.strategy.common.model.binance.entity.margin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceMarginOrder {
    private Long orderId;
    private String clientOrderId;
    private String symbol;
    private String side;
    private String status;
    private String type;
    private String timeInForce;
    private Boolean isIsolated;
    private BigDecimal price;
    private BigDecimal origQty;
    private BigDecimal executedQty;
    private BigDecimal cummulativeQuoteQty;
    private BigDecimal stopPrice;
    private BigDecimal icebergQty;
    private Long time;
    private Long updateTime;
    private Boolean isWorking;
    private Long accountId; // For isolated margin sometimes
}
