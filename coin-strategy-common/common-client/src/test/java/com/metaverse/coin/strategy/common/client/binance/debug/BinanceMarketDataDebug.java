package com.metaverse.coin.strategy.common.client.binance.debug;

import com.metaverse.coin.strategy.common.client.binance.service.BinanceMarketDataService;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceExchangeInfo;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceTrade;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
public class BinanceMarketDataDebug extends BinanceDebugBase {

    private BinanceMarketDataService marketDataService;

    @BeforeEach
    public void setUp() {
        super.setUp();
        // Market Data usually doesn't require API Key, but we pass the client anyway
        marketDataService = new BinanceMarketDataService(spotClient);
    }

    @Test
    public void testCheckServerTime() {
        log.info("Testing Server Time...");
        String time = marketDataService.time();
        log.info("Server Time: {}", time);
        assertNotNull(time);
    }

    @Test
    public void testExchangeInfo() {
        log.info("Testing Exchange Info (BTCUSDT)...");
        BinanceExchangeInfo info = marketDataService.getExchangeInfo("BTCUSDT", null);
        log.info("Exchange Info: {}", info);
        assertNotNull(info);
        // Basic validation
        assertTrue(info.getSymbols().stream().anyMatch(s -> "BTCUSDT".equals(s.getSymbol())));
    }

    @Test
    public void testRecentTrades() {
        log.info("Testing Recent Trades (BTCUSDT)...");
        List<BinanceTrade> trades = marketDataService.getTrades("BTCUSDT", 10);
        log.info("Recent Trades (limit 10): {}", trades);
        assertNotNull(trades);
        assertTrue(trades.size() <= 10);
    }

    @Test
    public void testTicker() {
        log.info("Testing Ticker...");
        String ticker = marketDataService.ticker("BTCUSDT", null);
        log.info("Ticker: {}", ticker);
        assertNotNull(ticker);
    }
}
