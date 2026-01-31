package com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.grid;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.math.BigDecimal;

@Data
@ToString
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class GridQuantityRsp {

    @JSONField(name = "maxGridQty")
    @JsonProperty("maxGridQty")
    private Integer maxGridQty;

    @JSONField(name = "minGridQty")
    @JsonProperty("minGridQty")
    private Integer minGridQty;
}
