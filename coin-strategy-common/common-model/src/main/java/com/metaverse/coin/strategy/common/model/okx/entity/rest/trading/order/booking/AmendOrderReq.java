package com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.order.booking;

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
public class AmendOrderReq implements IOkxRestReq {

    /**
     * 产品ID，如 BTC-USDT
     */
    @NonNull
    @JSONField(name = "instId")
    @JsonProperty("instId")
    private String instId;

    /**
     * 是否撤销目标下单，仅适用于单币种保证金模式下的全仓杠杆订单
     */
    @JSONField(name = "cldOrdId")
    @JsonProperty("cldOrdId")
    private String cldOrdId;

    /**
     * 订单ID， ordId和clOrdId必须传一个，若传两个，以ordId为主
     */
    @JSONField(name = "ordId")
    @JsonProperty("ordId")
    private String ordId;

    /**
     * 用户自定义ID
     */
    @JSONField(name = "clOrdId")
    @JsonProperty("clOrdId")
    private String clOrdId;

    /**
     * 用户自定义修改事件ID
     */
    @JSONField(name = "reqId")
    @JsonProperty("reqId")
    private String reqId;

    /**
     * 修改的新数量
     */
    @JSONField(name = "newSz")
    @JsonProperty("newSz")
    private BigDecimal newSz;

    /**
     * 修改的新价格
     */
    @JSONField(name = "newPx")
    @JsonProperty("newPx")
    private BigDecimal newPx;

    /**
     * 止盈触发价
     */
    @JSONField(name = "newTpTriggerPx")
    @JsonProperty("newTpTriggerPx")
    private BigDecimal newTpTriggerPx;

    /**
     * 止盈委托价
     */
    @JSONField(name = "newTpOrdPx")
    @JsonProperty("newTpOrdPx")
    private BigDecimal newTpOrdPx;

    /**
     * 止损触发价
     */
    @JSONField(name = "newSlTriggerPx")
    @JsonProperty("newSlTriggerPx")
    private BigDecimal newSlTriggerPx;

    /**
     * 止损委托价
     */
    @JSONField(name = "newSlOrdPx")
    @JsonProperty("newSlOrdPx")
    private BigDecimal newSlOrdPx;

    /**
     * 止盈触发价类型
     * last: 最新成交价; index: 指数价格; mark: 标记价格
     */
    @JSONField(name = "newTpTriggerPxType")
    @JsonProperty("newTpTriggerPxType")
    private String newTpTriggerPxType;

    /**
     * 止损触发价类型
     * last: 最新成交价; index: 指数价格; mark: 标记价格
     */
    @JSONField(name = "newSlTriggerPxType")
    @JsonProperty("newSlTriggerPxType")
    private String newSlTriggerPxType;

}
