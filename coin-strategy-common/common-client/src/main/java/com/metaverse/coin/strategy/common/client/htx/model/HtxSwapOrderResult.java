package com.metaverse.coin.strategy.common.client.htx.model;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

/** Order identifiers returned after HTX accepts a swap order instruction. */
@Data
public class HtxSwapOrderResult {
    @JSONField(name = "order_id")
    private Long orderId;
    @JSONField(name = "order_id_str")
    private String orderIdStr;
    @JSONField(name = "client_order_id")
    private Long clientOrderId;
}
