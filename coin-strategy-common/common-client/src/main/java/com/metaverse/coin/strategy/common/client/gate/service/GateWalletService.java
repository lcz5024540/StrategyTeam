package com.metaverse.coin.strategy.common.client.gate.service;

import com.metaverse.coin.strategy.common.client.gate.GateApi;

public class GateWalletService extends GateService {

    public GateWalletService(GateApi gateApi) {
        super(gateApi);
    }

    public String getTotalBalance(String currency) {
        return execute(gateApi.getTotalBalance(currency));
    }
}
