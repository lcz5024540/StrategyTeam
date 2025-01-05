package com.metaverse.coin.strategy.common.model.okx.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

@Getter
public enum TriggerStrategy {

    INSTANT("instant"),
    PRICE("price"),
    RSI("rsi");

    private final String value;

    TriggerStrategy(final String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }


}
