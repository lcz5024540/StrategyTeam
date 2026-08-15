package com.metaverse.coin.strategy.common.client.htx.model.v5;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

import java.util.List;

/** Wire model shared by GET and POST /v5/position/lever. */
@Data
public class HtxV5Leverage {
    @JSONField(name = "contract_code")
    private String contractCode;
    @JSONField(name = "margin_mode")
    private String marginMode;
    @JSONField(name = "position_side")
    private String positionSide;
    @JSONField(name = "lever_rate")
    private Integer leverage;
    @JSONField(name = "available_lever")
    private List<Integer> availableLeverage;
}
