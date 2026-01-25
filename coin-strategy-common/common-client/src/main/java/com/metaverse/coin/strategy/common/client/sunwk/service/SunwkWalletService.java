package com.metaverse.coin.strategy.common.client.sunwk.service;

import com.metaverse.coin.strategy.common.client.sunwk.SunwkApi;

public class SunwkWalletService extends SunwkService {

    public SunwkWalletService(SunwkApi sunwkApi) {
        super(sunwkApi);
    }

    public String getDepositAddress(String currency) {
        return execute(sunwkApi.getDepositAddress(currency), "Failed to get deposit address");
    }
}
