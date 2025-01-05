package com.metaverse.coin.strategy.common.model.okx.entity.ws.request.pub;

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
public class EstimatedPriceArg implements WsChannelRequestArg {

    @Builder.Default
    @JSONField(name = "channel")
    @JsonProperty("channel")
    private Channel channel = Channel.ESTIMATED_PRICE;

    /**
     * 产品类型
     * FUTURES：交割合约
     * SWAP：永续合约
     * OPTION：期权
     */
    @NonNull
    @JSONField(name = "instType")
    @JsonProperty("instType")
    private InstrumentType instType;

    /**
     * 交易品种
     * instFamily和instId必须指定一个
     */
    @JSONField(name = "instFamily")
    @JsonProperty("instFamily")
    private String instFamily;


    /**
     * 产品ID
     * instFamily和instId必须指定一个
     */
    @JSONField(name = "instId")
    @JsonProperty("instId")
    private String instId;

}
