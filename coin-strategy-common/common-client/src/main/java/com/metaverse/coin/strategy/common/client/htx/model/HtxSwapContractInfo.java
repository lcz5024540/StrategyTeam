package com.metaverse.coin.strategy.common.client.htx.model;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

import java.math.BigDecimal;

/** Public metadata for an HTX USDT-margined perpetual contract. */
@Data
public class HtxSwapContractInfo {
    @JSONField(name = "contract_code")
    private String contractCode;
    @JSONField(name = "contract_size")
    private BigDecimal contractSize;
    @JSONField(name = "price_tick")
    private BigDecimal priceTick;
    @JSONField(name = "contract_status")
    private Integer contractStatus;
    @JSONField(name = "support_margin_mode")
    private String supportMarginMode;
    @JSONField(name = "business_type")
    private String businessType;
}
