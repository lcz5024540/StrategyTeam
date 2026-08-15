package com.metaverse.coin.strategy.common.client.htx.model;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

import java.math.BigDecimal;

/** Open isolated swap position returned by HTX. */
@Data
public class HtxSwapPosition {
    @JSONField(name = "contract_code")
    private String contractCode;
    private String direction;
    @JSONField(name = "position_side")
    private String positionSide;
    @JSONField(name = "margin_mode")
    private String marginMode;
    private BigDecimal volume;
    private BigDecimal available;
    @JSONField(name = "cost_open")
    private BigDecimal openCost;
    @JSONField(name = "lever_rate")
    private Integer leverage;
    @JSONField(name = "position_mode")
    private String positionMode;
    @JSONField(name = "profit_unreal")
    private BigDecimal unrealizedProfit;
}
