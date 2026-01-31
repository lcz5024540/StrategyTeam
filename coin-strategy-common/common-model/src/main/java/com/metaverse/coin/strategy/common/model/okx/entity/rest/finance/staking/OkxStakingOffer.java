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
public class OkxStakingOffer implements IOkxRestRsp {

    @JSONField(name = "ccy")
    @JsonProperty("ccy")
    private String ccy;

    @JSONField(name = "productId")
    @JsonProperty("productId")
    private String productId;

    @JSONField(name = "protocol")
    @JsonProperty("protocol")
    private String protocol;

    @JSONField(name = "protocolType")
    @JsonProperty("protocolType")
    private String protocolType;

    @JSONField(name = "term")
    @JsonProperty("term")
    private String term;

    @JSONField(name = "apy")
    @JsonProperty("apy")
    private BigDecimal apy;

    @JSONField(name = "earlyRedeem")
    @JsonProperty("earlyRedeem")
    private Boolean earlyRedeem;

    @JSONField(name = "investData")
    @JsonProperty("investData")
    private String investData;

    @JSONField(name = "minInvest")
    @JsonProperty("minInvest")
    private BigDecimal minInvest;

    @JSONField(name = "maxInvest")
    @JsonProperty("maxInvest")
    private BigDecimal maxInvest;

    @JSONField(name = "bal")
    @JsonProperty("bal")
    private BigDecimal bal;
}
