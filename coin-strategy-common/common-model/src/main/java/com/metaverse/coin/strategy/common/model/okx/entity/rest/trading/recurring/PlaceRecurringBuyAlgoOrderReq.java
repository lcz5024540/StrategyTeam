package com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.recurring;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IOkxRestReq;
import lombok.*;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@ToString
@AllArgsConstructor
@NoArgsConstructor
public class PlaceRecurringBuyAlgoOrderReq implements IOkxRestReq {

    @JSONField(name = "stgyName")
    @JsonProperty("stgyName")
    private String stgyName;

    @JSONField(name = "recurringList")
    @JsonProperty("recurringList")
    private List<RecurringAlgoOrder.RecurringAsset> recurringList;

    @JSONField(name = "period")
    @JsonProperty("period")
    private String period;

    @JSONField(name = "recurringDay")
    @JsonProperty("recurringDay")
    private String recurringDay;

    @JSONField(name = "recurringTime")
    @JsonProperty("recurringTime")
    private String recurringTime;

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

    @JSONField(name = "algoClOrdId")
    @JsonProperty("algoClOrdId")
    private String algoClOrdId;

    @JSONField(name = "tag")
    @JsonProperty("tag")
    private String tag;
}
