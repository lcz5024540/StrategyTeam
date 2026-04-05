package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class SignalRiskPresetCatalogView {
    private SignalRiskPresetView activeDefault;
    private List<SignalRiskPresetView> presets;
}
