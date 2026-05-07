package com.metaverse.coin.strategy.core.arbitrage.market;

import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesAccount;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesOrder;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesOrderBook;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesPositionRisk;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesPremiumIndex;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceOrderBook;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinancePriceTicker;
import com.metaverse.coin.strategy.common.model.binance.entity.spot.BinanceOrder;

import java.util.List;

public interface BinanceQueryDataProvider {

    BinancePriceTicker getSpotPrice(String symbol);

    BinancePriceTicker getFuturesPrice(String symbol);

    BinanceFuturesPremiumIndex getPremiumIndex(String symbol);

    List<BinanceFuturesPremiumIndex> getPremiumIndices();

    BinanceOrderBook getSpotOrderBook(String symbol, Integer limit);

    BinanceFuturesOrderBook getFuturesOrderBook(String symbol, Integer limit);

    BinanceOrder getSpotOrder(String symbol, Long orderId, String origClientOrderId);

    BinanceFuturesOrder getFuturesOrder(String symbol, Long orderId, String origClientOrderId);

    BinanceFuturesAccount getFuturesAccount();

    List<BinanceFuturesPositionRisk> getPositionRisk(String symbol);
}
