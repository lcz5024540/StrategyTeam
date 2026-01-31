package com.metaverse.coin.strategy.core.technical;

import com.metaverse.coin.strategy.core.Strategy;
import com.metaverse.coin.strategy.core.technical.analysis.MarketAnalysisService;
import com.metaverse.coin.strategy.core.technical.indicator.TechnicalIndicatorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Conventional Technical Analysis Strategy.
 * Uses MACD, EMA, Volume, and Order Book Imbalance.
 */
@Component
public class TechnicalAnalysisStrategy implements Strategy {

    private static final Logger log = LoggerFactory.getLogger(TechnicalAnalysisStrategy.class);
    private static final String STRATEGY_ID = "CONVENTIONAL_TECHNICAL_STRATEGY";

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private final MarketAnalysisService marketAnalysisService;
    private final TechnicalIndicatorService indicatorService;

    // Config
    private String symbol = "BTCUSDT";
    private String interval = "1h";
    private int period = 14; // EMA/RSI period

    public TechnicalAnalysisStrategy(MarketAnalysisService marketAnalysisService) {
        this.marketAnalysisService = marketAnalysisService;
        this.indicatorService = new TechnicalIndicatorService();
    }

    @Override
    public String getStrategyId() {
        return STRATEGY_ID;
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("Starting {} for {}", STRATEGY_ID, symbol);
            scheduler.scheduleAtFixedRate(this::analyze, 0, 1, TimeUnit.MINUTES);
        } else {
            log.warn("{} is already running.", STRATEGY_ID);
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping {}", STRATEGY_ID);
            // In a real app, we might want to shut down the scheduler properly
            // scheduler.shutdown(); 
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void analyze() {
        if (!running.get()) return;

        try {
            // 1. Get Market Data (Candles)
            BarSeries series = marketAnalysisService.getBarSeries(symbol, interval, 100);
            if (series.getBarCount() < 50) {
                log.warn("Not enough data for {}", symbol);
                return;
            }

            // 2. Calculate Indicators
            Map<String, Double> macd = indicatorService.calculateMACD(series);
            double ema = indicatorService.calculateEMA(series, period);
            double rsi = indicatorService.calculateRSI(series, period);
            double volume = indicatorService.getCurrentVolume(series);

            // 3. Analyze Order Book (Market Buy/Sell Pressure)
            double imbalance = marketAnalysisService.getOrderBookImbalance(symbol);

            // 4. Log Analysis
            log.info("Analysis for {}: Price={}, EMA={}, RSI={}, MACD={}, Hist={}, Vol={}, Imbalance={}",
                    symbol, series.getLastBar().getClosePrice(), String.format("%.2f", ema), 
                    String.format("%.2f", rsi), String.format("%.2f", macd.get("MACD")), 
                    String.format("%.2f", macd.get("Hist")), volume, String.format("%.2f", imbalance));

            // 5. Generate Signals (Simple Logic)
            generateSignal(series.getLastBar().getClosePrice().doubleValue(), ema, macd, rsi, imbalance);

        } catch (Exception e) {
            log.error("Error in strategy analysis loop", e);
        }
    }

    private void generateSignal(double currentPrice, double ema, Map<String, Double> macd, double rsi, double imbalance) {
        boolean trendUp = currentPrice > ema;
        boolean macdBullish = macd.get("Hist") > 0 && macd.get("MACD") > macd.get("Signal");
        boolean notOverbought = rsi < 70;
        boolean buyPressure = imbalance > 1.2; // 20% more bids

        if (trendUp && macdBullish && notOverbought && buyPressure) {
            log.info(">>> BUY SIGNAL DETECTED <<<");
            // executeTrade(Side.BUY);
        } else if (!trendUp && !macdBullish && rsi > 30 && imbalance < 0.8) {
            log.info(">>> SELL SIGNAL DETECTED <<<");
            // executeTrade(Side.SELL);
        }
    }
}
