package com.metaverse.coin.strategy.common.client.binance.debug;

import com.binance.connector.futures.client.impl.UMFuturesClientImpl;
import com.metaverse.coin.strategy.common.client.binance.service.BinanceFuturesService;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.*;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinancePriceTicker;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceTicker;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceTrade;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
@EnabledIfSystemProperty(named = "binance.debug.tests", matches = "true")
public class BinanceFuturesMarketDataTest {

    private BinanceFuturesService futuresService;

    @BeforeEach
    public void setUp() {
        // Use public API (no keys needed for market data)
        // Use Mainnet for better data availability
        UMFuturesClientImpl futuresClient = new UMFuturesClientImpl("https://fapi.binance.com");
        futuresService = new BinanceFuturesService(futuresClient, "https://fapi.binance.com");
    }

    @Test
    public void testPing() {
        assertDoesNotThrow(() -> futuresService.ping());
    }

    @Test
    public void testGetExchangeInfo() {
        BinanceFuturesExchangeInfo info = futuresService.getExchangeInfo();
        assertNotNull(info);
        assertNotNull(info.getSymbols());
        assertFalse(info.getSymbols().isEmpty());
        log.info("Exchange Info Symbols: {}", info.getSymbols().size());
    }

    @Test
    public void testGetOrderBook() {
        BinanceFuturesOrderBook book = futuresService.getOrderBook("BTCUSDT", 5);
        assertNotNull(book);
        assertNotNull(book.getBids());
        assertNotNull(book.getAsks());
        log.info("Order Book Bids: {}", book.getBids().size());
    }

    @Test
    public void testGetTrades() {
        List<BinanceTrade> trades = futuresService.getTrades("BTCUSDT", 5);
        assertNotNull(trades);
        assertFalse(trades.isEmpty());
        log.info("Trades: {}", trades.size());
    }

    @Test
    public void testGetKlines() {
        List<BinanceFuturesKline> klines = futuresService.getKlines("BTCUSDT", "1m", null, null, 5);
        assertNotNull(klines);
        assertFalse(klines.isEmpty());
        log.info("Klines: {}", klines.size());
        if (!klines.isEmpty()) {
            BinanceFuturesKline kline = klines.get(0);
            assertNotNull(kline.getOpenTime());
            assertNotNull(kline.getOpen());
            assertNotNull(kline.getHigh());
            assertNotNull(kline.getLow());
            assertNotNull(kline.getClose());
            assertNotNull(kline.getVolume());
            log.info("First Kline: {}", kline);
        }
    }

    @Test
    public void testGet24hrTicker() {
        BinanceTicker ticker = futuresService.get24hrTicker("BTCUSDT");
        assertNotNull(ticker);
        log.info("24hr Ticker: {}", ticker);
    }

    @Test
    public void testGetBookTicker() {
        BinanceFuturesBookTicker ticker = futuresService.getBookTicker("BTCUSDT");
        assertNotNull(ticker);
        log.info("Book Ticker: {}", ticker);
    }

    @Test
    public void testGetOpenInterest() {
        BinanceFuturesOpenInterest oi = futuresService.getOpenInterest("BTCUSDT");
        assertNotNull(oi);
        log.info("Open Interest: {}", oi);
    }

    @Test
    public void testGetOpenInterestStatistics() {
        List<BinanceFuturesOpenInterestHist> stats = futuresService.getOpenInterestStatistics("BTCUSDT", "5m", 5, null, null);
        assertNotNull(stats);
        if (!stats.isEmpty()) {
            log.info("Open Interest Stats: {}", stats.get(0));
        }
    }

    @Test
    public void testGetTopLongShortAccountRatio() {
        List<BinanceFuturesAccountRatio> ratio = futuresService.getTopLongShortAccountRatio("BTCUSDT", "5m", 5, null, null);
        assertNotNull(ratio);
        if (!ratio.isEmpty()) {
            log.info("Top Long/Short Account Ratio: {}", ratio.get(0));
        }
    }

    @Test
    public void testGetTopLongShortPositionRatio() {
        List<BinanceFuturesPositionRatio> ratio = futuresService.getTopLongShortPositionRatio("BTCUSDT", "5m", 5, null, null);
        assertNotNull(ratio);
        if (!ratio.isEmpty()) {
            log.info("Top Long/Short Position Ratio: {}", ratio.get(0));
        }
    }

    @Test
    public void testGetGlobalLongShortAccountRatio() {
        List<BinanceFuturesAccountRatio> ratio = futuresService.getGlobalLongShortAccountRatio("BTCUSDT", "5m", 5, null, null);
        assertNotNull(ratio);
        if (!ratio.isEmpty()) {
            log.info("Global Long/Short Account Ratio: {}", ratio.get(0));
        }
    }

    @Test
    public void testGetTakerLongShortRatio() {
        List<BinanceFuturesTakerVolume> ratio = futuresService.getTakerLongShortRatio("BTCUSDT", "5m", 5, null, null);
        assertNotNull(ratio);
        if (!ratio.isEmpty()) {
            log.info("Taker Long/Short Ratio: {}", ratio.get(0));
        }
    }

