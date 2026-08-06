package com.metaverse.coin.strategy.common.client.htx.model;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

/** Leverage values currently accepted by HTX for one contract. */
@Data
public class HtxSwapAvailableLeverage {
    @JSONField(name = "contract_code")
    private String contractCode;
    @JSONField(name = "available_level_rate")
    private String availableLevelRate;
    @JSONField(name = "margin_mode")
    private String marginMode;
}
