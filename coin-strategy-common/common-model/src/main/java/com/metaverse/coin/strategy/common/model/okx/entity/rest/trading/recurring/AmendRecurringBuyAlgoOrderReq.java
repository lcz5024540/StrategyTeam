package com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.recurring;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IOkxRestReq;
import lombok.*;

import java.math.BigDecimal;

@Data
@ToString
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AmendRecurringBuyAlgoOrderReq implements IOkxRestReq {

    @NonNull
    @JSONField(name = "algoId")
    @JsonProperty("algoId")
    private String algoId;

    @NonNull
    @JSONField(name = "cxlOnFail")
    @JsonProperty("cxlOnFail")
    private Boolean cxlOnFail;

    @JSONField(name = "amt")
    @JsonProperty("amt")
    private BigDecimal amt;

    @JSONField(name = "action")
    @JsonProperty("action")
    private String action; // start, stop
}
