package com.metaverse.coin.strategy.common.model.okx.entity.rest.market;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IOkxRestRsp;
import com.metaverse.coin.strategy.common.model.okx.enums.Side;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;

@Data
@ToString
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OkxTrade implements IOkxRestRsp {

    @JSONField(name = "instId")
    @JsonProperty("instId")
    private String instId;

    @JSONField(name = "tradeId")
    @JsonProperty("tradeId")
    private String tradeId;

    @JSONField(name = "px")
    @JsonProperty("px")
    private BigDecimal px;

    @JSONField(name = "sz")
    @JsonProperty("sz")
    private BigDecimal sz;

    @JSONField(name = "side")
    @JsonProperty("side")
    private Side side;

    @JSONField(name = "ts")
    @JsonProperty("ts")
    private Long ts;
}
