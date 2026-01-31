package com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.algo;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IOkxRestRsp;
import com.metaverse.coin.strategy.common.model.okx.enums.*;
import com.metaverse.coin.strategy.common.model.okx.enums.ws.TdMode;
import lombok.*;

import java.math.BigDecimal;

@Data
@ToString
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OkxAlgoOrder implements IOkxRestRsp {

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
     * Instrument type
     */
    @JSONField(name = "instType")
    @JsonProperty("instType")
    private InstrumentType instType;

    /**
     * Instrument ID
     */
    @JSONField(name = "instId")
    @JsonProperty("instId")
    private String instId;

    /**
     * Creation time
     */
    @JSONField(name = "cTime")
    @JsonProperty("cTime")
    private Long cTime;

    /**
     * Update time
     */
    @JSONField(name = "uTime")
    @JsonProperty("uTime")
    private Long uTime;

    /**
     * Order type (Algo type)
     * conditional, oco, trigger, move_order_stop, iceberg, twap
     */
    @JSONField(name = "ordType")
    @JsonProperty("ordType")
    private AlgoOrderType ordType;

    /**
     * Order size
     */
    @JSONField(name = "sz")
    @JsonProperty("sz")
    private BigDecimal sz;

    /**
     * Margin currency
     */
    @JSONField(name = "ccy")
    @JsonProperty("ccy")
    private String ccy;

    /**
     * Order side
     */
    @JSONField(name = "side")
    @JsonProperty("side")
    private Side side;

    /**
     * Position side
     */
    @JSONField(name = "posSide")
    @JsonProperty("posSide")
    private PositionsSide posSide;

    /**
     * Trade mode
     */
    @JSONField(name = "tdMode")
    @JsonProperty("tdMode")
    private TdMode tdMode;

    /**
     * Target currency
     */
    @JSONField(name = "tgtCcy")
    @JsonProperty("tgtCcy")
    private String tgtCcy;

    /**
     * State
     * live, pause, partially_failed, filled, canceled, order_failed, partially_filled
     */
    @JSONField(name = "state")
    @JsonProperty("state")
    private OrderState state;

    /**
     * Leverage
     */
    @JSONField(name = "lever")
    @JsonProperty("lever")
    private BigDecimal lever;

    /**
     * Take-profit trigger price
     */
    @JSONField(name = "tpTriggerPx")
    @JsonProperty("tpTriggerPx")
    private BigDecimal tpTriggerPx;

    /**
     * Take-profit order price
     */
    @JSONField(name = "tpOrdPx")
    @JsonProperty("tpOrdPx")
    private BigDecimal tpOrdPx;

    /**
     * Stop-loss trigger price
     */
    @JSONField(name = "slTriggerPx")
    @JsonProperty("slTriggerPx")
    private BigDecimal slTriggerPx;

    /**
     * Stop-loss order price
     */
    @JSONField(name = "slOrdPx")
    @JsonProperty("slOrdPx")
    private BigDecimal slOrdPx;

    /**
     * Trigger price (for trigger order)
     */
    @JSONField(name = "triggerPx")
    @JsonProperty("triggerPx")
    private BigDecimal triggerPx;

    /**
     * Order price (for trigger order)
     */
    @JSONField(name = "ordPx")
    @JsonProperty("ordPx")
    private BigDecimal ordPx;

    /**
     * Actual size
     */
    @JSONField(name = "actualSz")
    @JsonProperty("actualSz")
    private BigDecimal actualSz;

    /**
     * Actual price
     */
    @JSONField(name = "actualPx")
    @JsonProperty("actualPx")
    private BigDecimal actualPx;

    /**
     * Actual side
     */
    @JSONField(name = "actualSide")
    @JsonProperty("actualSide")
    private Side actualSide;

    /**
     * Trigger time
     */
    @JSONField(name = "triggerTime")
    @JsonProperty("triggerTime")
    private Long triggerTime;
    
    /**
     * Callback ratio (for trailing stop)
     */
    @JSONField(name = "callbackRatio")
    @JsonProperty("callbackRatio")
    private BigDecimal callbackRatio;
    
    /**
     * Callback spread (for trailing stop)
     */
    @JSONField(name = "callbackSpread")
    @JsonProperty("callbackSpread")
    private BigDecimal callbackSpread;
    
    /**
     * Active price (for trailing stop)
     */
    @JSONField(name = "activePx")
    @JsonProperty("activePx")
    private BigDecimal activePx;
    
    /**
     * Price limit (for iceberg/twap)
     */
    @JSONField(name = "pxLimit")
    @JsonProperty("pxLimit")
    private BigDecimal pxLimit;
    
    /**
     * Price variance (for iceberg/twap)
     */
    @JSONField(name = "pxVar")
    @JsonProperty("pxVar")
    private BigDecimal pxVar;
    
    /**
     * Price ratio (for iceberg/twap)
     */
    @JSONField(name = "pxSpread")
    @JsonProperty("pxSpread")
    private BigDecimal pxSpread;
    
    /**
     * Average amount (for iceberg)
     */
    @JSONField(name = "szLimit")
    @JsonProperty("szLimit")
    private BigDecimal szLimit;
    
    /**
     * Time interval (for twap)
     */
    @JSONField(name = "timeInterval")
    @JsonProperty("timeInterval")
    private String timeInterval;

}
