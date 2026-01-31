package com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.order.booking;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IOkxRestRsp;
import lombok.*;

@Data
@ToString
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CancelAllAfterRsp implements IOkxRestRsp {

    /**
     * 触发撤单时间，Unix时间戳
     */
    @JSONField(name = "triggerTime")
    @JsonProperty("triggerTime")
    private String triggerTime;

    /**
     * 接收请求的时间，Unix时间戳
     */
    @JSONField(name = "ts")
    @JsonProperty("ts")
    private String ts;
}