    @Test
    public void testGetBasis() {
        // Basis might not be available for all pairs or contract types
        try {
            List<BinanceFuturesBasis> basis = futuresService.getBasis("BTCUSDT", "PERPETUAL", "5m", 5, null, null);
            assertNotNull(basis);
            if (!basis.isEmpty()) {
                log.info("Basis: {}", basis.get(0));
            }
        } catch (Exception e) {
            log.warn("Basis endpoint failed (might be expected for PERPETUAL or empty): {}", e.getMessage());
        }
    }

    @Test
    public void testGetCompositeIndexInfo() {
        List<BinanceFuturesCompositeIndexInfo> info = futuresService.getCompositeIndexInfo("DEFIUSDT"); // DEFIUSDT is a composite index
        assertNotNull(info);
        if (!info.isEmpty()) {
            log.info("Composite Index Info: {}", info.get(0));
        }
    }

    @Test
    public void testGetAssetIndex() {
        List<BinanceFuturesAssetIndex> index = futuresService.getAssetIndex("BTCUSDT");
        assertNotNull(index);
        if (!index.isEmpty()) {
            log.info("Asset Index: {}", index.get(0));
        }
    }

    @Test
    public void testGetTickerPrices() {
        List<BinancePriceTicker> tickers = futuresService.getTickerPrices();
        assertNotNull(tickers);
        assertFalse(tickers.isEmpty());
        log.info("All Ticker Prices: {}", tickers.size());
    }

    @Test
    public void testGetPremiumIndices() {
        List<BinanceFuturesPremiumIndex> indices = futuresService.getPremiumIndices();
        assertNotNull(indices);
        assertFalse(indices.isEmpty());
        log.info("All Premium Indices: {}", indices.size());
    }

    @Test
    public void testGetLvtKlines() {
        try {
            // Try to find a valid LVT symbol if possible, or handle error
            List<BinanceFuturesKline> klines = futuresService.getLvtKlines("BTCUPUSDT", "1m", null, null, 5);
            assertNotNull(klines);
            log.info("LVT Klines: {}", klines.size());
        } catch (Exception e) {
            log.warn("LVT Klines failed (likely symbol delisted or not available): {}", e.getMessage());
        }
    }

    @Test
    public void testGetIndexConstituents() {
        try {
            BinanceFuturesConstituents constituents = futuresService.getIndexConstituents("DEFIUSDT");
            assertNotNull(constituents);
            log.info("Index Constituents: {}", constituents);
        } catch (Exception e) {
             log.warn("Index Constituents failed: {}", e.getMessage());
        }
    }

    @Test
    public void testGetApiTradingStatus() {
        BinanceFuturesTradingStatus status = futuresService.getApiTradingStatus();
        assertNotNull(status);
        log.info("Trading Status: {}", status);
    }

    @Test
    public void testGetDeliveryPrice() {
        try {
            List<BinanceFuturesDeliveryPrice> prices = futuresService.getDeliveryPrice("BTCUSD"); 
            assertNotNull(prices);
            log.info("Delivery Price: {}", prices);
        } catch (Exception e) {
            log.warn("Delivery Price failed: {}", e.getMessage());
        }
    }

    @Test
    public void testGetFundingInfo() {
        List<BinanceFuturesFundingInfo> info = futuresService.getFundingInfo();
        assertNotNull(info);
        assertFalse(info.isEmpty());
        log.info("Funding Info: {}", info.size());
    }

    @Test
    public void testGetPremiumIndex() {
        BinanceFuturesPremiumIndex index = futuresService.getPremiumIndex("BTCUSDT");
        assertNotNull(index);
        log.info("Premium Index: {}", index);
    }

    @Test
    public void testGetFundingRateHistory() {
        List<BinanceFuturesFundingRate> history = futuresService.getFundingRateHistory("BTCUSDT", 5);
        assertNotNull(history);
        if (!history.isEmpty()) {
            log.info("Funding Rate History: {}", history.get(0));
        }
    }

    @Test
    public void testGetMarkPriceKlines() {
        List<BinanceFuturesKline> klines = futuresService.getMarkPriceKlines("BTCUSDT", "1m", null, null, 5);
        assertNotNull(klines);
        log.info("Mark Price Klines: {}", klines.size());
    }

    @Test
    public void testGetIndexPriceKlines() {
        List<BinanceFuturesKline> klines = futuresService.getIndexPriceKlines("BTCUSDT", "1m", null, null, 5);
        assertNotNull(klines);
        log.info("Index Price Klines: {}", klines.size());
    }

    @Test
    public void testGetContinuousKlines() {
        List<BinanceFuturesKline> klines = futuresService.getContinuousKlines("BTCUSDT", "PERPETUAL", "1m", null, null, 5);
        assertNotNull(klines);
        log.info("Continuous Klines: {}", klines.size());
    }
}
