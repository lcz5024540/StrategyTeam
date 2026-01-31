package com.metaverse.coin.strategy.common.client.okx.service;

import com.metaverse.coin.strategy.common.client.okx.OkxApi;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestResponse;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.finance.savings.*;

import java.math.BigDecimal;
import java.util.Map;

public class OkxSavingsService extends OkxService {

    public OkxSavingsService(OkxApi okxApi) {
        super(okxApi);
    }

    public OkxRestResponse<OkxSavingsBalance> getSavingsBalance(String ccy) {
        return execute(okxApi.getSavingsBalance(ccy), "Get savings balance failed");
    }

    public OkxRestResponse<OkxSavingsPurchaseRedemptRsp> purchaseRedempt(String ccy, BigDecimal amt, String side, BigDecimal rate) {
        OkxSavingsPurchaseRedemptReq req = OkxSavingsPurchaseRedemptReq.builder()
                .ccy(ccy)
                .amt(amt)
                .side(side)
                .rate(rate)
                .build();
        return execute(okxApi.savingsPurchaseRedempt(req), "Savings purchase/redempt failed");
    }

    public OkxRestResponse<OkxSavingsSetLendingRateRsp> setLendingRate(String ccy, BigDecimal rate) {
        OkxSavingsSetLendingRateReq req = OkxSavingsSetLendingRateReq.builder()
                .ccy(ccy)
                .rate(rate)
                .build();
        return execute(okxApi.setSavingsLendingRate(req), "Set savings lending rate failed");
    }

    public OkxRestResponse<OkxSavingsLendingHistory> getLendingHistory(Map<String, Object> queryMap) {
        return execute(okxApi.getSavingsLendingHistory(queryMap), "Get savings lending history failed");
    }

    public OkxRestResponse<OkxSavingsLendingRateSummary> getLendingRateSummary(String ccy) {
        return execute(okxApi.getSavingsLendingRateSummary(ccy), "Get savings lending rate summary failed");
    }

    public OkxRestResponse<OkxSavingsLendingRateHistory> getLendingRateHistory(Map<String, Object> queryMap) {
        return execute(okxApi.getSavingsLendingRateHistory(queryMap), "Get savings lending rate history failed");
    }
}
