package com.metaverse.coin.strategy.common.client.sunwk.service;

import com.metaverse.coin.strategy.common.client.sunwk.SunwkApi;

import java.util.Collections;

public class SunwkAccountService extends SunwkService {

    public SunwkAccountService(SunwkApi sunwkApi) {
        super(sunwkApi);
    }

    public String getAssets() {
        return execute(sunwkApi.getAssets(Collections.emptyMap()), "Failed to get assets");
    }
}
