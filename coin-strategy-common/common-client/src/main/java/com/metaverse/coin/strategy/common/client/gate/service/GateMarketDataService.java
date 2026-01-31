package com.metaverse.coin.strategy.common.client.gate.service;

import io.gate.gateapi.ApiException;
import io.gate.gateapi.api.FuturesApi;
import io.gate.gateapi.api.SpotApi;
import io.gate.gateapi.models.FuturesTicker;
import io.gate.gateapi.models.Ticker;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Gate.io Market Data Service
 * <p>
 * Handles retrieval of public market data such as Tickers.
 * </p>
 */
@Slf4j
public class GateMarketDataService extends GateService {

    private final SpotApi spotApi;
    private final FuturesApi futuresApi;

    public GateMarketDataService(SpotApi spotApi, FuturesApi futuresApi) {
        this.spotApi = spotApi;
        this.futuresApi = futuresApi;
    }

    /**
     * Get Spot Tickers.
     *
     * @param currencyPair Currency pair (e.g., "BTC_USDT"). If null, returns all tickers.
     * @return List of Spot Tickers
     */
    public List<Ticker> getSpotTickers(String currencyPair) {
        try {
            return spotApi.listTickers().currencyPair(currencyPair).execute();
        } catch (ApiException e) {
            log.error("Gate API Error: {} {}", e.getCode(), e.getMessage());
            throw new RuntimeException("Gate API Error", e);
        }
    }

    /**
     * Get Futures Tickers.
     *
     * @param settle   Settle currency (e.g., "usdt", "btc").
     * @param contract Contract name (e.g., "BTC_USDT"). If null, returns all tickers for the settle currency.
     * @return List of Futures Tickers
     */
    public List<FuturesTicker> getFuturesTickers(String settle, String contract) {
        try {
            return futuresApi.listFuturesTickers(settle).contract(contract).execute();
        } catch (ApiException e) {
            log.error("Gate API Error: {} {}", e.getCode(), e.getMessage());
            throw new RuntimeException("Gate API Error", e);
        }
    }
}
