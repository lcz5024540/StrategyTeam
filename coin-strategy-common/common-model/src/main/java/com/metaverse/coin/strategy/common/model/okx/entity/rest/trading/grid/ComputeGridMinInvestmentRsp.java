package com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.grid;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IOkxRestRsp;
import lombok.Data;
import lombok.ToString;
import java.math.BigDecimal;
import java.util.List;

@ToString
@Data
public class ComputeGridMinInvestmentRsp implements IOkxRestRsp {

    /**
     * 最小投入信息
     */
    @JSONField(name = "minInvestmentData")
    @JsonProperty("minInvestmentData")
    private List<ComputeGridMinInvestmentReq.InvestmentData> minInvestmentData;

    /**
     * 单网格买卖量
     * 现货网格单位为计价币
     * 合约网格单位为张
     */
    @JSONField(name = "singleAmt")
    @JsonProperty("singleAmt")
    private BigDecimal singleAmt;

}