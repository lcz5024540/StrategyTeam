package com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.grid;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IOkxRestReq;
import lombok.*;

@Data
@ToString
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class InstantTriggerGridAlgoOrderReq implements IOkxRestReq {

    /**
     * 策略订单ID
     */
    @NonNull
    @JSONField(name = "algoId")
    @JsonProperty("algoId")
    private String algoId;

    /**
     * 产品ID，如 BTC-USDT
     */
    @JSONField(name = "instId")
    @JsonProperty("instId")
    private String instId;

    /**
     * 策略订单类型
     * grid：现货网格
     * contract_grid：合约网格
     * moon_grid：天地网格
     */
    @JSONField(name = "algoOrdType")
    @JsonProperty("algoOrdType")
    private String algoOrdType;

}