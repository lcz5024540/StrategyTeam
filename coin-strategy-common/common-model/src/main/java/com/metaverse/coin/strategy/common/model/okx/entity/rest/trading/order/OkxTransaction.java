package com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.order;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IOkxRestRsp;
import com.metaverse.coin.strategy.common.model.okx.enums.*;
import com.metaverse.coin.strategy.common.model.okx.enums.ws.ExecType;
import lombok.*;

import java.math.BigDecimal;

@Data
@ToString
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OkxTransaction implements IOkxRestRsp {

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
     * Trade ID
     */
    @JSONField(name = "tradeId")
    @JsonProperty("tradeId")
    private String tradeId;

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
     * Bill ID
     */
    @JSONField(name = "billId")
    @JsonProperty("billId")
    private String billId;

    /**
     * Order tag
     */
    @JSONField(name = "tag")
    @JsonProperty("tag")
    private String tag;

    /**
     * Filled price
     */
    @JSONField(name = "fillPx")
    @JsonProperty("fillPx")
    private BigDecimal fillPx;

    /**
     * Filled size
     */
    @JSONField(name = "fillSz")
    @JsonProperty("fillSz")
    private BigDecimal fillSz;
    
    /**
     * Index price at the moment of trade
     */
    @JSONField(name = "idxPx")
    @JsonProperty("idxPx")
    private BigDecimal idxPx;

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
     * Liquidity indicator (T: taker, M: maker)
     */
    @JSONField(name = "execType")
    @JsonProperty("execType")
    private ExecType execType;

    /**
     * Fee currency
     */
    @JSONField(name = "feeCcy")
    @JsonProperty("feeCcy")
    private String feeCcy;

    /**
     * Fee amount
     */
    @JSONField(name = "fee")
    @JsonProperty("fee")
    private BigDecimal fee;

    /**
     * Timestamp
     */
    @JSONField(name = "ts")
    @JsonProperty("ts")
    private Long ts;
    
    /**
     * Filled PnL
     */
    @JSONField(name = "fillPnl")
    @JsonProperty("fillPnl")
    private BigDecimal fillPnl;
}
