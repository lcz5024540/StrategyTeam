package com.metaverse.coin.strategy.common.client.htx.model;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

import java.math.BigDecimal;

/**
 * HTX spot order snapshot returned by /v1/order/orders/{id} or place-order.
 */
@Data
public class HtxOrder {
    @JSONField(name = "id", alternateNames = {"order-id", "orderId"})
    private long orderId;
    @JSONField(name = "client-order-id", alternateNames = {"clientOrderId"})
    private String clientOrderId;
    private String symbol;
    @JSONField(name = "account-id", alternateNames = {"accountId"})
    private String accountId;
    /** buy-limit / buy-market / sell-limit / sell-market */
    private String type;
    private BigDecimal price;
    /** For LIMIT: base amount. For BUY-MARKET: quote amount. */
    private BigDecimal amount;
    @JSONField(name = "field-amount", alternateNames = {"filled-amount", "filledAmount"})
    private BigDecimal filledAmount;
    @JSONField(name = "field-cash-amount", alternateNames = {"filled-cash-amount", "filledCashAmount"})
    private BigDecimal filledCashAmount;
    @JSONField(name = "field-fees", alternateNames = {"filled-fees", "filledFees"})
    private BigDecimal filledFees;
    /** submitted / partial-filled / filled / partial-canceled / canceled / rejected */
    private String state;
    @JSONField(name = "created-at", alternateNames = {"createdAt"})
    private long createdAt;
}
