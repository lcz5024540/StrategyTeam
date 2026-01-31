package com.metaverse.coin.strategy.common.model.binance.entity.wallet;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceApiKeyPermission {
    private Boolean ipRestrict;
    private Long createTime;
    private Boolean enableReading;
    private Boolean enableWithdrawals;
    private Boolean enableInternalTransfer;
    private Boolean enableMargin;
    private Boolean enableFutures;
    private Boolean permitsUniversalTransfer;
    private Boolean enableVanillaOptions;
    private Boolean enableSpotAndMarginTrading;
}
