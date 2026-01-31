package com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.signal;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IOkxRestReq;
import lombok.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@Builder
@ToString
@AllArgsConstructor
@NoArgsConstructor
public class PlaceSignalAlgoOrderReq implements IOkxRestReq {

    @JSONField(name = "signalChanId")
    @JsonProperty("signalChanId")
    private String signalChanId;

    @JSONField(name = "lever")
    @JsonProperty("lever")
    private String lever;

    @JSONField(name = "investAmt")
    @JsonProperty("investAmt")
    private BigDecimal investAmt;

    @JSONField(name = "subOrdType")
    @JsonProperty("subOrdType")
    private String subOrdType; // 1: buy/long, 2: sell/short ? or limit/market? Check docs if possible. Assuming String for now.

    @JSONField(name = "includeAll")
    @JsonProperty("includeAll")
    private Boolean includeAll;

    @JSONField(name = "instIds")
    @JsonProperty("instIds")
    private List<String> instIds;

    @JSONField(name = "ratio")
    @JsonProperty("ratio")
    private String ratio;

    @JSONField(name = "entrySettingParam")
    @JsonProperty("entrySettingParam")
    private Map<String, Object> entrySettingParam; // Use Map for flexible params

    @JSONField(name = "exitSettingParam")
    @JsonProperty("exitSettingParam")
    private Map<String, Object> exitSettingParam;
}
