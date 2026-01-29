package com.metaverse.coin.strategy.common.client.sunwk.service;

import com.metaverse.coin.strategy.common.client.sunwk.SunwkApi;
import com.metaverse.coin.strategy.common.model.sunwk.entity.SunwkAsset;
import com.metaverse.coin.strategy.common.model.sunwk.entity.SunwkBalance;
import com.metaverse.coin.strategy.common.model.sunwk.entity.SunwkBillRecord;
import com.metaverse.coin.strategy.common.model.sunwk.entity.SunwkFeeRate;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class SunwkAccountService extends SunwkService {

    public SunwkAccountService(SunwkApi sunwkApi) {
        super(sunwkApi);
    }

    public List<SunwkAsset> getAssets() {
        return executeData(sunwkApi.getAssets(Collections.emptyMap()), "Failed to get assets");
    }

    public SunwkBalance getBalance() {
        return executeData(sunwkApi.getBalance(), "Failed to get balance");
    }

    public List<SunwkBillRecord> getBillRecord(Map<String, Object> queryMap) {
        return executeData(sunwkApi.getBillRecord(queryMap), "Failed to get bill records");
    }

    public List<SunwkFeeRate> getFeeRate(String symbol) {
        return executeData(sunwkApi.getFeeRate(symbol), "Failed to get fee rate");
    }
}
