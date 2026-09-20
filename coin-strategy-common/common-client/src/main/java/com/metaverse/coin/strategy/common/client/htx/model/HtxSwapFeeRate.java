package com.metaverse.coin.strategy.common.client.htx.model;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

import java.math.BigDecimal;

/** Current USDT-margined swap trading fee rates returned by HTX private API. */
@Data
public class HtxSwapFeeRate {
    private String symbol;
    @JSONField(name = "contract_code", alternateNames = {"contractCode"})
    private String contractCode;
    private String pair;
    @JSONField(name = "business_type", alternateNames = {"businessType"})
    private String businessType;
    @JSONField(name = "contract_type", alternateNames = {"contractType"})
    private String contractType;
    @JSONField(name = "open_maker_fee", alternateNames = {"openMakerFee"})
    private BigDecimal openMakerFee;
    @JSONField(name = "open_taker_fee", alternateNames = {"openTakerFee"})
    private BigDecimal openTakerFee;
    @JSONField(name = "close_maker_fee", alternateNames = {"closeMakerFee"})
    private BigDecimal closeMakerFee;
    @JSONField(name = "close_taker_fee", alternateNames = {"closeTakerFee"})
    private BigDecimal closeTakerFee;
    @JSONField(name = "fee_asset", alternateNames = {"feeAsset"})
    private String feeAsset;
    @JSONField(name = "delivery_fee", alternateNames = {"deliveryFee"})
    private BigDecimal deliveryFee;
}
