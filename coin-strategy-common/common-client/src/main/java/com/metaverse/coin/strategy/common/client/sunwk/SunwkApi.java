package com.metaverse.coin.strategy.common.client.sunwk;

import com.metaverse.coin.strategy.common.model.sunwk.SunwkResponse;
import com.metaverse.coin.strategy.common.model.sunwk.entity.*;
import retrofit2.Call;
import retrofit2.http.*;

import java.util.List;
import java.util.Map;

public interface SunwkApi {

    // --- Common / Basic Info ---
    @GET("/sapi/v1/common/ping")
    Call<SunwkResponse<Void>> ping();

    @GET("/sapi/v1/common/timestamp")
    Call<SunwkResponse<Long>> getTimestamp();

    @GET("/sapi/v1/common/currencies")
    Call<SunwkResponse<List<SunwkCurrency>>> getCurrencies();

    @GET("/sapi/v1/market/contracts")
    Call<SunwkResponse<List<SunwkSymbol>>> getContracts(@Query("contract_type") String contractType);

    // --- Account (账户类) ---
    // Assuming endpoint for balance/assets returns a list of assets
    @GET("/sapi/v1/account/assets")
    Call<SunwkResponse<List<SunwkAsset>>> getAssets(@QueryMap Map<String, Object> queryMap);

    @GET("/sapi/v1/account/balance")
    Call<SunwkResponse<SunwkBalance>> getBalance();

    @GET("/sapi/v1/account/bill_record")
    Call<SunwkResponse<List<SunwkBillRecord>>> getBillRecord(@QueryMap Map<String, Object> queryMap);

    @GET("/sapi/v1/account/fee_rate")
    Call<SunwkResponse<List<SunwkFeeRate>>> getFeeRate(@Query("symbol") String symbol);

    // --- Order (订单类) ---
    @GET("/sapi/v1/trade/order")
    Call<SunwkResponse<SunwkOrder>> getOrder(@Query("contract_code") String contractCode, @Query("order_id") String orderId, @Query("client_order_id") String clientOrderId);

    @GET("/sapi/v1/trade/order/history")
    Call<SunwkResponse<List<SunwkOrder>>> getOrderHistory(@QueryMap Map<String, Object> queryMap);

    @POST("/sapi/v1/trade/order")
    Call<SunwkResponse<SunwkOrder>> placeOrder(@Body Map<String, Object> req);

    @POST("/sapi/v1/trade/cancel_order")
    Call<SunwkResponse<SunwkOrder>> cancelOrder(@Body Map<String, Object> req);

    @GET("/sapi/v1/trade/open_orders")
    Call<SunwkResponse<List<SunwkOrder>>> getOpenOrders(@Query("contract_code") String contractCode, @Query("index") Integer index, @Query("limit") Integer limit);

    @POST("/sapi/v1/trade/cancel_all")
    Call<SunwkResponse<SunwkOrder>> cancelAllOrders(@Body Map<String, Object> req);

    @POST("/sapi/v1/trade/batch_orders")
    Call<SunwkResponse<List<SunwkOrder>>> batchPlaceOrders(@Body Map<String, Object> req);

    @POST("/sapi/v1/trade/cancel_batch_orders")
    Call<SunwkResponse<List<SunwkOrder>>> batchCancelOrders(@Body Map<String, Object> req);

    // --- Plan Order (计划委托) ---
    @POST("/sapi/v1/trade/plan_order")
    Call<SunwkResponse<SunwkPlanOrder>> placePlanOrder(@Body Map<String, Object> req);

    @POST("/sapi/v1/trade/cancel_plan_order")
    Call<SunwkResponse<SunwkPlanOrder>> cancelPlanOrder(@Body Map<String, Object> req);

    @GET("/sapi/v1/trade/plan_order")
    Call<SunwkResponse<SunwkPlanOrder>> getPlanOrder(@Query("contract_code") String contractCode, @Query("order_id") String orderId);

    @GET("/sapi/v1/trade/plan_open_orders")
    Call<SunwkResponse<List<SunwkPlanOrder>>> getPlanOpenOrders(@Query("contract_code") String contractCode, @Query("index") Integer index, @Query("limit") Integer limit);

    @GET("/sapi/v1/trade/plan_order_history")
    Call<SunwkResponse<List<SunwkPlanOrder>>> getPlanOrderHistory(@QueryMap Map<String, Object> queryMap);

    // --- Position (持仓类) ---
    @GET("/sapi/v1/trade/position/opens")
    Call<SunwkResponse<List<SunwkPosition>>> getPositions(@Query("contract_code") String contractCode, @Query("symbol") String symbol);

    @GET("/sapi/v1/trade/position/history")
    Call<SunwkResponse<List<SunwkPositionHistory>>> getPositionHistory(@QueryMap Map<String, Object> queryMap);

    @POST("/sapi/v1/trade/position/lever_rate")
    Call<SunwkResponse<Map<String, Object>>> setLeverage(@Body Map<String, Object> req);

    @POST("/sapi/v1/trade/position/margin_mode")
    Call<SunwkResponse<Map<String, Object>>> setMarginMode(@Body Map<String, Object> req);

    @POST("/sapi/v1/trade/position/transfer_margin")
    Call<SunwkResponse<Map<String, Object>>> transferMargin(@Body Map<String, Object> req);

    @GET("/sapi/v1/trade/position/limit")
    Call<SunwkResponse<Map<String, Object>>> getPositionLimit(@Query("contract_code") String contractCode);

    @GET("/sapi/v1/trade/position/risk")
    Call<SunwkResponse<List<Map<String, Object>>>> getPositionRisk(@Query("contract_code") String contractCode);

    // --- Market Data (行情数据) ---
    @GET("/sapi/v1/market/ticker")
    Call<SunwkResponse<List<SunwkTicker>>> getTicker(@Query("contract_code") String contractCode);

    @GET("/sapi/v1/market/kline")
    Call<SunwkResponse<List<SunwkKline>>> getKlines(@Query("contract_code") String contractCode, @Query("period") String period, @Query("limit") Integer limit);

    @GET("/sapi/v1/market/depth")
    Call<SunwkResponse<SunwkDepth>> getDepth(@Query("contract_code") String contractCode, @Query("type") String type);

    @GET("/sapi/v1/market/trades")
    Call<SunwkResponse<List<SunwkTrade>>> getTrades(@Query("contract_code") String contractCode);

    @GET("/sapi/v1/market/history_trades")
    Call<SunwkResponse<List<SunwkTrade>>> getHistoryTrades(@Query("contract_code") String contractCode, @Query("size") Integer size);

    @GET("/sapi/v1/market/agg_trades")
    Call<SunwkResponse<List<SunwkAggTrade>>> getAggTrades(@Query("contract_code") String contractCode, @Query("limit") Integer limit);

    @GET("/sapi/v1/market/funding_rate")
    Call<SunwkResponse<SunwkFundingRate>> getFundingRate(@Query("contract_code") String contractCode);

    @GET("/sapi/v1/market/mark_price")
    Call<SunwkResponse<SunwkMarkPrice>> getMarkPrice(@Query("contract_code") String contractCode);

    @GET("/sapi/v1/market/open_interest")
    Call<SunwkResponse<SunwkOpenInterest>> getOpenInterest(@Query("contract_code") String contractCode);

    // --- Wallet (钱包) ---
    @GET("/sapi/v1/wallet/deposit/address")
    Call<SunwkResponse<SunwkDepositAddress>> getDepositAddress(@Query("currency") String currency);

}
