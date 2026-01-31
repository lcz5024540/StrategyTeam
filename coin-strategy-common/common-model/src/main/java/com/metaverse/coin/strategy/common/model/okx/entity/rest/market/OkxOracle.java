package com.metaverse.coin.strategy.common.model.okx.entity.rest.market;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IOkxRestRsp;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.List;
import java.util.Map;

@Data
@ToString
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OkxOracle implements IOkxRestRsp {

    @JSONField(name = "messages")
    @JsonProperty("messages")
    private List<String> messages;

    @JSONField(name = "signatures")
    @JsonProperty("signatures")
    private List<String> signatures;

    @JSONField(name = "timestamp")
    @JsonProperty("timestamp")
    private Long timestamp;

    @JSONField(name = "prices")
    @JsonProperty("prices")
    private Map<String, String> prices;
}
