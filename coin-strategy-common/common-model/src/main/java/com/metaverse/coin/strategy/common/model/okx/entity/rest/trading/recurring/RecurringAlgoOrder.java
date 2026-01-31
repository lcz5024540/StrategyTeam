package com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.recurring;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@ToString
@AllArgsConstructor
@NoArgsConstructor
public class RecurringAlgoOrder {

    @JSONField(name = "algoId")
    @JsonProperty("algoId")
    private String algoId;

    @JSONField(name = "algoClOrdId")
    @JsonProperty("algoClOrdId")
    private String algoClOrdId;

    @JSONField(name = "instId")
    @JsonProperty("instId")
    private String instId;

    @JSONField(name = "stgyName")
    @JsonProperty("stgyName")
    private String stgyName;

    @JSONField(name = "recurringList")
    @JsonProperty("recurringList")
    private List<RecurringAsset> recurringList;

    @JSONField(name = "period")
    @JsonProperty("period")
    private String period;

    @JSONField(name = "recurringDay")
    @JsonProperty("recurringDay")
    private String recurringDay;

    @JSONField(name = "recurringHour")
    @JsonProperty("recurringHour")
    private String recurringHour; // Assuming recurringTime splits into hour/minute or similar

    @JSONField(name = "timeZone")
    @JsonProperty("timeZone")
    private String timeZone;

    @JSONField(name = "amt")
    @JsonProperty("amt")
    private BigDecimal amt;

    @JSONField(name = "investmentCcy")
    @JsonProperty("investmentCcy")
    private String investmentCcy;

    @JSONField(name = "tdMode")
    @JsonProperty("tdMode")
    private String tdMode;

    @JSONField(name = "state")
    @JsonProperty("state")
    private String state;

    @JSONField(name = "cTime")
    @JsonProperty("cTime")
    private Long cTime;

    @JSONField(name = "uTime")
    @JsonProperty("uTime")
    private Long uTime;

    @Data
    @Builder
    @ToString
    @AllArgsConstructor
    @NoArgsConstructor
    public static class RecurringAsset {
        @JSONField(name = "ccy")
        @JsonProperty("ccy")
        private String ccy;

        @JSONField(name = "ratio")
        @JsonProperty("ratio")
        private BigDecimal ratio;
    }
}
