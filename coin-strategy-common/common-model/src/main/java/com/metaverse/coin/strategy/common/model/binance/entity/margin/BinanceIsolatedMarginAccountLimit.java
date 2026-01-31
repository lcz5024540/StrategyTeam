package com.metaverse.coin.strategy.common.model.binance.entity.margin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceIsolatedMarginAccountLimit {
    private Integer enabledAccount;
    private Integer maxAccount;
}
