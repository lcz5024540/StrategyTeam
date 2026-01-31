package com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.signal;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@Builder
@ToString
@AllArgsConstructor
@NoArgsConstructor
public class CancelSignalAlgoOrderRsp {

    @JSONField(name = "algoId")
    @JsonProperty("algoId")
    private String algoId;

    @JSONField(name = "sMsg")
    @JsonProperty("sMsg")
    private String sMsg;

    @JSONField(name = "sCode")
    @JsonProperty("sCode")
    private String sCode;
}
