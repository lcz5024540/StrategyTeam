package com.metaverse.coin.strategy.common.model.okx.entity.rest.finance.staking;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IOkxRestRsp;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OkxStakingOrder implements IOkxRestRsp {

    @JSONField(name = "ordId")
    @JsonProperty("ordId")
    private String ordId;

    @JSONField(name = "protocolType")
    @JsonProperty("protocolType")
    private String protocolType;

    @JSONField(name = "ccy")
    @JsonProperty("ccy")
    private String ccy;

    @JSONField(name = "amt")
    @JsonProperty("amt")
    private BigDecimal amt;

    @JSONField(name = "earnings")
    @JsonProperty("earnings")
    private BigDecimal earnings;

    @JSONField(name = "state")
    @JsonProperty("state")
    private String state;

    @JSONField(name = "investData")
    @JsonProperty("investData")
    private BigDecimal investData;

    @JSONField(name = "term")
    @JsonProperty("term")
    private String term;

    @JSONField(name = "apy")
    @JsonProperty("apy")
    private BigDecimal apy;

    @JSONField(name = "purchasedTime")
    @JsonProperty("purchasedTime")
    private Long purchasedTime;
}
