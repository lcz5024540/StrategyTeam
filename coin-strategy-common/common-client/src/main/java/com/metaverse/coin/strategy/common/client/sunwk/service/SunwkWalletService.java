package com.metaverse.coin.strategy.common.client.sunwk.service;

import com.metaverse.coin.strategy.common.client.sunwk.SunwkApi;
import com.metaverse.coin.strategy.common.model.sunwk.entity.SunwkDepositAddress;

public class SunwkWalletService extends SunwkService {

    public SunwkWalletService(SunwkApi sunwkApi) {
        super(sunwkApi);
    }

    public SunwkDepositAddress getDepositAddress(String currency) {
        return executeData(sunwkApi.getDepositAddress(currency), "Failed to get deposit address");
    }
}
