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

@Data
@ToString
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OkxOrderBook implements IOkxRestRsp {

    @JSONField(name = "asks")
    @JsonProperty("asks")
    private List<List<String>> asks;

    @JSONField(name = "bids")
    @JsonProperty("bids")
    private List<List<String>> bids;

    @JSONField(name = "ts")
    @JsonProperty("ts")
    private Long ts;
}
