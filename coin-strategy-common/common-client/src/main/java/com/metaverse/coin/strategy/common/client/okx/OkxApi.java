package com.metaverse.coin.strategy.common.client.okx;

import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestResponse;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.account.Balance;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.grid.PlaceGridAlgoOrderReq;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.grid.PlaceGridAlgoOrderRsp;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.grid.StopGridAlgoOrderReq;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.grid.StopGridAlgoOrderRsp;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.order.booking.CancelOrderReq;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.order.booking.CancelOrderRsp;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.order.booking.PlaceOrderReq;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.order.booking.PlaceOrderRsp;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Query;
import retrofit2.http.QueryMap;

import java.util.List;
import java.util.Map;

public interface OkxApi {

    // --- Account ---

    @GET("/api/v5/account/balance")
    Call<OkxRestResponse<Balance>> getBalance(@Query("ccy") String ccy);

    @GET("/api/v5/account/positions")
    Call<OkxRestResponse<Map<String, Object>>> getPositions(@Query("instType") String instType, @Query("instId") String instId);

    @POST("/api/v5/account/set-leverage")
    Call<OkxRestResponse<Object>> setLeverage(@Body Map<String, Object> req);

    @GET("/api/v5/account/config")
    Call<OkxRestResponse<Map<String, Object>>> getAccountConfig();

    @GET("/api/v5/account/max-size")
    Call<OkxRestResponse<Map<String, Object>>> getMaxSize(@QueryMap Map<String, Object> queryMap);

    // --- Trade ---

    @POST("/api/v5/trade/order")
    Call<OkxRestResponse<PlaceOrderRsp>> placeOrder(@Body PlaceOrderReq req);

    @POST("/api/v5/trade/cancel-order")
    Call<OkxRestResponse<CancelOrderRsp>> cancelOrder(@Body CancelOrderReq req);

    @POST("/api/v5/trade/close-position")
    Call<OkxRestResponse<Object>> closePosition(@Body Map<String, Object> req);

    @GET("/api/v5/trade/order")
    Call<OkxRestResponse<Map<String, Object>>> getOrder(@Query("instId") String instId, @Query("ordId") String ordId, @Query("clOrdId") String clOrdId);

    @GET("/api/v5/trade/orders-history")
    Call<OkxRestResponse<Map<String, Object>>> getOrdersHistory(@QueryMap Map<String, Object> queryMap);

    @GET("/api/v5/trade/orders-pending")
    Call<OkxRestResponse<Map<String, Object>>> getOrdersPending(@QueryMap Map<String, Object> queryMap);

    // --- Algo Trading ---

    @POST("/api/v5/trade/order-algo")
    Call<OkxRestResponse<Map<String, Object>>> placeAlgoOrder(@Body Map<String, Object> req);

    @POST("/api/v5/trade/cancel-algos")
    Call<OkxRestResponse<Map<String, Object>>> cancelAlgoOrder(@Body List<Map<String, Object>> req);

    @GET("/api/v5/trade/orders-algo-history")
    Call<OkxRestResponse<Map<String, Object>>> getAlgoOrdersHistory(@QueryMap Map<String, Object> queryMap);

    // --- Grid Trading ---

    @POST("/api/v5/tradingBot/grid/order-algo")
    Call<OkxRestResponse<PlaceGridAlgoOrderRsp>> placeGridAlgoOrder(@Body PlaceGridAlgoOrderReq req);

    @POST("/api/v5/tradingBot/grid/stop-order-algo")
    Call<OkxRestResponse<StopGridAlgoOrderRsp>> stopGridAlgoOrder(@Body StopGridAlgoOrderReq req);

    @POST("/api/v5/tradingBot/grid/amend-order-algo")
    Call<OkxRestResponse<Map<String, Object>>> amendGridAlgoOrder(@Body Map<String, Object> req);

    // --- Market Data ---

    @GET("/api/v5/market/tickers")
    Call<OkxRestResponse<Map<String, Object>>> getTickers(@Query("instType") String instType, @Query("uly") String uly);

    @GET("/api/v5/market/ticker")
    Call<OkxRestResponse<Map<String, Object>>> getTicker(@Query("instId") String instId);

    @GET("/api/v5/market/books")
    Call<OkxRestResponse<Map<String, Object>>> getOrderBook(@Query("instId") String instId, @Query("sz") Integer sz);

    @GET("/api/v5/market/candles")
    Call<OkxRestResponse<List<String>>> getCandles(@Query("instId") String instId, @Query("bar") String bar, @Query("after") String after, @Query("before") String before, @Query("limit") Integer limit);

    @GET("/api/v5/market/history-candles")
    Call<OkxRestResponse<List<String>>> getHistoryCandles(@Query("instId") String instId, @Query("bar") String bar, @Query("after") String after, @Query("before") String before, @Query("limit") Integer limit);

    // --- Public Data ---

    @GET("/api/v5/public/instruments")
    Call<OkxRestResponse<Map<String, Object>>> getInstruments(@Query("instType") String instType, @Query("uly") String uly, @Query("instId") String instId);

    @GET("/api/v5/public/delivery-exercise-history")
    Call<OkxRestResponse<Map<String, Object>>> getDeliveryExerciseHistory(@Query("instType") String instType, @Query("uly") String uly, @Query("after") String after, @Query("before") String before, @Query("limit") Integer limit);

    @GET("/api/v5/public/open-interest")
    Call<OkxRestResponse<Map<String, Object>>> getOpenInterest(@Query("instType") String instType, @Query("uly") String uly, @Query("instId") String instId);

