package com.metaverse.coin.strategy.job.funding.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.client.binance.BinanceApiService;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesOrder;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesPremiumIndex;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceOrderBook;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinancePriceTicker;
import com.metaverse.coin.strategy.common.model.binance.entity.spot.BinanceOrder;
import com.metaverse.coin.strategy.core.arbitrage.config.SingleExchangeSpotFuturesProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class BinanceWebSocketStreamServiceTest {

    @Test
    void parsesPublicMarketMessagesIntoCaches() {
        BinanceWebSocketStreamService service = service();

        service.handlePublicMessage("""
                {"stream":"btcusdt@markPrice@1s","data":{"e":"markPriceUpdate","E":1000,"s":"BTCUSDT","p":"50010.50","i":"50009.10","r":"0.0006","T":2000}}
                """, true);
        service.handlePublicMessage("""
                {"stream":"btcusdt@bookTicker","data":{"u":1,"s":"BTCUSDT","b":"50000","B":"1.2","a":"50002","A":"1.1"}}
                """, false);
        service.handlePublicMessage("""
                {"stream":"btcusdt@depth20@100ms","data":{"lastUpdateId":2,"bids":[["49999","1"]],"asks":[["50003","2"]]}}
                """, false);

        BinanceFuturesPremiumIndex premiumIndex = service.getCachedPremiumIndex("BTCUSDT");
        BinancePriceTicker spotPrice = service.getCachedSpotPrice("BTCUSDT");
        BinanceOrderBook spotBook = service.getCachedSpotOrderBook("BTCUSDT");

        assertNotNull(premiumIndex);
        assertEquals(new BigDecimal("0.0006"), premiumIndex.getLastFundingRate());
        assertEquals(new BigDecimal("50010.50"), service.getCachedFuturesPrice("BTCUSDT").getPrice());
        assertEquals(new BigDecimal("50001.00000000"), spotPrice.getPrice());
        assertEquals("49999", spotBook.getBids().get(0).get(0));
    }

    @Test
    void parsesUserDataOrderMessagesIntoCaches() {
        BinanceWebSocketStreamService service = service();

        service.handleUserMessage("""
                {"e":"executionReport","E":1000,"s":"BTCUSDT","c":"spot-client","S":"BUY","o":"MARKET","f":"GTC","q":"0.01","p":"0","X":"FILLED","i":101,"z":"0.01","Z":"500","O":900}
                """, false);
        service.handleUserMessage("""
                {"e":"ORDER_TRADE_UPDATE","E":1000,"o":{"s":"BTCUSDT","c":"futures-client","S":"SELL","o":"MARKET","f":"GTC","q":"0.01","p":"0","X":"FILLED","i":202,"z":"0.01","Z":"501","ap":"50100","ps":"SHORT","T":1000}}
                """, true);

        BinanceOrder spotOrder = service.getCachedSpotOrder("BTCUSDT", 101L, "spot-client");
        BinanceFuturesOrder futuresOrder = service.getCachedFuturesOrder("BTCUSDT", 202L, "futures-client");

        assertNotNull(spotOrder);
        assertEquals("FILLED", spotOrder.getStatus());
        assertNotNull(futuresOrder);
        assertEquals(new BigDecimal("50100"), futuresOrder.getAvgPrice());
    }

    private BinanceWebSocketStreamService service() {
        return new BinanceWebSocketStreamService(
                new BinanceWebSocketProperties(),
                new SingleExchangeSpotFuturesProperties(),
                mock(BinanceApiService.class),
                new ObjectMapper()
        );
    }
}
