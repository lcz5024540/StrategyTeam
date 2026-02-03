package com.metaverse.coin.strategy.common.model.binance.entity.futures;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceFuturesTakerVolume {
    private BigDecimal buySellRatio;
    private BigDecimal buyVol;
    private BigDecimal sellVol;
    private Long timestamp;
}
