package com.metaverse.coin.strategy.common.client.sunwk.service;

import com.metaverse.coin.strategy.common.client.sunwk.SunwkApi;
import com.metaverse.coin.strategy.common.model.sunwk.entity.SunwkPosition;
import com.metaverse.coin.strategy.common.model.sunwk.entity.SunwkPositionHistory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SunwkPositionService extends SunwkService {

    public SunwkPositionService(SunwkApi sunwkApi) {
        super(sunwkApi);
    }

    public List<SunwkPosition> getPositions(String contractCode, String symbol) {
        return executeData(sunwkApi.getPositions(contractCode, symbol), "Failed to get positions");
    }

    public List<SunwkPositionHistory> getPositionHistory(Map<String, Object> queryMap) {
        return executeData(sunwkApi.getPositionHistory(queryMap), "Failed to get position history");
    }

    public Map<String, Object> setLeverage(String contractCode, int leverRate) {
        Map<String, Object> req = new HashMap<>();
        req.put("contract_code", contractCode);
        req.put("lever_rate", leverRate);
        return executeData(sunwkApi.setLeverage(req), "Failed to set leverage");
    }

    public Map<String, Object> setMarginMode(String contractCode, String marginMode) {
        Map<String, Object> req = new HashMap<>();
        req.put("contract_code", contractCode);
        req.put("margin_mode", marginMode);
        return executeData(sunwkApi.setMarginMode(req), "Failed to set margin mode");
    }

    public Map<String, Object> transferMargin(String contractCode, String amount, String type) {
        Map<String, Object> req = new HashMap<>();
        req.put("contract_code", contractCode);
        req.put("amount", amount);
        req.put("type", type);
        return executeData(sunwkApi.transferMargin(req), "Failed to transfer margin");
    }

    public Map<String, Object> getPositionLimit(String contractCode) {
        return executeData(sunwkApi.getPositionLimit(contractCode), "Failed to get position limit");
    }

    public List<Map<String, Object>> getPositionRisk(String contractCode) {
        return executeData(sunwkApi.getPositionRisk(contractCode), "Failed to get position risk");
    }
}
