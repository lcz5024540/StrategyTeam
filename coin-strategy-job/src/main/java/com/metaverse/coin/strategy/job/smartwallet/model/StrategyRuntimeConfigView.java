package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class StrategyRuntimeConfigView {
    private String strategyCode;
    private String key;
    private String value;
    private String defaultValue;
    private String valueType;
    private String groupName;
    private String label;
    private String description;
    private boolean editable;
    private String source;
    private String updatedAt;
}
