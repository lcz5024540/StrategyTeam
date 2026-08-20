package com.metaverse.coin.strategy.core.arbitrage.multi;

import java.math.BigDecimal;
import java.time.Instant;

public record PaperHedgePosition(String positionId, CrossExchangeOpportunity opportunity,
        PaperOrder longOrder, PaperOrder shortOrder, BigDecimal hedgedQuantity,
        Status status, Instant openedAt, Instant updatedAt, String message) {
    public enum Status { CREATED, OPENING_LONG, OPENING_SHORT, OPENED, PARTIALLY_FILLED,
        COMPENSATING, ROLLED_BACK, CLOSING, CLOSED, MANUAL_INTERVENTION }
}
