package com.metaverse.coin.strategy.core.technical.analysis;

import com.metaverse.coin.strategy.common.client.binance.BinanceApiService;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceBookTicker;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceKline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

@Service
@ConditionalOnExpression("'${binance.api.api-key:}' != '' && '${binance.api.secret-key:}' != ''")
public class MarketAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(MarketAnalysisService.class);

    private final BinanceApiService binanceApiService;

    public MarketAnalysisService(BinanceApiService binanceApiService) {
        this.binanceApiService = binanceApiService;
    }

    /**
     * Fetch K-lines and convert to ta4j BarSeries.
     * @param symbol Symbol (e.g. "BTCUSDT")
     * @param interval Interval (e.g. "1h", "15m")
     * @param limit Number of candles
     * @return BarSeries
     */
    public BarSeries getBarSeries(String symbol, String interval, int limit) {
        try {
            List<BinanceKline> klines = binanceApiService.klines(symbol, interval, limit);
            BarSeries series = new BaseBarSeriesBuilder().withName(symbol).build();

            for (BinanceKline kline : klines) {
                ZonedDateTime endTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(kline.getCloseTime()), ZoneId.of("UTC"));
                series.addBar(
                        endTime,
                        kline.getOpenPrice(),
                        kline.getHighPrice(),
                        kline.getLowPrice(),
                        kline.getClosePrice(),
                        kline.getVolume()
                );
            }
            return series;
        } catch (Exception e) {
            log.error("Error fetching bar series for {}: {}", symbol, e.getMessage());
            return new BaseBarSeriesBuilder().withName(symbol).build();
        }
    }

    /**
     * Analyze Order Book Imbalance from Book Ticker (Best Bid/Ask).
     * For full depth, we would need getOrderBook.
     * @param symbol Symbol
     * @return Imbalance Ratio (BidVolume / AskVolume). > 1 means buy pressure.
     */
    public double getOrderBookImbalance(String symbol) {
        try {
            BinanceBookTicker ticker = binanceApiService.getTickerBook(symbol);
            if (ticker == null) return 0.0;

            BigDecimal bidQty = ticker.getBidQty();
            BigDecimal askQty = ticker.getAskQty();

            if (askQty.compareTo(BigDecimal.ZERO) == 0) return 999.0; // Infinite buy pressure

            return bidQty.divide(askQty, 4, BigDecimal.ROUND_HALF_UP).doubleValue();
        } catch (Exception e) {
            log.error("Error analyzing order book for {}: {}", symbol, e.getMessage());
            return 0.0;
        }
    }
}
