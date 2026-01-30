package com.metaverse.coin.strategy.common.model.okx.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

@Getter
public enum AlgoOrderType {

    /**
     * Spot grid
     */
    GRID("grid"),
    /**
     * Contract grid
     */
    CONTRACT_GRID("contract_grid"),
    /**
     * Conditional (Stop Order)
     */
    CONDITIONAL("conditional"),
    /**
     * OCO (One-cancels-the-other)
     */
    OCO("oco"),
    /**
     * Trigger Order
     */
    TRIGGER("trigger"),
    /**
     * Trailing Stop
     */
    MOVE_ORDER_STOP("move_order_stop"),
    /**
     * Iceberg
     */
    ICEBERG("iceberg"),
    /**
     * TWAP
     */
    TWAP("twap");

    private final String value;

    AlgoOrderType(final String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }


}
