package com.metaverse.coin.strategy.common.api.model;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class UnifiedOrder {
    private String orderId;
    private String clientOrderId;
    private String symbol;
    private Side side;
    private OrderType type;
    private BigDecimal price;
    private BigDecimal quantity;
    private BigDecimal executedQty;
    private BigDecimal cumQuote; // Cumulative quote asset transacted quantity
    private OrderStatus status;
    private Long time;
    private Long updateTime;
}
