package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Data;

import java.util.List;

@Data
public class SmartWalletReplayBacktestRequest {
    private List<Integer> windowDays = List.of(30, 60, 90);
    private Integer maxEventsPerWindow = 2000;
}
