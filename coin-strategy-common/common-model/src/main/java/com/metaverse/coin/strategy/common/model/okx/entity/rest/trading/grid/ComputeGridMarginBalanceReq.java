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
public class ComputeGridMarginBalanceReq implements IOkxRestReq {

    /**
     * 策略订单ID
     */
    @NonNull
    @JSONField(name = "algoId")
    @JsonProperty("algoId")
    private String algoId;

    /**
     * 调整保证金类型
     * add：增加，reduce：减少
     */
    @NonNull
    @JSONField(name = "type")
    @JsonProperty("type")
    private String type;

    /**
     * 调整保证金数量
     */
    @JSONField(name = "amt")
    @JsonProperty("amt")
    private BigDecimal amt;

}