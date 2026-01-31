package com.metaverse.coin.strategy.common.model.binance.entity.mining;

import lombok.Data;
import java.util.List;

@Data
public class BinanceMiningAccount {
    private String type;
    private String userName;
    private List<BinanceMiningStat> list;
}
