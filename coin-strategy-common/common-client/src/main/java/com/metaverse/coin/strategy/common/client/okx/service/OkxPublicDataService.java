package com.metaverse.coin.strategy.common.client.okx.service;

import com.metaverse.coin.strategy.common.client.okx.OkxApi;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestResponse;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.market.OkxTrade;

import java.util.Map;

public class OkxPublicDataService extends OkxService {

    public OkxPublicDataService(OkxApi okxApi) {
        super(okxApi);
    }

    public OkxRestResponse<Map<String, Object>> getInstruments(String instType, String uly, String instId) {
        return execute(okxApi.getInstruments(instType, uly, instId), "Get instruments failed");
    }

    public OkxRestResponse<OkxTrade> getPublicOptionTrades(String instId, Integer limit) {
        return execute(okxApi.getPublicOptionTrades(instId, limit), "Get public option trades failed");
    }

    public OkxRestResponse<Map<String, Object>> getDeliveryExerciseHistory(String instType, String uly, String after, String before, Integer limit) {
        return execute(okxApi.getDeliveryExerciseHistory(instType, uly, after, before, limit), "Get delivery exercise history failed");
    }

    public OkxRestResponse<Map<String, Object>> getOpenInterest(String instType, String uly, String instId) {
        return execute(okxApi.getOpenInterest(instType, uly, instId), "Get open interest failed");
    }

    public OkxRestResponse<Map<String, Object>> getFundingRate(String instId) {
        return execute(okxApi.getFundingRate(instId), "Get funding rate failed");
    }

    public OkxRestResponse<Map<String, Object>> getFundingRateHistory(String instId, String after, String before, Integer limit) {
        return execute(okxApi.getFundingRateHistory(instId, after, before, limit), "Get funding rate history failed");
    }

    public OkxRestResponse<Map<String, Object>> getPriceLimit(String instId) {
        return execute(okxApi.getPriceLimit(instId), "Get price limit failed");
    }

    public OkxRestResponse<Map<String, Object>> getOptSummary(String uly, String expTime) {
        return execute(okxApi.getOptSummary(uly, expTime), "Get opt summary failed");
    }

    public OkxRestResponse<Map<String, Object>> getEstimatedPrice(String instId) {
        return execute(okxApi.getEstimatedPrice(instId), "Get estimated price failed");
    }

    public OkxRestResponse<Map<String, Object>> getDiscountRateInterestFreeQuota(String ccy) {
        return execute(okxApi.getDiscountRateInterestFreeQuota(ccy), "Get discount rate interest free quota failed");
    }

    public OkxRestResponse<Map<String, Object>> getSystemTime() {
        return execute(okxApi.getSystemTime(), "Get system time failed");
    }

    public OkxRestResponse<Map<String, Object>> getLiquidationOrders(String instType, String mgnMode, String instId, String ccy, String uly, String alias, String state, String before, String after, Integer limit) {
        return execute(okxApi.getLiquidationOrders(instType, mgnMode, instId, ccy, uly, alias, state, before, after, limit), "Get liquidation orders failed");
    }

    public OkxRestResponse<Map<String, Object>> getMarkPrice(String instType, String uly, String instId) {
        return execute(okxApi.getMarkPrice(instType, uly, instId), "Get mark price failed");
    }

    public OkxRestResponse<Map<String, Object>> getPositionTiers(String instType, String uly, String instId, String tdMode, String tier) {
        return execute(okxApi.getPositionTiers(instType, uly, instId, tdMode, tier), "Get position tiers failed");
    }

    public OkxRestResponse<Map<String, Object>> getInterestRateLoanQuota() {
        return execute(okxApi.getInterestRateLoanQuota(), "Get interest rate loan quota failed");
    }

    public OkxRestResponse<Map<String, Object>> getUnderlying(String instType) {
        return execute(okxApi.getUnderlying(instType), "Get underlying failed");
    }

    public OkxRestResponse<Map<String, Object>> getInsuranceFund(String instType, String type, String uly, String ccy, String before, String after, Integer limit) {
        return execute(okxApi.getInsuranceFund(instType, type, uly, ccy, before, after, limit), "Get insurance fund failed");
    }

    public OkxRestResponse<Map<String, Object>> convertContractCoin(String type, String instId, String sz, String px, String unit) {
        return execute(okxApi.convertContractCoin(type, instId, sz, px, unit), "Convert contract coin failed");
    }
}
