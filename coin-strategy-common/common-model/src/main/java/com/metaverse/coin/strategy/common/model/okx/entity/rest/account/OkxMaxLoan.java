package com.metaverse.coin.strategy.common.model.okx.entity.rest.account;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IOkxRestRsp;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IRestEntity;
import lombok.*;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OkxMaxLoan implements IRestEntity, IOkxRestRsp {
    @JSONField(name = "instId")
    @JsonProperty("instId")
    private String instId;
    
    @JSONField(name = "mgnMode")
    @JsonProperty("mgnMode")
    private String mgnMode;
    
    @JSONField(name = "mgnCcy")
    @JsonProperty("mgnCcy")
    private String mgnCcy;

    @JSONField(name = "maxLoan")
    @JsonProperty("maxLoan")
    private BigDecimal maxLoan;
    
    @JSONField(name = "ccy")
    @JsonProperty("ccy")
    private String ccy;
    
    @JSONField(name = "side")
    @JsonProperty("side")
    private String side;
}
