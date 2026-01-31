package com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.signal;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IOkxRestRsp;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IRestEntity;
import com.metaverse.coin.strategy.common.model.okx.enums.ws.TdMode;
import com.metaverse.coin.strategy.common.model.okx.enums.Side;
import com.metaverse.coin.strategy.common.model.okx.enums.OrderType;
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
public class PlaceSignalAlgoSubOrderReq implements IRestEntity {

    @JSONField(name = "algoId")
    @JsonProperty("algoId")
    private String algoId;

    @JSONField(name = "instId")
    @JsonProperty("instId")
    private String instId;

    @JSONField(name = "algoClOrdId")
    @JsonProperty("algoClOrdId")
    private String algoClOrdId;

    @JSONField(name = "side")
    @JsonProperty("side")
    private Side side;

    @JSONField(name = "ordType")
    @JsonProperty("ordType")
    private OrderType ordType;

    @JSONField(name = "sz")
    @JsonProperty("sz")
    private String sz;

    @JSONField(name = "px")
    @JsonProperty("px")
    private String px;

    @JSONField(name = "reduceOnly")
    @JsonProperty("reduceOnly")
    private Boolean reduceOnly;
}
