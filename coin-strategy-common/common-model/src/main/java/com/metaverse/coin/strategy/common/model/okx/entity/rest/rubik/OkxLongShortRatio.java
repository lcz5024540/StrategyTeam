package com.metaverse.coin.strategy.common.model.okx.entity.rest.rubik;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IOkxRestRsp;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OkxLongShortRatio implements IOkxRestRsp {

    @JSONField(name = "ts")
    @JsonProperty("ts")
    private Long ts;

    @JSONField(name = "longRatio")
    @JsonProperty("longRatio")
    private BigDecimal longRatio;

    @JSONField(name = "shortRatio")
    @JsonProperty("shortRatio")
    private BigDecimal shortRatio;
}
