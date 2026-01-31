package com.metaverse.coin.strategy.common.model.okx.entity.rest.finance.staking;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IOkxRestReq;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OkxStakingCancelReq implements IOkxRestReq {

    @JSONField(name = "ordId")
    @JsonProperty("ordId")
    private String ordId;

    @JSONField(name = "protocolType")
    @JsonProperty("protocolType")
    private String protocolType;
}
