package com.metaverse.coin.strategy.common.model.okx.enums.ws;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

@Getter
public enum MgnMode {

    /**
     * 全仓
     */
    CROSS("cross"),
    /**
     * 逐仓
     */
    ISOLATED("isolated");

    private final String value;

    MgnMode(final String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }


}
