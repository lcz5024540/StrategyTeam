package com.metaverse.coin.strategy.common.client.htx.model;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

/** Result returned by the isolated-margin leverage switch endpoint. */
@Data
public class HtxSwapLeverageResult {
    @JSONField(name = "contract_code")
    private String contractCode;
    @JSONField(name = "margin_mode")
    private String marginMode;
    @JSONField(name = "lever_rate")
    private Integer leverage;
}
