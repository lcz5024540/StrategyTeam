package com.metaverse.coin.strategy.common.model.okx.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

@Getter
public enum TriggerCondition {

    CROSS_UP("cross_up"),
    CROSS_DOWN("cross_down"),
    ABOVE("above"),
    BELOW("below"),
    CROSS("cross");

    private final String value;

    TriggerCondition(final String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }


}
