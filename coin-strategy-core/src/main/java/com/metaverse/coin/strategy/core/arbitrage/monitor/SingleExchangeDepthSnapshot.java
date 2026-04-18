package com.metaverse.coin.strategy.core.arbitrage.monitor;

import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesOrderBook;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceOrderBook;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SingleExchangeDepthSnapshot {

    private boolean fallbackUsed;
    private BinanceOrderBook spotOrderBook;
    private BinanceFuturesOrderBook futuresOrderBook;
}
