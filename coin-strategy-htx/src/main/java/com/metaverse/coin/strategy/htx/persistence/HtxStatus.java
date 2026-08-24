package com.metaverse.coin.strategy.htx.persistence;

/** htx_locked_pair.status 与 htx_order.intent_status 的取值。 */
public final class HtxStatus {
    private HtxStatus() {}
    // pair.status
    public static final String NEW = "NEW";
    public static final String BUY_SENT = "BUY_SENT";
    public static final String BUY_FILLED = "BUY_FILLED";
    public static final String SELL_SENT = "SELL_SENT";
    public static final String DONE = "DONE";
    public static final String SELL_PENDING = "SELL_PENDING";
    public static final String EXPOSED = "EXPOSED";
    public static final String BUY_TIMEOUT = "BUY_TIMEOUT";
    public static final String BUY_REJECTED = "BUY_REJECTED";
    public static final String REJECTED = "REJECTED";
    // market-maker（做市）状态
    public static final String MM_RESTING_BUY = "MM_RESTING_BUY";
    public static final String MM_RESTING_SELL = "MM_RESTING_SELL";
    public static final String MM_DONE = "MM_DONE";
    public static final String MM_DUST = "MM_DUST";
    public static final String MM_CANCELLED_BUY = "MM_CANCELLED_BUY";
    /** htx_locked_pair.strategy 取值：做市单。执行 SDK 单为 NULL。 */
    public static final String STRATEGY_MARKET_MAKER = "MARKET_MAKER";
    // order.intent_status
    public static final String INTENT_PENDING_SEND = "PENDING_SEND";
    public static final String INTENT_SENT = "SENT";
    public static final String INTENT_SEND_FAILED = "SEND_FAILED";
    public static final String INTENT_TERMINAL = "TERMINAL";
    // exposure.status
    public static final String EXP_OPEN = "OPEN";
    public static final String EXP_FLATTENING = "FLATTENING";
    public static final String EXP_FLATTENED = "FLATTENED";
    public static final String EXP_MANUAL = "MANUAL";
    /** htx_exposure.exposed_side（VARCHAR(8)）中的现货 base 多头敞口。 */
    public static final String EXPOSED_SIDE_BASE_LONG = "LONG";
}
