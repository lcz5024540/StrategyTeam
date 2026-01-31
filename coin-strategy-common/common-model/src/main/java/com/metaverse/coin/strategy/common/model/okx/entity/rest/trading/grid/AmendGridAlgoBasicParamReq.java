package com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.grid;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IOkxRestReq;
import lombok.*;

import java.math.BigDecimal;

@Data
@ToString
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AmendGridAlgoBasicParamReq implements IOkxRestReq {

    /**
     * Algo ID
     */
    @NonNull
    @JSONField(name = "algoId")
    @JsonProperty("algoId")
    private String algoId;

    /**
     * Instrument ID
     */
    @NonNull
    @JSONField(name = "instId")
    @JsonProperty("instId")
    private String instId;

    /**
     * Upper price of price range
     */
    @JSONField(name = "maxPx")
    @JsonProperty("maxPx")
    private BigDecimal maxPx;

    /**
     * Lower price of price range
     */
    @JSONField(name = "minPx")
    @JsonProperty("minPx")
    private BigDecimal minPx;

    /**
     * Grid quantity
     */
    @JSONField(name = "gridNum")
    @JsonProperty("gridNum")
    private BigDecimal gridNum;
    
    /**
     * Stop trigger price
     */
    @JSONField(name = "stopPx")
    @JsonProperty("stopPx")
    private BigDecimal stopPx;
}
