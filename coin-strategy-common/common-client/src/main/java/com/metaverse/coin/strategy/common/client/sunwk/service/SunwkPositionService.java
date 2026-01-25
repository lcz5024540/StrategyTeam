package com.metaverse.coin.strategy.common.client.sunwk.service;

import com.metaverse.coin.strategy.common.client.sunwk.SunwkApi;

public class SunwkPositionService extends SunwkService {

    public SunwkPositionService(SunwkApi sunwkApi) {
        super(sunwkApi);
    }

    public String getPositions(String contractCode, String symbol) {
        return execute(sunwkApi.getPositions(contractCode, symbol), "Failed to get positions");
    }
}
