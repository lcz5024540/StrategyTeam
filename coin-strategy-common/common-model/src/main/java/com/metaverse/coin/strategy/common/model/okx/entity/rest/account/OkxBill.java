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
public class OkxBill implements IRestEntity, IOkxRestRsp {
    @JSONField(name = "billId")
    @JsonProperty("billId")
    private String billId;

    @JSONField(name = "ccy")
    @JsonProperty("ccy")
    private String ccy;

    @JSONField(name = "bal")
    @JsonProperty("bal")
    private BigDecimal bal;

    @JSONField(name = "balChg")
    @JsonProperty("balChg")
    private BigDecimal balChg;

    @JSONField(name = "type")
    @JsonProperty("type")
    private String type;

    @JSONField(name = "subType")
    @JsonProperty("subType")
    private String subType;

    @JSONField(name = "ts")
    @JsonProperty("ts")
    private Long ts;
    
    @JSONField(name = "fromAccount")
    @JsonProperty("fromAccount")
    private String fromAccount;
    
    @JSONField(name = "toAccount")
    @JsonProperty("toAccount")
    private String toAccount;
    
    @JSONField(name = "notes")
    @JsonProperty("notes")
    private String notes;
}
