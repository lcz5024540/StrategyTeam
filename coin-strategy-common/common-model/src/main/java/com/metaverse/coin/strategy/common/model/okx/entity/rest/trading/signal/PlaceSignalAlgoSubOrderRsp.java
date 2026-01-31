package com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.signal;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IOkxRestRsp;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IRestEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@ToString
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlaceSignalAlgoSubOrderRsp implements IRestEntity, IOkxRestRsp {

    @JSONField(name = "algoId")
    @JsonProperty("algoId")
    private String algoId;

    @JSONField(name = "algoClOrdId")
    @JsonProperty("algoClOrdId")
    private String algoClOrdId;

    @JSONField(name = "ordId")
    @JsonProperty("ordId")
    private String ordId;

    @JSONField(name = "sCode")
    @JsonProperty("sCode")
    private String sCode;

    @JSONField(name = "sMsg")
    @JsonProperty("sMsg")
    private String sMsg;
}
