package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class UpdateStrategyRuntimeConfigRequest {
    private String updatedBy;
    private List<Item> items = new ArrayList<>();

    @Data
    public static class Item {
        private String key;
        private String value;
    }
}
