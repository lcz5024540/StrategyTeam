package com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.signal;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IOkxRestReq;
import lombok.*;

import java.util.Map;

@Data
@ToString
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AmendSignalAlgoOrderReq implements IOkxRestReq {

    @NonNull
    @JSONField(name = "algoId")
    @JsonProperty("algoId")
    private String algoId;

    @NonNull
    @JSONField(name = "instId")
    @JsonProperty("instId")
    private String instId;

    @JSONField(name = "exitSettingParam")
    @JsonProperty("exitSettingParam")
    private Map<String, Object> exitSettingParam;
}
