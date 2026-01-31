package com.metaverse.coin.strategy.common.model.binance.entity.market;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceOpenSymbol {
    private Long openTime;
    private List<String> symbols;
}
