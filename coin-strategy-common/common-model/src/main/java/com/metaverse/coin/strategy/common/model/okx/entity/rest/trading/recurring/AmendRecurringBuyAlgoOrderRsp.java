package com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.recurring;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IOkxRestRsp;
import lombok.Data;
import lombok.ToString;

@ToString
@Data
public class AmendRecurringBuyAlgoOrderRsp implements IOkxRestRsp {

    @JSONField(name = "algoId")
    @JsonProperty("algoId")
    private String algoId;
}
