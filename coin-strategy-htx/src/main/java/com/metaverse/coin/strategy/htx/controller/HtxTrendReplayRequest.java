package com.metaverse.coin.strategy.htx.controller;

import lombok.Data;

import javax.validation.constraints.AssertTrue;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

/** Local-only request for a read-only historical trend replay; timestamps use Asia/Shanghai. */
@Data
public class HtxTrendReplayRequest {
    @NotNull
    private LocalDateTime startAt;
    @NotNull
    private LocalDateTime endAt;

    @AssertTrue(message = "endAt must be after startAt")
    public boolean isValidWindow() {
        return startAt == null || endAt == null || endAt.isAfter(startAt);
    }
}
