package com.metaverse.coin.strategy.core.arbitrage.multi;

/** Supported exchanges for funding-rate arbitrage. */
public enum Exchange {
    BINANCE, OKX, HTX, BITGET, HYPERLIQUID;

    public static Exchange parse(String value) {
        if (value == null) throw new IllegalArgumentException("exchange is required");
        return valueOf(value.trim().toUpperCase().replace('-', '_'));
    }
}
