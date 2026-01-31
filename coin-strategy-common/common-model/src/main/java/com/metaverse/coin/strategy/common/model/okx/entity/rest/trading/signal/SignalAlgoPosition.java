package com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.signal;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IOkxRestRsp;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IRestEntity;
import com.metaverse.coin.strategy.common.model.okx.enums.InstrumentType;
import com.metaverse.coin.strategy.common.model.okx.enums.PositionsSide;
import lombok.*;

import java.math.BigDecimal;

@ToString
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignalAlgoPosition implements IRestEntity, IOkxRestRsp {

    @JSONField(name = "algoId")
    @JsonProperty("algoId")
    private String algoId;

    @JSONField(name = "algoClOrdId")
    @JsonProperty("algoClOrdId")
    private String algoClOrdId;

    @JSONField(name = "instType")
    @JsonProperty("instType")
    private InstrumentType instType;

    @JSONField(name = "instId")
    @JsonProperty("instId")
    private String instId;

    @JSONField(name = "cTime")
    @JsonProperty("cTime")
    private Long cTime;

    @JSONField(name = "uTime")
    @JsonProperty("uTime")
    private Long uTime;

    @JSONField(name = "avgPx")
    @JsonProperty("avgPx")
    private BigDecimal avgPx;

    @JSONField(name = "ccy")
    @JsonProperty("ccy")
    private String ccy;

    @JSONField(name = "lever")
    @JsonProperty("lever")
    private Integer lever;

    @JSONField(name = "liqPx")
    @JsonProperty("liqPx")
    private BigDecimal liqPx;

    @JSONField(name = "posSide")
    @JsonProperty("posSide")
    private PositionsSide posSide;
}
