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
public class OkxPlatformVolume implements IOkxRestRsp {

    @JSONField(name = "volUsd")
    @JsonProperty("volUsd")
    private BigDecimal volUsd;

    @JSONField(name = "volCcy")
    @JsonProperty("volCcy")
    private BigDecimal volCcy;

    @JSONField(name = "ts")
    @JsonProperty("ts")
    private Long ts;
}
