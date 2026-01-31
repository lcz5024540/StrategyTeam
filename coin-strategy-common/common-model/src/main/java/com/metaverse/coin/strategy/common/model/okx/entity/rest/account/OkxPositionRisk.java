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
public class OkxPositionRisk implements IRestEntity, IOkxRestRsp {
    @JSONField(name = "instId")
    @JsonProperty("instId")
    private String instId;

    @JSONField(name = "instType")
    @JsonProperty("instType")
    private String instType;

    @JSONField(name = "mgnMode")
    @JsonProperty("mgnMode")
    private String mgnMode;

    @JSONField(name = "posId")
    @JsonProperty("posId")
    private String posId;

    @JSONField(name = "posSide")
    @JsonProperty("posSide")
    private String posSide;

    @JSONField(name = "pos")
    @JsonProperty("pos")
    private BigDecimal pos;
    
    @JSONField(name = "baseBal")
    @JsonProperty("baseBal")
    private BigDecimal baseBal;
    
    @JSONField(name = "quoteBal")
    @JsonProperty("quoteBal")
    private BigDecimal quoteBal;
    
    @JSONField(name = "posCcy")
    @JsonProperty("posCcy")
    private String posCcy;
    
    @JSONField(name = "ccy")
    @JsonProperty("ccy")
    private String ccy;
    
    @JSONField(name = "notionalCcy")
    @JsonProperty("notionalCcy")
    private BigDecimal notionalCcy;
    
    @JSONField(name = "notionalUsd")
    @JsonProperty("notionalUsd")
    private BigDecimal notionalUsd;
}
