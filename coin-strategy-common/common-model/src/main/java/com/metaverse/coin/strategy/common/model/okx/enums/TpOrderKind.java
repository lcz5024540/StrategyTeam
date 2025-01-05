package com.metaverse.coin.strategy.common.model.okx.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

@Getter
public enum TpOrderKind {

    CONDITION("condition"),
    LIMIT("limit");

    private final String value;

    TpOrderKind(final String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

}
