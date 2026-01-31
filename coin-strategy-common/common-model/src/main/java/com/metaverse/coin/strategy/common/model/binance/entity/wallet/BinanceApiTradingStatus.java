package com.metaverse.coin.strategy.common.model.binance.entity.wallet;

import lombok.Data;

@Data
public class BinanceApiTradingStatus {
    private Data data;

    @lombok.Data
    public static class Data {
        private Boolean isLocked;
        private Long plannedRecoverTime;
        private TriggerCondition triggerCondition;
        private Long updateTime;
    }

    @lombok.Data
    public static class TriggerCondition {
        private Integer GCR;
        private Integer IFER;
        private Integer UFR;
    }
}
