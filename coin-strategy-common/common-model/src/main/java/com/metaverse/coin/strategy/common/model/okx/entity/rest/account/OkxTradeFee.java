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
public class OkxTradeFee implements IRestEntity, IOkxRestRsp {
    @JSONField(name = "level")
    @JsonProperty("level")
    private String level;

    @JSONField(name = "taker")
    @JsonProperty("taker")
    private BigDecimal taker;

    @JSONField(name = "maker")
    @JsonProperty("maker")
    private BigDecimal maker;
    
    @JSONField(name = "instType")
    @JsonProperty("instType")
    private String instType;
    
    @JSONField(name = "delivery")
    @JsonProperty("delivery")
    private BigDecimal delivery;
    
    @JSONField(name = "exercise")
    @JsonProperty("exercise")
    private BigDecimal exercise;
    
    @JSONField(name = "category")
    @JsonProperty("category")
    private String category;
    
    @JSONField(name = "ts")
    @JsonProperty("ts")
    private Long ts;
}
