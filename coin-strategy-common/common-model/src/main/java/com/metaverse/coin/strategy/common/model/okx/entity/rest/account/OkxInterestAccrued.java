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
public class OkxInterestAccrued implements IRestEntity, IOkxRestRsp {
    @JSONField(name = "instId")
    @JsonProperty("instId")
    private String instId;

    @JSONField(name = "ccy")
    @JsonProperty("ccy")
    private String ccy;

    @JSONField(name = "interest")
    @JsonProperty("interest")
    private BigDecimal interest;

    @JSONField(name = "interestRate")
    @JsonProperty("interestRate")
    private BigDecimal interestRate;
    
    @JSONField(name = "liab")
    @JsonProperty("liab")
    private BigDecimal liab;
    
    @JSONField(name = "mgnMode")
    @JsonProperty("mgnMode")
    private String mgnMode;
    
    @JSONField(name = "ts")
    @JsonProperty("ts")
    private Long ts;
}
