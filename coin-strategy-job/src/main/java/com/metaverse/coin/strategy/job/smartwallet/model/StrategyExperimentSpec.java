package com.metaverse.coin.strategy.job.smartwallet.model;

import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import lombok.Data;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class StrategyExperimentSpec {
    private String datasetVersion = "polymarket-live-v1";
    private String featureVersion = "point-in-time-v1";
    private String modelVersion = "rules-v1";
    private Date asOf;
    private Integer trainDays = 60;
    private Integer validationDays = 14;
    private Integer testDays = 14;
    private Integer embargoDays = 7;
    private Integer foldCount = 4;
    private Integer maxEventsPerFold = 2000;
    private Long randomSeed = 20260712L;
    private SmartWalletProperties.SignalRisk championPolicy;
    private SmartWalletProperties.SignalRisk candidatePolicy;
    private Map<String, Object> costScenario = new LinkedHashMap<>();
}
