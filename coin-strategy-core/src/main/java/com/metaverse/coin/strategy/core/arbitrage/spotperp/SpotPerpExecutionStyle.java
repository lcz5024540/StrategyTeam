package com.metaverse.coin.strategy.core.arbitrage.spotperp;

/**
 * Paper execution style for the spot-long / perp-short hedge.
 *
 * <p>Live orders are not implemented. Maker fills in the backtest are assumed
 * at the passive quote because public funding history has no L2.</p>
 */
public enum SpotPerpExecutionStyle {
    /** Current engine: sequential spot buy MARKET IOC, then perp sell MARKET IOC. */
    TAKER_IOC(false, false),
    /** Both legs rest at the passive quote. */
    MAKER_LIMIT(true, true),
    /** Keep sequential hedge: rest spot, take perp once spot is assumed filled. */
    MIXED_SPOT_MAKER_PERP_TAKER(true, false),
    /** Take spot immediately, rest the perp short (fee-saving mixed). */
    MIXED_SPOT_TAKER_PERP_MAKER(false, true);

    private final boolean spotMaker;
    private final boolean perpMaker;

    SpotPerpExecutionStyle(boolean spotMaker, boolean perpMaker) {
        this.spotMaker = spotMaker;
        this.perpMaker = perpMaker;
    }

    public boolean spotMaker() {
        return spotMaker;
    }

    public boolean perpMaker() {
        return perpMaker;
    }

    public boolean rests() {
        return spotMaker || perpMaker;
    }

    public String spotOrderType() {
        return spotMaker ? "LIMIT" : "MARKET_IOC";
    }

    public String perpOrderType() {
        return perpMaker ? "LIMIT" : "MARKET_IOC";
    }
}
