package com.metaverse.coin.strategy.common.client.sunwk.service;

import com.metaverse.coin.strategy.common.client.sunwk.SunwkApi;

public class SunwkMarketDataService extends SunwkService {

    public SunwkMarketDataService(SunwkApi sunwkApi) {
        super(sunwkApi);
    }

    public String getTicker(String contractCode) {
        return execute(sunwkApi.getTicker(contractCode), "Failed to get ticker");
    }

    public String getKlines(String contractCode, String period, Integer limit) {
        return execute(sunwkApi.getKlines(contractCode, period, limit), "Failed to get klines");
    }
}
