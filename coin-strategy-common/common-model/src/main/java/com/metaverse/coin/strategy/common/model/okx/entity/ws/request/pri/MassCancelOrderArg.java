package com.metaverse.coin.strategy.common.model.okx.entity.ws.request.pri;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.okx.entity.ws.request.WsRequestArg;
import com.metaverse.coin.strategy.common.model.okx.enums.InstrumentType;
import lombok.*;
import lombok.experimental.SuperBuilder;

@Data
@ToString(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder(toBuilder = true)
public class MassCancelOrderArg implements WsRequestArg {

    /**
     * Instrument type
     * OPTION
     */
    @NonNull
    @JSONField(name = "instType")
    @JsonProperty("instType")
    private InstrumentType instType;

    /**
     * Instrument family
     */
    @JSONField(name = "instFamily")
    @JsonProperty("instFamily")
    private String instFamily;

}
