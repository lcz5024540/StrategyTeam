package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Data;

import java.util.Date;
import java.util.List;

@Data
public class SmartWalletReplayBacktestRequest {
    private List<Integer> windowDays = List.of(30, 60, 90);
    private Integer maxEventsPerWindow = 2000;
    private String datasetVersion = "polymarket-live-v1";
    private Boolean strictPointInTime = true;
    private Long randomSeed = 20260712L;
    private Date asOf;
}
