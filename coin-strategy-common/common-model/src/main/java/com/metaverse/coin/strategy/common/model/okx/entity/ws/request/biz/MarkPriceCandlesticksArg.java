package com.metaverse.coin.strategy.common.model.okx.entity.ws.request.biz;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.okx.entity.ws.request.WsChannelRequestArg;
import com.metaverse.coin.strategy.common.model.okx.enums.ws.Channel;
import lombok.*;
import lombok.experimental.SuperBuilder;

@Data
@ToString(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder(toBuilder = true)
public class MarkPriceCandlesticksArg implements WsChannelRequestArg {

    // 新建的时候必须指定channel
    @JSONField(name = "channel")
    @JsonProperty("channel")
    private Channel channel;

    /**
     * 产品ID
     */
    @NonNull
    @JSONField(name = "instId")
    @JsonProperty("instId")
    private String instId;

}