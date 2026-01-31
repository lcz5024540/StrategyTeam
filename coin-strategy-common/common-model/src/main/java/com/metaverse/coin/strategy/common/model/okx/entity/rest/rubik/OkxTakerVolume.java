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
public class OkxTakerVolume implements IOkxRestRsp {

    @JSONField(name = "ts")
    @JsonProperty("ts")
    private Long ts;

    @JSONField(name = "sellVol")
    @JsonProperty("sellVol")
    private BigDecimal sellVol;

    @JSONField(name = "buyVol")
    @JsonProperty("buyVol")
    private BigDecimal buyVol;
}
