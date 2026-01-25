package com.metaverse.coin.strategy.common.client.okx;

import com.metaverse.coin.strategy.common.client.okx.service.*;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestResponse;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.account.Balance;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.grid.PlaceGridAlgoOrderReq;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.grid.PlaceGridAlgoOrderRsp;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.grid.StopGridAlgoOrderReq;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.grid.StopGridAlgoOrderRsp;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.order.booking.CancelOrderReq;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.order.booking.CancelOrderRsp;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.order.booking.PlaceOrderRsp;
import com.metaverse.coin.strategy.common.model.okx.enums.ws.TdMode;
import okhttp3.OkHttpClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public class OkxApiService {

    private final OkxApi okxApi;
    private final OkHttpClient client;
    private final boolean simulated;

    public final OkxAccountService account;
    public final OkxTradeService trade;
    public final OkxAlgoTradingService algoTrading;
    public final OkxGridTradingService gridTrading;
    public final OkxMarketDataService marketData;
    public final OkxPublicDataService publicData;
    public final OkxFundingService funding;

    public OkxApiService(OkxApi okxApi, OkHttpClient client, boolean simulated) {
        this.okxApi = okxApi;
        this.client = client;
        this.simulated = simulated;

        this.account = new OkxAccountService(okxApi);
        this.trade = new OkxTradeService(okxApi);
        this.algoTrading = new OkxAlgoTradingService(okxApi);
        this.gridTrading = new OkxGridTradingService(okxApi);
        this.marketData = new OkxMarketDataService(okxApi);
        this.publicData = new OkxPublicDataService(okxApi);
        this.funding = new OkxFundingService(okxApi);
    }

    // --- Account ---

    public OkxRestResponse<Balance> getBalance(String ccy) {
        return account.getBalance(ccy);
    }

    public OkxRestResponse<Object> setLeverage(String instId, String mgnMode, String lever) {
        return account.setLeverage(instId, mgnMode, lever);
    }

    public OkxRestResponse<Map<String, Object>> getPositions(String instType, String instId) {
        return account.getPositions(instType, instId);
    }

    public OkxRestResponse<Map<String, Object>> getAccountConfig() {
        return account.getAccountConfig();
    }

    public OkxRestResponse<Map<String, Object>> getMaxSize(Map<String, Object> queryMap) {
        return account.getMaxSize(queryMap);
    }

    // --- Trade ---

    public OkxRestResponse<PlaceOrderRsp> openLong(String instId, TdMode tdMode, BigDecimal size, BigDecimal price) {
        return trade.openLong(instId, tdMode, size, price);
    }

    public OkxRestResponse<PlaceOrderRsp> openShort(String instId, TdMode tdMode, BigDecimal size, BigDecimal price) {
        return trade.openShort(instId, tdMode, size, price);
    }

    public OkxRestResponse<PlaceOrderRsp> closeLong(String instId, TdMode tdMode, BigDecimal size, BigDecimal price) {
        return trade.closeLong(instId, tdMode, size, price);
    }

    public OkxRestResponse<PlaceOrderRsp> closeShort(String instId, TdMode tdMode, BigDecimal size, BigDecimal price) {
        return trade.closeShort(instId, tdMode, size, price);
    }

    public OkxRestResponse<Object> closePosition(String instId, String mgnMode, String posSide) {
        return trade.closePosition(instId, mgnMode, posSide);
    }

    public OkxRestResponse<CancelOrderRsp> cancelOrder(CancelOrderReq req) {
        return trade.cancelOrder(req);
    }

    public OkxRestResponse<Map<String, Object>> getOrder(String instId, String ordId, String clOrdId) {
        return trade.getOrder(instId, ordId, clOrdId);
    }

    public OkxRestResponse<Map<String, Object>> getOrdersHistory(Map<String, Object> queryMap) {
        return trade.getOrdersHistory(queryMap);
    }

    public OkxRestResponse<Map<String, Object>> getOrdersPending(Map<String, Object> queryMap) {
        return trade.getOrdersPending(queryMap);
    }

    // --- Algo Trading ---

    public OkxRestResponse<Map<String, Object>> placeAlgoOrder(Map<String, Object> req) {
        return algoTrading.placeAlgoOrder(req);
    }

    public OkxRestResponse<Map<String, Object>> cancelAlgoOrder(List<Map<String, Object>> req) {
        return algoTrading.cancelAlgoOrder(req);
    }

    public OkxRestResponse<Map<String, Object>> getAlgoOrdersHistory(Map<String, Object> queryMap) {
        return algoTrading.getAlgoOrdersHistory(queryMap);
    }

    // --- Grid Trading ---

    public OkxRestResponse<PlaceGridAlgoOrderRsp> placeGridAlgoOrder(PlaceGridAlgoOrderReq req) {
        return gridTrading.placeGridAlgoOrder(req);
    }

    public OkxRestResponse<StopGridAlgoOrderRsp> stopGridAlgoOrder(StopGridAlgoOrderReq req) {
        return gridTrading.stopGridAlgoOrder(req);
    }

    public OkxRestResponse<Map<String, Object>> amendGridAlgoOrder(Map<String, Object> req) {
        return gridTrading.amendGridAlgoOrder(req);
    }

    // --- Market Data ---

    public OkxRestResponse<Map<String, Object>> getTickers(String instType, String uly) {
        return marketData.getTickers(instType, uly);
    }

    public OkxRestResponse<Map<String, Object>> getTicker(String instId) {
        return marketData.getTicker(instId);
    }

    public OkxRestResponse<Map<String, Object>> getOrderBook(String instId, Integer sz) {
        return marketData.getOrderBook(instId, sz);
    }

    public OkxRestResponse<List<String>> getCandles(String instId, String bar, String after, String before, Integer limit) {
        return marketData.getCandles(instId, bar, after, before, limit);
    }

    public OkxRestResponse<List<String>> getHistoryCandles(String instId, String bar, String after, String before, Integer limit) {
        return marketData.getHistoryCandles(instId, bar, after, before, limit);
    }

    // --- Public Data ---

    public OkxRestResponse<Map<String, Object>> getInstruments(String instType, String uly, String instId) {
        return publicData.getInstruments(instType, uly, instId);
    }

    public OkxRestResponse<Map<String, Object>> getDeliveryExerciseHistory(String instType, String uly, String after, String before, Integer limit) {
        return publicData.getDeliveryExerciseHistory(instType, uly, after, before, limit);
    }

    public OkxRestResponse<Map<String, Object>> getOpenInterest(String instType, String uly, String instId) {
        return publicData.getOpenInterest(instType, uly, instId);
    }

    public OkxRestResponse<Map<String, Object>> getFundingRate(String instId) {
        return publicData.getFundingRate(instId);
    }

    public OkxRestResponse<Map<String, Object>> getFundingRateHistory(String instId, String after, String before, Integer limit) {
        return publicData.getFundingRateHistory(instId, after, before, limit);
    }

    public OkxRestResponse<Map<String, Object>> getPriceLimit(String instId) {
        return publicData.getPriceLimit(instId);
    }

    public OkxRestResponse<Map<String, Object>> getOptSummary(String uly, String expTime) {
        return publicData.getOptSummary(uly, expTime);
    }

    public OkxRestResponse<Map<String, Object>> getEstimatedPrice(String instId) {
        return publicData.getEstimatedPrice(instId);
    }

    public OkxRestResponse<Map<String, Object>> getDiscountRateInterestFreeQuota(String ccy) {
        return publicData.getDiscountRateInterestFreeQuota(ccy);
    }

    public OkxRestResponse<Map<String, Object>> getSystemTime() {
        return publicData.getSystemTime();
    }

    public OkxRestResponse<Map<String, Object>> getLiquidationOrders(String instType, String mgnMode, String instId, String ccy, String uly, String alias, String state, String before, String after, Integer limit) {
        return publicData.getLiquidationOrders(instType, mgnMode, instId, ccy, uly, alias, state, before, after, limit);
    }

    public OkxRestResponse<Map<String, Object>> getMarkPrice(String instType, String uly, String instId) {
        return publicData.getMarkPrice(instType, uly, instId);
    }

    public OkxRestResponse<Map<String, Object>> getPositionTiers(String instType, String uly, String instId, String tdMode, String tier) {
        return publicData.getPositionTiers(instType, uly, instId, tdMode, tier);
    }

    public OkxRestResponse<Map<String, Object>> getInterestRateLoanQuota() {
        return publicData.getInterestRateLoanQuota();
    }

    public OkxRestResponse<Map<String, Object>> getUnderlying(String instType) {
        return publicData.getUnderlying(instType);
    }

    public OkxRestResponse<Map<String, Object>> getInsuranceFund(String instType, String type, String uly, String ccy, String before, String after, Integer limit) {
        return publicData.getInsuranceFund(instType, type, uly, ccy, before, after, limit);
    }

    public OkxRestResponse<Map<String, Object>> convertContractCoin(String type, String instId, String sz, String px, String unit) {
        return publicData.convertContractCoin(type, instId, sz, px, unit);
    }

    // --- Funding Account ---

    public OkxRestResponse<Map<String, Object>> getCurrencies(String ccy) {
        return funding.getCurrencies(ccy);
    }

    public OkxRestResponse<Map<String, Object>> getAssetBalances(String ccy) {
        return funding.getAssetBalances(ccy);
    }

    public OkxRestResponse<Map<String, Object>> assetTransfer(Map<String, Object> req) {
        return funding.assetTransfer(req);
    }

    public OkxRestResponse<Map<String, Object>> getAssetBills(String ccy, String type, String after, String before, Integer limit) {
        return funding.getAssetBills(ccy, type, after, before, limit);
    }

    public OkxRestResponse<Map<String, Object>> getDepositLightning(String ccy, String amt, String to) {
        return funding.getDepositLightning(ccy, amt, to);
    }

    public OkxRestResponse<Map<String, Object>> getDepositAddress(String ccy) {
        return funding.getDepositAddress(ccy);
    }

    public OkxRestResponse<Map<String, Object>> getDepositHistory(String ccy, String depId, String txId, String type, String state, String after, String before, Integer limit) {
        return funding.getDepositHistory(ccy, depId, txId, type, state, after, before, limit);
    }

    public OkxRestResponse<Map<String, Object>> withdrawal(Map<String, Object> req) {
        return funding.withdrawal(req);
    }

    public OkxRestResponse<Map<String, Object>> getWithdrawalHistory(String ccy, String wdId, String clientId, String txId, String type, String state, String after, String before, Integer limit) {
        return funding.getWithdrawalHistory(ccy, wdId, clientId, txId, type, state, after, before, limit);
    }

    public OkxRestResponse<Map<String, Object>> cancelWithdrawal(Map<String, Object> req) {
        return funding.cancelWithdrawal(req);
    }

    public OkxRestResponse<Map<String, Object>> convertDustAssets(Map<String, Object> req) {
        return funding.convertDustAssets(req);
    }
}
