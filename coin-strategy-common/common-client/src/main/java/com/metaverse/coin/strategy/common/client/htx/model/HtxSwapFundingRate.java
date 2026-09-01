package com.metaverse.coin.strategy.common.client.htx.model;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

import java.math.BigDecimal;

/** Public funding-rate snapshot for an HTX USDT-margined perpetual contract. */
@Data
public class HtxSwapFundingRate {
    @JSONField(name = "contract_code")
    private String contractCode;
    @JSONField(name = "funding_rate")
    private BigDecimal fundingRate;
    @JSONField(name = "estimated_rate")
    private BigDecimal estimatedRate;
    @JSONField(name = "fee_asset")
    private String feeAsset;
    @JSONField(name = "funding_time")
    private Long fundingTime;
    @JSONField(name = "next_funding_time")
    private Long nextFundingTime;
}
