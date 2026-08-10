package com.metaverse.coin.strategy.htx.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Result of a leverage change or isolated long open/close cycle. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HtxContractTradeResult {

    public enum Code {
        OK,
        DRY_RUN_OK,
        LEVERAGE_OK,
        LEVERAGE_DRY_RUN_OK,
        CLOSE_PENDING,
        REJECT_INVALID_INPUT,
        REJECT_CONTRACT_NOT_ALLOWED,
        REJECT_CONTRACT_UNAVAILABLE,
        REJECT_PRICE_TICK,
        REJECT_DEPTH,
        REJECT_NOTIONAL,
        REJECT_LEVERAGE,
        REJECT_LIVE_GATE,
        REJECT_PREFLIGHT,
        REJECT_EXISTING_ORDER,
        REJECT_EXISTING_POSITION,
        REJECT_INSUFFICIENT_MARGIN,
        REJECT_PERSISTENCE,
        OPEN_ORDER_REJECTED,
        OPEN_WAIT_TIMEOUT,
        CLOSE_ORDER_REJECTED,
        EXPOSURE,
        QUERY_INFRA_ERROR
    }

    private Code code;
    private String contractCode;
    private Integer leverage;
    private Long cycleId;
    private Long openOrderId;
    private Long closeOrderId;
    private BigDecimal openFilledVolume;
    private BigDecimal closeFilledVolume;
    private String message;

    public boolean success() {
        return code == Code.OK || code == Code.DRY_RUN_OK
                || code == Code.LEVERAGE_OK || code == Code.LEVERAGE_DRY_RUN_OK;
    }
}
