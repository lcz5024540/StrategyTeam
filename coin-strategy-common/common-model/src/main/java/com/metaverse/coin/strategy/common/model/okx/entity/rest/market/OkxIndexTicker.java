package com.metaverse.coin.strategy.common.model.okx.entity.rest.market;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IOkxRestRsp;
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
public class OkxIndexTicker implements IOkxRestRsp {

    @JSONField(name = "instId")
    @JsonProperty("instId")
    private String instId;

    @JSONField(name = "idxPx")
    @JsonProperty("idxPx")
    private BigDecimal idxPx;

    @JSONField(name = "high24h")
    @JsonProperty("high24h")
    private BigDecimal high24h;

    @JSONField(name = "low24h")
    @JsonProperty("low24h")
    private BigDecimal low24h;

    @JSONField(name = "open24h")
    @JsonProperty("open24h")
    private BigDecimal open24h;

    @JSONField(name = "sodUtc0")
    @JsonProperty("sodUtc0")
    private BigDecimal sodUtc0;

    @JSONField(name = "sodUtc8")
    @JsonProperty("sodUtc8")
    private BigDecimal sodUtc8;

    @JSONField(name = "ts")
    @JsonProperty("ts")
    private Long ts;
}
