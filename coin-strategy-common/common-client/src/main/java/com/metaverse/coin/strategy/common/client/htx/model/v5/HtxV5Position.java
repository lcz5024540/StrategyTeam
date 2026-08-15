package com.metaverse.coin.strategy.common.client.htx.model.v5;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

import java.math.BigDecimal;

/** Wire model for GET /v5/trade/position/opens. */
@Data
public class HtxV5Position {
    @JSONField(name = "contract_code")
    private String contractCode;
    @JSONField(name = "position_side")
    private String positionSide;
    private String direction;
    @JSONField(name = "margin_mode")
    private String marginMode;
    private BigDecimal volume;
    private BigDecimal available;
    @JSONField(name = "open_avg_price")
    private BigDecimal openAveragePrice;
    @JSONField(name = "lever_rate")
    private Integer leverage;
    @JSONField(name = "profit_unreal")
    private BigDecimal unrealizedProfit;
}
