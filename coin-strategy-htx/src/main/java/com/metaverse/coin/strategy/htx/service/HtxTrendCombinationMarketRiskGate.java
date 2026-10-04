package com.metaverse.coin.strategy.htx.service;

/**
 * Pure market-risk gate shared by live combination execution and frozen-data diagnostics.
 *
 * <p>A combination already carries its local EMA, momentum, ATR, ADX and structure evidence.
 * This gate has one deliberately narrow responsibility: prevent it from opening against a
 * sufficiently broad market move.  It does not promote a candidate and it does not replace
 * the order-book, account, margin or position checks owned by the execution service.</p>
 */
public final class HtxTrendCombinationMarketRiskGate {

    private HtxTrendCombinationMarketRiskGate() {
    }

    public static boolean opposesBroadMarket(String direction, String marketRegime) {
        return ("LONG".equals(direction) && "BROAD_DOWN".equals(marketRegime))
                || ("SHORT".equals(direction) && "BROAD_UP".equals(marketRegime));
    }
}
