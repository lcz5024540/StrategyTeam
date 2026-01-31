package com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.algo;

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
public class AmendAlgoOrderReq implements IOkxRestReq {

    /**
     * Instrument ID
     */
    @NonNull
    @JSONField(name = "instId")
    @JsonProperty("instId")
    private String instId;

    /**
     * Algo ID
     */
    @JSONField(name = "algoId")
    @JsonProperty("algoId")
    private String algoId;

    /**
     * Client-supplied Algo ID
     */
    @JSONField(name = "algoClOrdId")
    @JsonProperty("algoClOrdId")
    private String algoClOrdId;

    /**
     * Whether to cancel the order if amendment fails
     */
    @JSONField(name = "cxlOnFail")
    @JsonProperty("cxlOnFail")
    private Boolean cxlOnFail;

    /**
     * New size
     */
    @JSONField(name = "newSz")
    @JsonProperty("newSz")
    private BigDecimal newSz;

    /**
     * New take-profit trigger price
     */
    @JSONField(name = "newTpTriggerPx")
    @JsonProperty("newTpTriggerPx")
    private BigDecimal newTpTriggerPx;

    /**
     * New take-profit order price
     */
    @JSONField(name = "newTpOrdPx")
    @JsonProperty("newTpOrdPx")
    private BigDecimal newTpOrdPx;

    /**
     * New stop-loss trigger price
     */
    @JSONField(name = "newSlTriggerPx")
    @JsonProperty("newSlTriggerPx")
    private BigDecimal newSlTriggerPx;

    /**
     * New stop-loss order price
     */
    @JSONField(name = "newSlOrdPx")
    @JsonProperty("newSlOrdPx")
    private BigDecimal newSlOrdPx;

    /**
     * New take-profit trigger price type
     * last: last price
     * index: index price
     * mark: mark price
     */
    @JSONField(name = "newTpTriggerPxType")
    @JsonProperty("newTpTriggerPxType")
    private String newTpTriggerPxType;

    /**
     * New stop-loss trigger price type
     * last: last price
     * index: index price
     * mark: mark price
     */
    @JSONField(name = "newSlTriggerPxType")
    @JsonProperty("newSlTriggerPxType")
    private String newSlTriggerPxType;
    
    /**
     * New trigger price (for trigger orders)
     */
    @JSONField(name = "newTriggerPx")
    @JsonProperty("newTriggerPx")
    private BigDecimal newTriggerPx;
    
    /**
     * New order price (for trigger orders)
     */
    @JSONField(name = "newOrdPx")
    @JsonProperty("newOrdPx")
    private BigDecimal newOrdPx;

}
