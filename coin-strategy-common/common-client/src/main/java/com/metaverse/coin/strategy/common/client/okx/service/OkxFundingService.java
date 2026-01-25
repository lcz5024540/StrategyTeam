package com.metaverse.coin.strategy.common.client.okx.service;

import com.metaverse.coin.strategy.common.client.okx.OkxApi;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestResponse;

import java.util.Map;

public class OkxFundingService extends OkxService {

    public OkxFundingService(OkxApi okxApi) {
        super(okxApi);
    }

    public OkxRestResponse<Map<String, Object>> getCurrencies(String ccy) {
        return execute(okxApi.getCurrencies(ccy), "Get currencies failed");
    }

    public OkxRestResponse<Map<String, Object>> getAssetBalances(String ccy) {
        return execute(okxApi.getAssetBalances(ccy), "Get asset balances failed");
    }

    public OkxRestResponse<Map<String, Object>> assetTransfer(Map<String, Object> req) {
        return execute(okxApi.assetTransfer(req), "Asset transfer failed");
    }

    public OkxRestResponse<Map<String, Object>> getAssetBills(String ccy, String type, String after, String before, Integer limit) {
        return execute(okxApi.getAssetBills(ccy, type, after, before, limit), "Get asset bills failed");
    }

    public OkxRestResponse<Map<String, Object>> getDepositLightning(String ccy, String amt, String to) {
        return execute(okxApi.getDepositLightning(ccy, amt, to), "Get deposit lightning failed");
    }

    public OkxRestResponse<Map<String, Object>> getDepositAddress(String ccy) {
        return execute(okxApi.getDepositAddress(ccy), "Get deposit address failed");
    }

    public OkxRestResponse<Map<String, Object>> getDepositHistory(String ccy, String depId, String txId, String type, String state, String after, String before, Integer limit) {
        return execute(okxApi.getDepositHistory(ccy, depId, txId, type, state, after, before, limit), "Get deposit history failed");
    }

    public OkxRestResponse<Map<String, Object>> withdrawal(Map<String, Object> req) {
        return execute(okxApi.withdrawal(req), "Withdrawal failed");
    }

    public OkxRestResponse<Map<String, Object>> getWithdrawalHistory(String ccy, String wdId, String clientId, String txId, String type, String state, String after, String before, Integer limit) {
        return execute(okxApi.getWithdrawalHistory(ccy, wdId, clientId, txId, type, state, after, before, limit), "Get withdrawal history failed");
    }

    public OkxRestResponse<Map<String, Object>> cancelWithdrawal(Map<String, Object> req) {
        return execute(okxApi.cancelWithdrawal(req), "Cancel withdrawal failed");
    }

    public OkxRestResponse<Map<String, Object>> convertDustAssets(Map<String, Object> req) {
        return execute(okxApi.convertDustAssets(req), "Convert dust assets failed");
    }
}
