package com.metaverse.coin.strategy.common.model.binance.entity.market;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceOrderBook {
    private Long lastUpdateId;
    private List<List<String>> bids; // [Price, Qty]
    private List<List<String>> asks; // [Price, Qty]
}
