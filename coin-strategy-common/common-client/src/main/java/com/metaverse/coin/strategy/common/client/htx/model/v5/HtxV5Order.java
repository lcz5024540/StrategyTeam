package com.metaverse.coin.strategy.common.client.htx.model.v5;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

import java.math.BigDecimal;

/** Wire model for V5 open-order and order-detail responses. */
@Data
public class HtxV5Order {
    @JSONField(name = "order_id")
    private Long orderId;
    @JSONField(name = "client_order_id")
    private Long clientOrderId;
    @JSONField(name = "contract_code")
    private String contractCode;
    @JSONField(name = "lever_rate")
    private Integer leverage;
    private String side;
    @JSONField(name = "position_side")
    private String positionSide;
    @JSONField(name = "margin_mode")
    private String marginMode;
    @JSONField(name = "reduce_only")
    private Boolean reduceOnly;
    private BigDecimal volume;
    @JSONField(name = "trade_volume")
    private BigDecimal tradeVolume;
    @JSONField(name = "trade_avg_price")
    private BigDecimal tradeAveragePrice;
    private String state;
    private BigDecimal fee;
    @JSONField(name = "fee_currency")
    private String feeCurrency;
    private BigDecimal profit;
    @JSONField(name = "real_profit")
    private BigDecimal realProfit;
}
