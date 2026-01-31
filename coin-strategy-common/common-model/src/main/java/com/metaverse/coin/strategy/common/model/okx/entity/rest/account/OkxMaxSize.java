package com.metaverse.coin.strategy.common.model.okx.entity.rest.account;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IOkxRestRsp;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IRestEntity;
import lombok.*;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OkxMaxSize implements IRestEntity, IOkxRestRsp {
    @JSONField(name = "instId")
    @JsonProperty("instId")
    private String instId;

    @JSONField(name = "availBuy")
    @JsonProperty("availBuy")
    private BigDecimal availBuy;

    @JSONField(name = "availSell")
    @JsonProperty("availSell")
    private BigDecimal availSell;
    
    @JSONField(name = "maxBuy")
    @JsonProperty("maxBuy")
    private BigDecimal maxBuy;

    @JSONField(name = "maxSell")
    @JsonProperty("maxSell")
    private BigDecimal maxSell;
}
