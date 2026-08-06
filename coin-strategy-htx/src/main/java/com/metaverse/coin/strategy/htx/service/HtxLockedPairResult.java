package com.metaverse.coin.strategy.htx.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Outcome of {@link HtxTradingService#submitLockedPair}.
 * See {@code HTX_SPOT_EXECUTION_SDK_V1.md} §4.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HtxLockedPairResult {

    public enum Code {
        /** Both legs terminal-filled — locked-in. */
        OK,
        /** Buy filled, sell placed but did not reach terminal state within timeout; sellOrderId still tracked. */
        SELL_PENDING,
        /** All pre-checks passed; live trade disabled. No order sent. */
        DRY_RUN_OK,
        REJECT_PRICE_INVERSION,
        REJECT_DEPTH_BUY,
        REJECT_DEPTH_SELL,
        REJECT_DEPTH_BOTH,
        REJECT_MIN_NOTIONAL,
        /** 单笔交易 quote 名义超过 {@code htx.risk.max-single-quote-notional} 上限。 */
        REJECT_MAX_SINGLE_NOTIONAL,
        /**
         * 当日累计 quote 名义（含本单）超过 {@code htx.risk.max-daily-quote-notional} 上限；
         * 或当日累计读取失败（无法确认未超限）→ fail-closed 拒单。
         */
        REJECT_MAX_DAILY_NOTIONAL,
        REJECT_INVALID_INPUT,
        /** 实盘预检失败：enabled=true 但 spot-account-id 为空，拒绝下单。 */
        REJECT_PREFLIGHT,
        /** 写前落库失败 → fail-closed，未发任何真实订单。 */
        PERSIST_WRITE_FAILED,
        BUY_ORDER_REJECTED,
        /** Buy leg had 0 fill within timeout; cancelled cleanly. */
        BUY_WAIT_TIMEOUT,
        /**
         * Buy leg partial-filled at timeout; remainder cancelled; sell placed for the filled quantity
         * (recovery path). SellOrderId populated; buyFilledAmount populated.
         */
        BUY_PARTIAL_FILL_RECOVERED,
        /**
         * Sell leg rejected AFTER buy had already partially or fully filled — single-leg exposure.
         * Needs human intervention.
         */
        FAIL_AFTER_BUY_FILL,
        DEPTH_FETCH_FAILED,
        /**
         * v1.0.2 hotfix #5: queryOrder returned non-ok status consecutively for the configured
         * threshold. We refuse to cancel (order state is unknown → may be filled) and hand off
         * to the caller with the orderId so a human / dead-letter handler can reconcile.
         */
        QUERY_INFRA_ERROR
    }

    private Code code;
    private Long buyOrderId;
    private Long sellOrderId;
    /** Base-asset amount actually filled on the buy leg (v1.0.1). */
    private BigDecimal buyFilledAmount;
    /** Base-asset amount actually filled on the sell leg (v1.0.1). */
    private BigDecimal sellFilledAmount;
    private String message;

    public boolean success() {
        return code == Code.OK || code == Code.DRY_RUN_OK;
    }
}
