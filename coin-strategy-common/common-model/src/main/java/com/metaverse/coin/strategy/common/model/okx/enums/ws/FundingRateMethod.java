package com.metaverse.coin.strategy.common.model.okx.enums.ws;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

@Getter
public enum FundingRateMethod {

    /**
     * 当期收
     */
    CURRENT_PERIOD("current_period"),
    /**
     * 跨期收
     */
    NEXT_PERIOD("next_period");

    private final String value;

    FundingRateMethod(final String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }


}
