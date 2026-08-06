package com.metaverse.coin.strategy.common.client.htx.model;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

import java.math.BigDecimal;

/** HTX isolated USDT-margined swap account snapshot. */
@Data
public class HtxSwapAccount {
    @JSONField(name = "contract_code")
    private String contractCode;
    @JSONField(name = "margin_available")
    private BigDecimal marginAvailable;
    @JSONField(name = "risk_rate")
    private BigDecimal riskRate;
    @JSONField(name = "lever_rate")
    private Integer leverage;
    @JSONField(name = "margin_mode")
    private String marginMode;
    @JSONField(name = "position_mode")
    private String positionMode;
}
