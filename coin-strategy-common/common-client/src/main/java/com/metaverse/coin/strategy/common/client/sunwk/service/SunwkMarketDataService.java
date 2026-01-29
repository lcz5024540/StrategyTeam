package com.metaverse.coin.strategy.common.client.sunwk.service;

import com.metaverse.coin.strategy.common.client.sunwk.SunwkApi;
import com.metaverse.coin.strategy.common.model.sunwk.SunwkResponse;
import com.metaverse.coin.strategy.common.model.sunwk.entity.*;

import java.util.List;

public class SunwkMarketDataService extends SunwkService {

    public SunwkMarketDataService(SunwkApi sunwkApi) {
        super(sunwkApi);
    }

    public boolean ping() {
        try {
            SunwkResponse<Void> response = sunwkApi.ping().execute().body();
            return response != null && response.isSuccess();
        } catch (Exception e) {
            return false;
        }
    }

    public Long getTimestamp() {
        return executeData(sunwkApi.getTimestamp(), "Failed to get timestamp");
    }

    public List<SunwkCurrency> getCurrencies() {
        return executeData(sunwkApi.getCurrencies(), "Failed to get currencies");
    }

    public List<SunwkSymbol> getContracts(String contractType) {
        return executeData(sunwkApi.getContracts(contractType), "Failed to get contracts");
    }

    public List<SunwkTicker> getTicker(String contractCode) {
        return executeData(sunwkApi.getTicker(contractCode), "Failed to get ticker");
    }

    public List<SunwkKline> getKlines(String contractCode, String period, Integer limit) {
        return executeData(sunwkApi.getKlines(contractCode, period, limit), "Failed to get klines");
    }

    public SunwkDepth getDepth(String contractCode, String type) {
        return executeData(sunwkApi.getDepth(contractCode, type), "Failed to get depth");
    }

    public List<SunwkTrade> getTrades(String contractCode) {
        return executeData(sunwkApi.getTrades(contractCode), "Failed to get trades");
    }

    public List<SunwkTrade> getHistoryTrades(String contractCode, Integer size) {
        return executeData(sunwkApi.getHistoryTrades(contractCode, size), "Failed to get history trades");
    }

    public List<SunwkAggTrade> getAggTrades(String contractCode, Integer limit) {
        return executeData(sunwkApi.getAggTrades(contractCode, limit), "Failed to get agg trades");
    }

    public SunwkFundingRate getFundingRate(String contractCode) {
        return executeData(sunwkApi.getFundingRate(contractCode), "Failed to get funding rate");
    }

    public SunwkMarkPrice getMarkPrice(String contractCode) {
        return executeData(sunwkApi.getMarkPrice(contractCode), "Failed to get mark price");
    }

    public SunwkOpenInterest getOpenInterest(String contractCode) {
        return executeData(sunwkApi.getOpenInterest(contractCode), "Failed to get open interest");
    }
}
