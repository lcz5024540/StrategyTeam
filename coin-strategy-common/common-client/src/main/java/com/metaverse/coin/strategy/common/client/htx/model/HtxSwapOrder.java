package com.metaverse.coin.strategy.common.client.htx.model;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

import java.math.BigDecimal;

/** HTX USDT-margined swap order snapshot, normalized across legacy and V5 envelopes. */
@Data
public class HtxSwapOrder {
    @JSONField(name = "order_id")
    private Long orderId;
    @JSONField(name = "order_id_str")
    private String orderIdStr;
    @JSONField(name = "client_order_id")
    private Long clientOrderId;
    @JSONField(name = "contract_code")
    private String contractCode;
    @JSONField(name = "lever_rate")
    private Integer leverage;
    private String direction;
    private String offset;
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
    private Integer status;
    /** V5 state: new, partially_filled, filled, partially_canceled, canceled or rejected. */
    private String state;
    private BigDecimal fee;
    @JSONField(name = "fee_currency")
    private String feeCurrency;
    private BigDecimal profit;
    @JSONField(name = "real_profit")
    private BigDecimal realProfit;
}
