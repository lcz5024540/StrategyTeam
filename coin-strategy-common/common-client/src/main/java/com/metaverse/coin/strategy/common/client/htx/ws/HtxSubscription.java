package com.metaverse.coin.strategy.common.client.htx.ws;

import lombok.Data;

/**
 * A registered WebSocket subscription descriptor.
 * <p>
 * v1 supports three canonical HTX topics:
 * <ul>
 *   <li>{@code market.$symbol.depth.step0} — incremental order book</li>
 *   <li>{@code market.$symbol.trade.detail} — public trades</li>
 *   <li>{@code orders#$accountId} — private order updates (requires auth channel)</li>
 * </ul>
 * The consumer callback receives the raw JSON string; DTO mapping is left to callers because
 * subscription payload schemas evolve.
 */
@Data
public class HtxSubscription {
    private final String topic;
    private final java.util.function.Consumer<String> callback;
}
