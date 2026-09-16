package com.metaverse.coin.strategy.core.arbitrage.multi;

import java.math.BigDecimal;
import java.time.Instant;

/** Supplies a fresh public L2 execution quote for a Paper order. */
public interface PaperExecutionQuoteProvider {
    ExecutionQuote quote(Exchange exchange, String symbol, HedgeSide side,
            BigDecimal quantity, Instant now);

    record ExecutionQuote(Instant bookReceivedAt, String bookFingerprint,
            OrderBookSweep.Result sweep, BigDecimal feeRate) {
    }
}
