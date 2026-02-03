package com.metaverse.coin.strategy.common.model.binance.entity.futures;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceFuturesTradingStatus {
    private Boolean isLocked;
    private String plannedType;
    private Long plannedStartedAt;
    private Long plannedEndAt;
    private Map<String, List<Indicator>> indicators;
    private Long updateTime;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Indicator {
        private String i; // Indicator name (e.g., "UFR")
        private BigDecimal v; // Value
        private BigDecimal t; // Trigger value
    }
}
