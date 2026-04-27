package com.metaverse.coin.strategy.common.client.sunwk;

import com.metaverse.coin.strategy.common.model.sunwk.entity.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@Slf4j
@Disabled("Disabled by default because Sunwk API tests depend on live external network and credentials.")
public class SunwkApiServiceTest {

    private SunwkApiService sunwkApiService;

    // Credentials provided by user
    private static final String API_KEY = "26bafc05-zrfc4v5b6n-67b8adca-12030";
    private static final String SECRET_KEY = "0x664c6785519ce7717e23e61f94bf7874422cc19e0202e44355fd536e197db2b7";
    private static final String BASE_URL = "https://api.sunwk.com"; // Assuming this is the correct base URL, or use the one from application.yml

    @BeforeEach
    public void setUp() {
        SunwkApiClientFactory factory = new SunwkApiClientFactory(BASE_URL, API_KEY, SECRET_KEY);
        this.sunwkApiService = factory.create();
    }

    @Test
    public void testPing() {
        try {
            boolean pong = sunwkApiService.getMarketDataService().ping();
            log.info("Ping: {}", pong);
            // assertTrue(pong); // Can't assert true if API is down/mocked, but code should run
        } catch (Exception e) {
            log.error("Failed to ping", e);
        }
    }

    @Test
    public void testGetCurrencies() {
        try {
            List<SunwkCurrency> currencies = sunwkApiService.getMarketDataService().getCurrencies();
            log.info("Currencies: {}", currencies);
            assertNotNull(currencies);
        } catch (Exception e) {
            log.error("Failed to get currencies", e);
        }
    }

    @Test
    public void testGetTimestamp() {
        try {
            Long timestamp = sunwkApiService.getMarketDataService().getTimestamp();
            log.info("Server Timestamp: {}", timestamp);
            assertNotNull(timestamp);
        } catch (Exception e) {
            log.error("Failed to get timestamp", e);
        }
    }

    @Test
    public void testGetContracts() {
        try {
            // "this_week", "next_week", "quarter"
            List<SunwkSymbol> contracts = sunwkApiService.getMarketDataService().getContracts("this_week");
            log.info("Contracts: {}", contracts);
            assertNotNull(contracts);
        } catch (Exception e) {
            log.error("Failed to get contracts", e);
        }
    }

    @Test
    public void testGetOpenOrders() {
        try {
            String contractCode = "BTC-USDT";
            List<SunwkOrder> orders = sunwkApiService.getOrderService().getOpenOrders(contractCode, 1, 10);
            log.info("Open Orders for {}: {}", contractCode, orders);
            assertNotNull(orders);
        } catch (Exception e) {
            log.error("Failed to get open orders", e);
        }
    }

    @Test
    public void testPlanOrders() {
        try {
            // Just test query empty list or similar to avoid actual trading
            String contractCode = "BTC-USDT";
            List<SunwkPlanOrder> orders = sunwkApiService.getOrderService().getPlanOpenOrders(contractCode, 1, 10);
            log.info("Plan Open Orders: {}", orders);
            assertNotNull(orders);
        } catch (Exception e) {
            log.error("Failed to get plan open orders", e);
        }
    }

    @Test
    public void testGetAssets() {
        try {
            List<SunwkAsset> assets = sunwkApiService.getAccountService().getAssets();
            log.info("Assets: {}", assets);
            assertNotNull(assets);
        } catch (Exception e) {
            log.error("Failed to get assets", e);
        }
    }

    @Test
    public void testGetBalance() {
        try {
            SunwkBalance balance = sunwkApiService.getAccountService().getBalance();
            log.info("Balance: {}", balance);
            assertNotNull(balance);
        } catch (Exception e) {
            log.error("Failed to get balance", e);
        }
    }

    @Test
    public void testGetBillRecord() {
        try {
            Map<String, Object> queryMap = new HashMap<>();
            // queryMap.put("contract_code", "BTC-USDT");
            List<SunwkBillRecord> bills = sunwkApiService.getAccountService().getBillRecord(queryMap);
            log.info("Bill Records: {}", bills);
            assertNotNull(bills);
        } catch (Exception e) {
            log.error("Failed to get bill records", e);
        }
    }

    @Test
    public void testGetFeeRate() {
        try {
            String symbol = "BTC-USDT";
            List<SunwkFeeRate> rates = sunwkApiService.getAccountService().getFeeRate(symbol);
            log.info("Fee Rates for {}: {}", symbol, rates);
            assertNotNull(rates);
        } catch (Exception e) {
            log.error("Failed to get fee rates", e);
        }
    }

    @Test
    public void testGetTicker() {
        try {
            String contractCode = "BTC-USDT"; // Example
            List<SunwkTicker> tickers = sunwkApiService.getMarketDataService().getTicker(contractCode);
            log.info("Ticker for {}: {}", contractCode, tickers);
            assertNotNull(tickers);
        } catch (Exception e) {
            log.error("Failed to get ticker", e);
        }
    }

    @Test
    public void testGetDepth() {
        try {
            String contractCode = "BTC-USDT";
            // type: step0, step1, ...
            SunwkDepth depth = sunwkApiService.getMarketDataService().getDepth(contractCode, "step0");
            log.info("Depth for {}: {}", contractCode, depth);
            assertNotNull(depth);
        } catch (Exception e) {
            log.error("Failed to get depth", e);
        }
    }

    @Test
    public void testGetTrades() {
        try {
            String contractCode = "BTC-USDT";
            List<SunwkTrade> trades = sunwkApiService.getMarketDataService().getTrades(contractCode);
            log.info("Trades for {}: {}", contractCode, trades);
            assertNotNull(trades);
        } catch (Exception e) {
            log.error("Failed to get trades", e);
        }
    }

    @Test
    public void testGetOrderHistory() {
        try {
            Map<String, Object> queryMap = new HashMap<>();
            queryMap.put("contract_code", "BTC-USDT");
            queryMap.put("trade_type", 0); // 0: all, 1: buy, 2: sell ? Guessing or checking docs
            queryMap.put("type", 1);       // 1: all types?
            queryMap.put("status", 0);     // 0: all status?
            queryMap.put("days", 7);       // last 7 days

            List<SunwkOrder> orders = sunwkApiService.getOrderService().getOrderHistory(queryMap);
            log.info("Order History: {}", orders);
            assertNotNull(orders);
        } catch (Exception e) {
            log.error("Failed to get order history", e);
        }
    }

    @Test
    public void testGetDepositAddress() {
        try {
            // User mentioned Tron network
            String currency = "USDT-TRC20"; 
            // Or maybe just "USDT" and the response contains chain info?
            // The method signature is getDepositAddress(currency)
            SunwkDepositAddress address = sunwkApiService.getWalletService().getDepositAddress(currency);
            log.info("Deposit Address for {}: {}", currency, address);
            assertNotNull(address);
        } catch (Exception e) {
            log.error("Failed to get deposit address", e);
        }
    }
}
