package com.metaverse.coin.strategy.common.client.okx.service;

import com.metaverse.coin.strategy.common.client.okx.OkxApi;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestResponse;

import java.util.List;
import java.util.Map;

public class OkxMarketDataService extends OkxService {

    public OkxMarketDataService(OkxApi okxApi) {
        super(okxApi);
    }

    public OkxRestResponse<Map<String, Object>> getTickers(String instType, String uly) {
        return execute(okxApi.getTickers(instType, uly), "Get tickers failed");
    }

    public OkxRestResponse<Map<String, Object>> getTicker(String instId) {
        return execute(okxApi.getTicker(instId), "Get ticker failed");
    }

    public OkxRestResponse<Map<String, Object>> getOrderBook(String instId, Integer sz) {
        return execute(okxApi.getOrderBook(instId, sz), "Get order book failed");
    }

    public OkxRestResponse<List<String>> getCandles(String instId, String bar, String after, String before, Integer limit) {
        return execute(okxApi.getCandles(instId, bar, after, before, limit), "Get candles failed");
    }

    public OkxRestResponse<List<String>> getHistoryCandles(String instId, String bar, String after, String before, Integer limit) {
        return execute(okxApi.getHistoryCandles(instId, bar, after, before, limit), "Get history candles failed");
    }
}
