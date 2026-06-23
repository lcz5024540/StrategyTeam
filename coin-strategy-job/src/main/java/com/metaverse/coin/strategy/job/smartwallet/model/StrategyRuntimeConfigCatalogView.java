package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class StrategyRuntimeConfigCatalogView {
    private String strategyCode;
    private List<GroupView> groups;
    private List<StrategyRuntimeConfigView> items;

    @Data
    @Builder
    public static class GroupView {
        private String name;
        private String label;
        private String description;
    }
}
