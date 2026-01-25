package com.metaverse.coin.strategy.common.client.okx.service;

import com.metaverse.coin.strategy.common.client.okx.OkxApi;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestResponse;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.account.Balance;

import java.util.HashMap;
import java.util.Map;

public class OkxAccountService extends OkxService {

    public OkxAccountService(OkxApi okxApi) {
        super(okxApi);
    }

    public OkxRestResponse<Balance> getBalance(String ccy) {
        return execute(okxApi.getBalance(ccy), "Get balance failed");
    }

    public OkxRestResponse<Object> setLeverage(String instId, String mgnMode, String lever) {
        Map<String, Object> req = new HashMap<>();
        req.put("instId", instId);
        req.put("mgnMode", mgnMode);
        req.put("lever", lever);
        return execute(okxApi.setLeverage(req), "Set leverage failed");
    }

    public OkxRestResponse<Map<String, Object>> getPositions(String instType, String instId) {
        return execute(okxApi.getPositions(instType, instId), "Get positions failed");
    }

    public OkxRestResponse<Map<String, Object>> getAccountConfig() {
        return execute(okxApi.getAccountConfig(), "Get account config failed");
    }

    public OkxRestResponse<Map<String, Object>> getMaxSize(Map<String, Object> queryMap) {
        return execute(okxApi.getMaxSize(queryMap), "Get max size failed");
    }
}
