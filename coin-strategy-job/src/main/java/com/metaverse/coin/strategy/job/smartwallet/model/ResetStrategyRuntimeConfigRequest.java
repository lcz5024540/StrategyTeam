package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ResetStrategyRuntimeConfigRequest {
    private List<String> keys = new ArrayList<>();
}
