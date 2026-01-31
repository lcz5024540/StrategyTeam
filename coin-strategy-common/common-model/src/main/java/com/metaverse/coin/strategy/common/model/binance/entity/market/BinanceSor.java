package com.metaverse.coin.strategy.common.model.binance.entity.market;

import lombok.Data;
import java.util.List;

@Data
public class BinanceSor {
    private String baseAsset;
    private List<String> symbols;
}