    @GET("/api/v5/public/funding-rate")
    Call<OkxRestResponse<Map<String, Object>>> getFundingRate(@Query("instId") String instId);

    @GET("/api/v5/public/funding-rate-history")
    Call<OkxRestResponse<Map<String, Object>>> getFundingRateHistory(@Query("instId") String instId, @Query("after") String after, @Query("before") String before, @Query("limit") Integer limit);

    @GET("/api/v5/public/price-limit")
    Call<OkxRestResponse<Map<String, Object>>> getPriceLimit(@Query("instId") String instId);

    @GET("/api/v5/public/opt-summary")
    Call<OkxRestResponse<Map<String, Object>>> getOptSummary(@Query("uly") String uly, @Query("expTime") String expTime);

    @GET("/api/v5/public/estimated-price")
    Call<OkxRestResponse<Map<String, Object>>> getEstimatedPrice(@Query("instId") String instId);

    @GET("/api/v5/public/discount-rate-interest-free-quota")
    Call<OkxRestResponse<Map<String, Object>>> getDiscountRateInterestFreeQuota(@Query("ccy") String ccy);

    @GET("/api/v5/public/time")
    Call<OkxRestResponse<Map<String, Object>>> getSystemTime();

    @GET("/api/v5/public/liquidation-orders")
    Call<OkxRestResponse<Map<String, Object>>> getLiquidationOrders(@Query("instType") String instType, @Query("mgnMode") String mgnMode, @Query("instId") String instId, @Query("ccy") String ccy, @Query("uly") String uly, @Query("alias") String alias, @Query("state") String state, @Query("before") String before, @Query("after") String after, @Query("limit") Integer limit);

    @GET("/api/v5/public/mark-price")
    Call<OkxRestResponse<Map<String, Object>>> getMarkPrice(@Query("instType") String instType, @Query("uly") String uly, @Query("instId") String instId);

    @GET("/api/v5/public/position-tiers")
    Call<OkxRestResponse<Map<String, Object>>> getPositionTiers(@Query("instType") String instType, @Query("uly") String uly, @Query("instId") String instId, @Query("tdMode") String tdMode, @Query("tier") String tier);

    @GET("/api/v5/public/interest-rate-loan-quota")
    Call<OkxRestResponse<Map<String, Object>>> getInterestRateLoanQuota();

    @GET("/api/v5/public/underlying")
    Call<OkxRestResponse<Map<String, Object>>> getUnderlying(@Query("instType") String instType);

    @GET("/api/v5/public/insurance-fund")
    Call<OkxRestResponse<Map<String, Object>>> getInsuranceFund(@Query("instType") String instType, @Query("type") String type, @Query("uly") String uly, @Query("ccy") String ccy, @Query("before") String before, @Query("after") String after, @Query("limit") Integer limit);

    @GET("/api/v5/public/convert-contract-coin")
    Call<OkxRestResponse<Map<String, Object>>> convertContractCoin(@Query("type") String type, @Query("instId") String instId, @Query("sz") String sz, @Query("px") String px, @Query("unit") String unit);

    // --- Funding Account ---

    @GET("/api/v5/asset/currencies")
    Call<OkxRestResponse<Map<String, Object>>> getCurrencies(@Query("ccy") String ccy);

    @GET("/api/v5/asset/balances")
    Call<OkxRestResponse<Map<String, Object>>> getAssetBalances(@Query("ccy") String ccy);

    @POST("/api/v5/asset/transfer")
    Call<OkxRestResponse<Map<String, Object>>> assetTransfer(@Body Map<String, Object> req);

    @GET("/api/v5/asset/bills")
    Call<OkxRestResponse<Map<String, Object>>> getAssetBills(@Query("ccy") String ccy, @Query("type") String type, @Query("after") String after, @Query("before") String before, @Query("limit") Integer limit);

    @GET("/api/v5/asset/deposit-lightning")
    Call<OkxRestResponse<Map<String, Object>>> getDepositLightning(@Query("ccy") String ccy, @Query("amt") String amt, @Query("to") String to);

    @GET("/api/v5/asset/deposit-address")
    Call<OkxRestResponse<Map<String, Object>>> getDepositAddress(@Query("ccy") String ccy);

    @GET("/api/v5/asset/deposit-history")
    Call<OkxRestResponse<Map<String, Object>>> getDepositHistory(@Query("ccy") String ccy, @Query("depId") String depId, @Query("txId") String txId, @Query("type") String type, @Query("state") String state, @Query("after") String after, @Query("before") String before, @Query("limit") Integer limit);

    @POST("/api/v5/asset/withdrawal")
    Call<OkxRestResponse<Map<String, Object>>> withdrawal(@Body Map<String, Object> req);

    @GET("/api/v5/asset/withdrawal-history")
    Call<OkxRestResponse<Map<String, Object>>> getWithdrawalHistory(@Query("ccy") String ccy, @Query("wdId") String wdId, @Query("clientId") String clientId, @Query("txId") String txId, @Query("type") String type, @Query("state") String state, @Query("after") String after, @Query("before") String before, @Query("limit") Integer limit);

    @POST("/api/v5/asset/cancel-withdrawal")
    Call<OkxRestResponse<Map<String, Object>>> cancelWithdrawal(@Body Map<String, Object> req);

    @POST("/api/v5/asset/convert-dust-assets")
    Call<OkxRestResponse<Map<String, Object>>> convertDustAssets(@Body Map<String, Object> req);

}
