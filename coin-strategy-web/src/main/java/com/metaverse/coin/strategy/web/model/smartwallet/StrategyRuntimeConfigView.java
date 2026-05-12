package com.metaverse.coin.strategy.web.model.smartwallet;
import java.util.Date;
public record StrategyRuntimeConfigView(Long id, String strategyCode, String configKey, String configValue,
    String defaultValue, String description, String valueType, Date updatedAt) {}
