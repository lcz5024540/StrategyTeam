package com.metaverse.coin.strategy.common.client.htx.model;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** V5 limit-order instruction used by both single and batch swap endpoints. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HtxSwapOrderRequest {
    @JSONField(name = "contract_code")
    private String contractCode;
    @JSONField(name = "margin_mode")
    private String marginMode;
    @JSONField(name = "position_side")
    private String positionSide;
    private String side;
    private String type;
    @JSONField(name = "client_order_id")
    private String clientOrderId;
    private String price;
    private String volume;
    @JSONField(name = "reduce_only")
    private Boolean reduceOnly;
    @JSONField(name = "time_in_force")
    private String timeInForce;
    @JSONField(name = "self_match_prevent")
    private String selfMatchPrevent;
}
