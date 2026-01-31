package com.metaverse.coin.strategy.common.client.okx.service;

import com.metaverse.coin.strategy.common.client.okx.OkxApi;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestResponse;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.market.*;

import java.util.List;
import java.util.Map;

public class OkxMarketDataService extends OkxService {

    public OkxMarketDataService(OkxApi okxApi) {
        super(okxApi);
    }

    public OkxRestResponse<OkxTicker> getTickers(String instType, String uly) {
        return execute(okxApi.getTickers(instType, uly), "Get tickers failed");
    }

    public OkxRestResponse<OkxTicker> getTicker(String instId) {
        return execute(okxApi.getTicker(instId), "Get ticker failed");
    }

    public OkxRestResponse<OkxIndexTicker> getIndexTickers(String quoteCcy, String instId) {
        return execute(okxApi.getIndexTickers(quoteCcy, instId), "Get index tickers failed");
    }

    public OkxRestResponse<OkxOrderBook> getOrderBook(String instId, Integer sz) {
        return execute(okxApi.getOrderBook(instId, sz), "Get order book failed");
    }

    public OkxRestResponse<OkxOrderBook> getOrderBookFull(String instId, Integer sz) {
        return execute(okxApi.getOrderBookFull(instId, sz), "Get order book full failed");
    }

    public OkxRestResponse<OkxCandle> getCandles(String instId, String bar, String after, String before, Integer limit) {
        return execute(okxApi.getCandles(instId, bar, after, before, limit), "Get candles failed");
    }

    public OkxRestResponse<OkxCandle> getHistoryCandles(String instId, String bar, String after, String before, Integer limit) {
        return execute(okxApi.getHistoryCandles(instId, bar, after, before, limit), "Get history candles failed");
    }

    public OkxRestResponse<OkxCandle> getIndexCandles(String instId, String bar, String after, String before, Integer limit) {
        return execute(okxApi.getIndexCandles(instId, bar, after, before, limit), "Get index candles failed");
    }

    public OkxRestResponse<OkxCandle> getMarkPriceCandles(String instId, String bar, String after, String before, Integer limit) {
        return execute(okxApi.getMarkPriceCandles(instId, bar, after, before, limit), "Get mark price candles failed");
    }

    public OkxRestResponse<OkxTrade> getTrades(String instId, Integer limit) {
        return execute(okxApi.getTrades(instId, limit), "Get trades failed");
    }

    public OkxRestResponse<OkxTrade> getOptionInstrumentFamilyTrades(String instFamily) {
        return execute(okxApi.getOptionInstrumentFamilyTrades(instFamily), "Get option instrument family trades failed");
    }

    public OkxRestResponse<OkxTrade> getHistoryTrades(String instId, String type, String after, String before, Integer limit) {
        return execute(okxApi.getHistoryTrades(instId, type, after, before, limit), "Get history trades failed");
    }

    public OkxRestResponse<OkxPlatformVolume> getPlatform24Volume() {
        return execute(okxApi.getPlatform24Volume(), "Get platform 24 volume failed");
    }

    public OkxRestResponse<OkxOracle> getOracle() {
        return execute(okxApi.getOracle(), "Get oracle failed");
    }

    public OkxRestResponse<OkxExchangeRate> getExchangeRate() {
        return execute(okxApi.getExchangeRate(), "Get exchange rate failed");
    }

    public OkxRestResponse<OkxIndexComponent> getIndexComponents(String index) {
        return execute(okxApi.getIndexComponents(index), "Get index components failed");
    }

    public OkxRestResponse<OkxTicker> getBlockTickers(String instType, String uly) {
        return execute(okxApi.getBlockTickers(instType, uly), "Get block tickers failed");
    }

    public OkxRestResponse<OkxTicker> getBlockTicker(String instId) {
        return execute(okxApi.getBlockTicker(instId), "Get block ticker failed");
    }

    public OkxRestResponse<OkxTrade> getBlockTrades(String instId) {
        return execute(okxApi.getBlockTrades(instId), "Get block trades failed");
    }

    public OkxRestResponse<Map<String, Object>> getCallAuctionDetails(String instId) {
        return execute(okxApi.getCallAuctionDetails(instId), "Get call auction details failed");
    }
}
