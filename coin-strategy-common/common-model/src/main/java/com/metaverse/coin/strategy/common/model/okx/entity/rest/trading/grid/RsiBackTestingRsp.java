package com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.grid;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.math.BigDecimal;
import java.util.List;

@Data
@ToString
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RsiBackTestingRsp {

    @JSONField(name = "triggerNum")
    @JsonProperty("triggerNum")
    private Integer triggerNum;

    @JSONField(name = "backTestingData")
    @JsonProperty("backTestingData")
    private List<BackTestingData> backTestingData;

    @Data
    @Builder
    @ToString
    @AllArgsConstructor
    @NoArgsConstructor
    public static class BackTestingData {
        @JSONField(name = "ts")
        @JsonProperty("ts")
        private Long ts;

        @JSONField(name = "px")
        @JsonProperty("px")
        private BigDecimal px;
        
        @JSONField(name = "rsi")
        @JsonProperty("rsi")
        private BigDecimal rsi;
    }
}
