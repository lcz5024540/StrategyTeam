package com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.recurring;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IOkxRestReq;
import lombok.*;

import java.util.List;

@Data
@Builder
@ToString
@AllArgsConstructor
@NoArgsConstructor
public class CancelRecurringBuyAlgoOrderReq implements IOkxRestReq {

    @JSONField(name = "algoId")
    @JsonProperty("algoId")
    private String algoId;
}
