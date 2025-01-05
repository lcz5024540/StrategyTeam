package com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.grid;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IOkxRestReq;
import com.metaverse.coin.strategy.common.model.okx.enums.AlgoOrderType;
import com.metaverse.coin.strategy.common.model.okx.enums.ContractDirection;
import lombok.*;
import java.math.BigDecimal;
import java.util.List;

@Data
@ToString
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ComputeGridMinInvestmentReq implements IOkxRestReq {

    /**
     * Instrument ID
     */
    @JSONField(name = "instId")
    @JsonProperty("instId")
    private String instId;

    /**
     * 币Algo order type
     * grid: Spot grid
     */
    @JSONField(name = "algoOrdType")
    @JsonProperty("algoOrdType")
    private AlgoOrderType algoOrdType;

    /**
     * Upper price of price range
     */
    @JSONField(name = "maxPx")
    @JsonProperty("maxPx")
    private BigDecimal maxPx;

    /**
     * Lower price of price range
     */
    @JSONField(name = "minPx")
    @JsonProperty("minPx")
    private BigDecimal minPx;

    /**
     * Grid quantity
     */
    @JSONField(name = "gridNum")
    @JsonProperty("gridNum")
    private BigDecimal gridNum;

    /**
     * 网格类型
     * 1：等差，2：等比
     */
    @JSONField(name = "runType")
    @JsonProperty("runType")
    private String runType;

    /**
     * 合约网格类型
     * 仅适用于合约网格
     */
    @JSONField(name = "direction")
    @JsonProperty("direction")
    private ContractDirection direction;

    /**
     * 杠杆倍数
     */
    @JSONField(name = "lever")
    @JsonProperty("lever")
    private Integer lever;

    /**
     * 是否开底仓
     * 默认为false
     */
    @JSONField(name = "basePos")
    @JsonProperty("basePos")
    private Boolean basePos;

    /**
     * 投资信息
     */
    @JSONField(name = "investmentData")
    @JsonProperty("investmentData")
    private List<InvestmentData> investmentData;

    @Data
    @Builder
    @ToString
    @AllArgsConstructor
    @NoArgsConstructor
    public static class InvestmentData {

        @JSONField(name = "amt")
        @JsonProperty("amt")
        private BigDecimal amt;

        @JSONField(name = "ccy")
        @JsonProperty("ccy")
        private String ccy;
    }

}
