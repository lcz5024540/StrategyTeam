package com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.signal;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IOkxRestRsp;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IRestEntity;
import com.metaverse.coin.strategy.common.model.okx.enums.*;
import com.metaverse.coin.strategy.common.model.okx.enums.ws.TdMode;
import lombok.*;

@ToString
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignalAlgoSubOrder implements IRestEntity, IOkxRestRsp {

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

    @JSONField(name = "algoOrdType")
    @JsonProperty("algoOrdType")
    private AlgoOrderType algoOrdType;

    @JSONField(name = "groupId")
    @JsonProperty("groupId")
    private String groupId;

    @JSONField(name = "ordId")
    @JsonProperty("ordId")
    private String ordId;

    @JSONField(name = "tdMode")
    @JsonProperty("tdMode")
    private TdMode tdMode;

    @JSONField(name = "ccy")
    @JsonProperty("ccy")
    private String ccy;
}
