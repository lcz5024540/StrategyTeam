package com.metaverse.coin.strategy.common.client.htx.model.v5;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

/** Wire model for V5 single and batch order acknowledgements. */
@Data
public class HtxV5OrderResult {
    private Integer code;
    private String message;
    @JSONField(name = "order_id")
    private Long orderId;
    @JSONField(name = "client_order_id")
    private Long clientOrderId;
}
