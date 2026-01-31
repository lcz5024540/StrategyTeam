package com.metaverse.coin.strategy.common.model.okx.entity.rest.finance.staking;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IOkxRestReq;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OkxStakingPurchaseReq implements IOkxRestReq {

    @JSONField(name = "productId")
    @JsonProperty("productId")
    private String productId;

    @JSONField(name = "investData")
    @JsonProperty("investData")
    private BigDecimal investData;

    @JSONField(name = "term")
    @JsonProperty("term")
    private String term;

    @JSONField(name = "ccy")
    @JsonProperty("ccy")
    private String ccy;

    @JSONField(name = "tag")
    @JsonProperty("tag")
    private String tag;
}
