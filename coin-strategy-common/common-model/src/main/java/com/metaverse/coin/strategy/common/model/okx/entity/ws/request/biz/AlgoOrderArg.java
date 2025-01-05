package com.metaverse.coin.strategy.common.model.okx.entity.ws.request.biz;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.okx.entity.ws.request.WsChannelRequestArg;
import com.metaverse.coin.strategy.common.model.okx.enums.InstrumentType;
import com.metaverse.coin.strategy.common.model.okx.enums.ws.Channel;
import lombok.*;
import lombok.experimental.SuperBuilder;

@Data
@ToString(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder(toBuilder = true)
public class AlgoOrderArg implements WsChannelRequestArg {

    @Builder.Default
    @JSONField(name = "channel")
    @JsonProperty("channel")
    private Channel channel = Channel.ALGO_ORDERS;

    /**
     * Instrument type
     * SPOT
     * MARGIN
     * SWAP
     * FUTURES
     * ANY
     */
    @NonNull
    @JSONField(name = "instType")
    @JsonProperty("instType")
    private InstrumentType instType;

    /**
     * Instrument family
     * Applicable to FUTURES/SWAP/OPTION
     */
    @JSONField(name = "instFamily")
    @JsonProperty("instFamily")
    private String instFamily;

    /**
     * Instrument ID
     */
    @JSONField(name = "instId")
    @JsonProperty("instId")
    private String instId;

}