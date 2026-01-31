package com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.grid;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

@Data
@ToString
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AmendGridAlgoBasicParamRsp {

    @JSONField(name = "algoId")
    @JsonProperty("algoId")
    private String algoId;

    @JSONField(name = "sCode")
    @JsonProperty("sCode")
    private String sCode;

    @JSONField(name = "sMsg")
    @JsonProperty("sMsg")
    private String sMsg;
}
