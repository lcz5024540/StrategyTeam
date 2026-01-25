package com.metaverse.coin.strategy.common.client.gate.service;

import com.metaverse.coin.strategy.common.client.gate.GateApi;

public class GateAccountService extends GateService {

    public GateAccountService(GateApi gateApi) {
        super(gateApi);
    }

    public String getSpotAccounts(String currency) {
        return execute(gateApi.getSpotAccounts(currency));
    }

    public String getFuturesAccounts(String settle) {
        return execute(gateApi.getFuturesAccounts(settle));
    }
}
