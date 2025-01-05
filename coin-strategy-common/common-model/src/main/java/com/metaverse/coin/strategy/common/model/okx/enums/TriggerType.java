package com.metaverse.coin.strategy.common.model.okx.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

@Getter
public enum TriggerType {

    MANUAL("manual"),
    AUTO("auto");

    private final String value;

    TriggerType(final String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }


}
