package com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.order;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IOkxRestRsp;
import com.metaverse.coin.strategy.common.model.okx.enums.*;
import com.metaverse.coin.strategy.common.model.okx.enums.ws.TdMode;
import com.metaverse.coin.strategy.common.model.okx.enums.ws.ExecType;
import lombok.*;

import java.math.BigDecimal;

@Data
@ToString
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OkxOrder implements IOkxRestRsp {

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
     * Target currency
     */
    @JSONField(name = "tgtCcy")
    @JsonProperty("tgtCcy")
    private String tgtCcy;

    /**
     * Margin currency
     */
    @JSONField(name = "ccy")
    @JsonProperty("ccy")
    private String ccy;

    /**
     * Order ID
     */
    @JSONField(name = "ordId")
    @JsonProperty("ordId")
    private String ordId;

    /**
     * Client Order ID
     */
    @JSONField(name = "clOrdId")
    @JsonProperty("clOrdId")
    private String clOrdId;

    /**
     * Order tag
     */
    @JSONField(name = "tag")
    @JsonProperty("tag")
    private String tag;

    /**
     * Order price
     */
    @JSONField(name = "px")
    @JsonProperty("px")
    private BigDecimal px;

    /**
     * Order size
     */
    @JSONField(name = "sz")
    @JsonProperty("sz")
    private BigDecimal sz;

    /**
     * Notional value in USD
     */
    @JSONField(name = "notionalUsd")
    @JsonProperty("notionalUsd")
    private BigDecimal notionalUsd;

    /**
     * Order type
     */
    @JSONField(name = "ordType")
    @JsonProperty("ordType")
    private OrderType ordType;

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
     * Accumulated fill size
     */
    @JSONField(name = "accFillSz")
    @JsonProperty("accFillSz")
    private BigDecimal accFillSz;

    /**
     * Last fill price
     */
    @JSONField(name = "fillPx")
    @JsonProperty("fillPx")
    private BigDecimal fillPx;

    /**
     * Trade ID
     */
    @JSONField(name = "tradeId")
    @JsonProperty("tradeId")
    private String tradeId;

    /**
     * Last fill size
     */
    @JSONField(name = "fillSz")
    @JsonProperty("fillSz")
    private BigDecimal fillSz;

    /**
     * Last fill time
     */
    @JSONField(name = "fillTime")
    @JsonProperty("fillTime")
    private Long fillTime;

    /**
     * Average price
     */
    @JSONField(name = "avgPx")
    @JsonProperty("avgPx")
    private BigDecimal avgPx;

    /**
     * Order state
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
     * Take-profit trigger price type
     */
    @JSONField(name = "tpTriggerPxType")
    @JsonProperty("tpTriggerPxType")
    private TriggerPxType tpTriggerPxType;

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
     * Stop-loss trigger price type
     */
    @JSONField(name = "slTriggerPxType")
    @JsonProperty("slTriggerPxType")
    private TriggerPxType slTriggerPxType;

    /**
     * Fee currency
     */
    @JSONField(name = "feeCcy")
    @JsonProperty("feeCcy")
    private String feeCcy;

    /**
     * Fee
     */
    @JSONField(name = "fee")
    @JsonProperty("fee")
    private BigDecimal fee;

    /**
     * Rebate currency
     */
    @JSONField(name = "rebateCcy")
    @JsonProperty("rebateCcy")
    private String rebateCcy;

    /**
     * Rebate
     */
    @JSONField(name = "rebate")
    @JsonProperty("rebate")
    private BigDecimal rebate;

    /**
     * Profit and loss
     */
    @JSONField(name = "pnl")
    @JsonProperty("pnl")
    private BigDecimal pnl;

    /**
     * Order source
     */
    @JSONField(name = "source")
    @JsonProperty("source")
    private String source;

    /**
     * Cancellation source
     */
    @JSONField(name = "cancelSource")
    @JsonProperty("cancelSource")
    private String cancelSource;

    /**
     * Category
     */
    @JSONField(name = "category")
    @JsonProperty("category")
    private String category;

    /**
     * Reduce only
     */
    @JSONField(name = "reduceOnly")
    @JsonProperty("reduceOnly")
    private Boolean reduceOnly;

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
     * Error code
     */
    @JSONField(name = "code")
    @JsonProperty("code")
    private String code;
    
    /**
     * Error message
     */
    @JSONField(name = "msg")
    @JsonProperty("msg")
    private String msg;
    
    @JSONField(name = "pxType")
    @JsonProperty("pxType")
    private String pxType;
    
    @JSONField(name = "attachAlgoClOrdId")
    @JsonProperty("attachAlgoClOrdId")
    private String attachAlgoClOrdId;
}
