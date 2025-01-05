package com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.grid;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IOkxRestReq;
import lombok.*;
import java.math.BigDecimal;

@Data
@ToString
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ClosePositionForContractGridReq implements IOkxRestReq {

    /**
     * 策略订单ID
     */
    @NonNull
    @JSONField(name = "algoId")
    @JsonProperty("algoId")
    private String algoId;

    /**
     * 是否市价全平
     * true：市价全平，false：部分平仓
     */
    @NonNull
    @JSONField(name = "mktClose")
    @JsonProperty("mktClose")
    private Boolean mktClose;

    /**
     * 平仓数量,单位为张
     * 部分平仓时必传
     */
    @JSONField(name = "sz")
    @JsonProperty("sz")
    private BigDecimal sz;

    /**
     * 平仓价格
     * 部分平仓时必传
     */
    @JSONField(name = "px")
    @JsonProperty("px")
    private BigDecimal px;

}