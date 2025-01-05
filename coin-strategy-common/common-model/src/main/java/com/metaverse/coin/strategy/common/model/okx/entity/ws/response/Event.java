package com.metaverse.coin.strategy.common.model.okx.entity.ws.response;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

import java.util.Arrays;

@Getter
public enum Event {

    LOGIN("login"),
    SUBSCRIBE("subscribe"),
    UNSUBSCRIBE("unsubscribe"),
    ERROR("error");

    private final String value;

    Event(final String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    public static Event fromValue(final String value) {
        return Arrays.stream(values()).filter(it -> it.getValue().equals(value)).findAny().orElse(null);
    }
}
