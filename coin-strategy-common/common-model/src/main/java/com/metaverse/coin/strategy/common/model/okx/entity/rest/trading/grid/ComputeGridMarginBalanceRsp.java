package com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.grid;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IOkxRestRsp;
import lombok.Data;
import lombok.ToString;
import java.math.BigDecimal;

@ToString
@Data
public class ComputeGridMarginBalanceRsp implements IOkxRestRsp {

    /**
     * 最多可调整的保证金数量
     */
    @JSONField(name = "maxAmt")
    @JsonProperty("maxAmt")
    private BigDecimal maxAmt;

    /**
     * 调整保证金后的杠杠倍数
     */
    @JSONField(name = "lever")
    @JsonProperty("lever")
    private Integer lever;

}