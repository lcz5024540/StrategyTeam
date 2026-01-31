package com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.signal;

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
public class SignalAlgoOrder {

    @JSONField(name = "algoId")
    @JsonProperty("algoId")
    private String algoId;

    @JSONField(name = "algoClOrdId")
    @JsonProperty("algoClOrdId")
    private String algoClOrdId;

    @JSONField(name = "instId")
    @JsonProperty("instId")
    private String instId;

    @JSONField(name = "signalChanId")
    @JsonProperty("signalChanId")
    private String signalChanId;

    @JSONField(name = "signalChanName")
    @JsonProperty("signalChanName")
    private String signalChanName;

    @JSONField(name = "lever")
    @JsonProperty("lever")
    private String lever;

    @JSONField(name = "investAmt")
    @JsonProperty("investAmt")
    private BigDecimal investAmt;

    @JSONField(name = "subOrdType")
    @JsonProperty("subOrdType")
    private String subOrdType;

    @JSONField(name = "ratio")
    @JsonProperty("ratio")
    private String ratio;

    @JSONField(name = "state")
    @JsonProperty("state")
    private String state;

    @JSONField(name = "cancelType")
    @JsonProperty("cancelType")
    private String cancelType;

    @JSONField(name = "cTime")
    @JsonProperty("cTime")
    private Long cTime;

    @JSONField(name = "uTime")
    @JsonProperty("uTime")
    private Long uTime;
}
