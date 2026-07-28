package com.metaverse.coin.strategy.common.client.htx.model;

import lombok.Data;

import java.math.BigDecimal;

/**
 * HTX spot order snapshot returned by /v1/order/orders/{id} or place-order.
 */
@Data
public class HtxOrder {
    private long orderId;
    private String clientOrderId;
    private String symbol;
    private String accountId;
    /** buy-limit / buy-market / sell-limit / sell-market */
    private String type;
    private BigDecimal price;
    /** For LIMIT: base amount. For BUY-MARKET: quote amount. */
    private BigDecimal amount;
    private BigDecimal filledAmount;
    private BigDecimal filledCashAmount;
    private BigDecimal filledFees;
    /** submitted / partial-filled / filled / partial-canceled / canceled / rejected */
    private String state;
    private long createdAt;
}
