package com.metaverse.coin.strategy.common.client.okx.service;

import com.metaverse.coin.strategy.common.client.okx.OkxApi;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestResponse;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.account.*;

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

    public OkxRestResponse<OkxAccountConfig> getAccountConfig() {
        return execute(okxApi.getAccountConfig(), "Get account config failed");
    }

    public OkxRestResponse<OkxMaxSize> getMaxSize(Map<String, Object> queryMap) {
        return execute(okxApi.getMaxSize(queryMap), "Get max size failed");
    }

    public OkxRestResponse<OkxPositionRisk> getPositionRisk(Map<String, Object> queryMap) {
        return execute(okxApi.getPositionRisk(queryMap), "Get position risk failed");
    }

    public OkxRestResponse<OkxBill> getBills(Map<String, Object> queryMap) {
        return execute(okxApi.getBills(queryMap), "Get bills failed");
    }

    public OkxRestResponse<OkxBill> getBillsArchive(Map<String, Object> queryMap) {
        return execute(okxApi.getBillsArchive(queryMap), "Get bills archive failed");
    }

    public OkxRestResponse<OkxMaxAvailSize> getMaxAvailSize(Map<String, Object> queryMap) {
        return execute(okxApi.getMaxAvailSize(queryMap), "Get max avail size failed");
    }

    public OkxRestResponse<OkxTradeFee> getTradeFee(String instType, String instId, String uly, String category) {
        Map<String, Object> queryMap = new HashMap<>();
        if (instType != null) queryMap.put("instType", instType);
        if (instId != null) queryMap.put("instId", instId);
        if (uly != null) queryMap.put("uly", uly);
        if (category != null) queryMap.put("category", category);
        return execute(okxApi.getTradeFee(queryMap), "Get trade fee failed");
    }

    public OkxRestResponse<OkxInterestAccrued> getInterestAccrued(Map<String, Object> queryMap) {
        return execute(okxApi.getInterestAccrued(queryMap), "Get interest accrued failed");
    }

    public OkxRestResponse<OkxInterestRate> getInterestRate(Map<String, Object> queryMap) {
        return execute(okxApi.getInterestRate(queryMap), "Get interest rate failed");
    }

    public OkxRestResponse<OkxMaxLoan> getMaxLoan(Map<String, Object> queryMap) {
        return execute(okxApi.getMaxLoan(queryMap), "Get max loan failed");
    }

    public OkxRestResponse<OkxPositionMode> setPositionMode(String posMode) {
        Map<String, Object> req = new HashMap<>();
        req.put("posMode", posMode);
        return execute(okxApi.setPositionMode(req), "Set position mode failed");
    }

    public OkxRestResponse<OkxLeverageInfo> getLeverageInfo(String instId, String mgnMode) {
        Map<String, Object> queryMap = new HashMap<>();
        queryMap.put("instId", instId);
        queryMap.put("mgnMode", mgnMode);
        return execute(okxApi.getLeverageInfo(queryMap), "Get leverage info failed");
    }
}
