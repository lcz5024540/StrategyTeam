package com.metaverse.coin.strategy.job.service;

import com.metaverse.coin.strategy.common.client.binance.BinanceApiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class BinanceTradingService {

    private final BinanceApiService binanceApiService;

    // ================= SPOT TRADING =================

    /**
     * Spot Buy
     */
    public String spotBuy(String symbol, String quantity, String price) {
        log.info("Spot Buy: symbol={}, quantity={}, price={}", symbol, quantity, price);
        // Type defaults to LIMIT if price is provided, else MARKET?
        // Usually safe to assume LIMIT for trading strategies, or MARKET if price is null.
        String type = (price == null) ? "MARKET" : "LIMIT";
        return binanceApiService.spotBuy(symbol, type, quantity, price);
    }

    /**
     * Spot Sell
     */
    public String spotSell(String symbol, String quantity, String price) {
        log.info("Spot Sell: symbol={}, quantity={}, price={}", symbol, quantity, price);
        String type = (price == null) ? "MARKET" : "LIMIT";
        return binanceApiService.spotSell(symbol, type, quantity, price);
    }

    // ================= FUTURES TRADING =================

    /**
     * Open Long (Hedge Mode)
     */
    public String openLong(String symbol, String quantity, String price) {
        log.info("Futures Open Long: symbol={}, quantity={}, price={}", symbol, quantity, price);
        String type = (price == null) ? "MARKET" : "LIMIT";
        return binanceApiService.openLong(symbol, type, quantity, price, "GTC");
    }

    /**
     * Close Long (Hedge Mode)
     */
    public String closeLong(String symbol, String quantity, String price) {
        log.info("Futures Close Long: symbol={}, quantity={}, price={}", symbol, quantity, price);
        String type = (price == null) ? "MARKET" : "LIMIT";
        return binanceApiService.closeLong(symbol, type, quantity, price);
    }

    /**
     * Open Short (Hedge Mode)
     */
    public String openShort(String symbol, String quantity, String price) {
        log.info("Futures Open Short: symbol={}, quantity={}, price={}", symbol, quantity, price);
        String type = (price == null) ? "MARKET" : "LIMIT";
        return binanceApiService.openShort(symbol, type, quantity, price, "GTC");
    }

    /**
     * Close Short (Hedge Mode)
     */
    public String closeShort(String symbol, String quantity, String price) {
        log.info("Futures Close Short: symbol={}, quantity={}, price={}", symbol, quantity, price);
        String type = (price == null) ? "MARKET" : "LIMIT";
        return binanceApiService.closeShort(symbol, type, quantity, price);
    }

    /**
     * Adjust Leverage
     */
    public String setLeverage(String symbol, Integer leverage) {
        log.info("Setting Leverage: symbol={}, leverage={}", symbol, leverage);
        return binanceApiService.changeInitialLeverage(symbol, leverage);
    }

    /**
     * Adjust Margin Type (Cross/Isolated)
     * @param marginType "ISOLATED" or "CROSSED"
     */
    public String setMarginType(String symbol, String marginType) {
        log.info("Setting Margin Type: symbol={}, marginType={}", symbol, marginType);
        return binanceApiService.changeMarginType(symbol, marginType);
    }
}
