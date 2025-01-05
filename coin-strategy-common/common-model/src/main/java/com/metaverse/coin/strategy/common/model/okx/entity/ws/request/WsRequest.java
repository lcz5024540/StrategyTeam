package com.metaverse.coin.strategy.common.model.okx.entity.ws.request;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.okx.enums.ws.Operation;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.List;

@ToString(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder(toBuilder = true)
public class WsRequest<T extends WsRequestArg> {

    @JSONField(name = "op")
    @JsonProperty("op")
    private Operation op;

    @JSONField(name = "args")
    @JsonProperty("args")
    private List<T> args;

}
