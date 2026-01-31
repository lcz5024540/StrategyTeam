package com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.order.booking;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IOkxRestReq;
import lombok.*;

@Data
@ToString
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CancelAllAfterReq implements IOkxRestReq {

    /**
     * 倒计时时间，单位秒。
     * 设为0时，表示取消倒计时撤单功能。
     */
    @NonNull
    @JSONField(name = "timeOut")
    @JsonProperty("timeOut")
    private String timeOut;

    /**
     * 标签
     */
    @JSONField(name = "tag")
    @JsonProperty("tag")
    private String tag;
}
