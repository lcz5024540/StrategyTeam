package com.metaverse.coin.strategy.common.client.htx.rest;

import com.alibaba.fastjson2.TypeReference;
import com.metaverse.coin.strategy.common.client.htx.model.HtxDepth;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapAccount;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapAvailableLeverage;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapContractInfo;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapFundingRate;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapKline;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapLeverageResult;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOpenOrders;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrder;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrderRequest;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrderResult;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapPosition;
import com.metaverse.coin.strategy.common.client.htx.model.v5.HtxV5Balance;
import com.metaverse.coin.strategy.common.client.htx.model.v5.HtxV5Leverage;
import com.metaverse.coin.strategy.common.client.htx.model.v5.HtxV5Order;
import com.metaverse.coin.strategy.common.client.htx.model.v5.HtxV5OrderResult;
import com.metaverse.coin.strategy.common.client.htx.model.v5.HtxV5Position;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** HTX USDT-margined perpetual REST client: legacy public market data plus V5 private trading. */
public class HtxUsdtSwapService {

    private static final String MARGIN_MODE = "isolated";
    private static final String SELF_MATCH_PREVENT = "cancel_both";

    private final HtxRestClient rest;
    private final HtxUidRateLimiter rateLimiter;

    public HtxUsdtSwapService(HtxRestClient rest) {
        this(rest, new HtxUidRateLimiter());
    }

    public HtxUsdtSwapService(HtxRestClient rest, HtxUidRateLimiter rateLimiter) {
        this.rest = rest;
        this.rateLimiter = rateLimiter;
    }

    public HtxRestResponse<List<HtxSwapContractInfo>> contractInfo(String contractCode) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("contract_code", contractCode);
        params.put("business_type", "swap");
        return rest.publicGet("/linear-swap-api/v1/swap_contract_info", params,
                new TypeReference<HtxRestResponse<List<HtxSwapContractInfo>>>() { });
    }

    public HtxRestResponse<HtxDepth> depth(String contractCode, int depth) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("contract_code", contractCode);
        params.put("type", "step" + depth);
        HtxRestResponse<HtxDepth> response = rest.publicGet("/linear-swap-ex/market/depth", params,
                new TypeReference<HtxRestResponse<HtxDepth>>() { });
        if (response.getData() == null && response.getTick() != null) {
            response.setData(response.getTick());
        }
        return response;
    }

    public HtxRestResponse<HtxSwapFundingRate> fundingRate(String contractCode) {
        Map<String, String> params = singletonQuery("contract_code", contractCode);
        return rest.publicGet("/linear-swap-api/v1/swap_funding_rate", params,
                new TypeReference<HtxRestResponse<HtxSwapFundingRate>>() { });
    }

    public HtxRestResponse<List<HtxSwapKline>> historyKline(String contractCode, String period, int size) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("contract_code", contractCode);
        params.put("period", period);
        params.put("size", Integer.toString(size));
        return rest.publicGet("/linear-swap-ex/market/history/kline", params,
                new TypeReference<HtxRestResponse<List<HtxSwapKline>>>() { });
    }

    public HtxRestResponse<List<HtxSwapAccount>> accountInfo(String contractCode) {
        rateLimiter.acquire(HtxUidRateLimiter.RequestType.QUERY);
        HtxRestResponse<HtxV5Balance> response = rest.signedGet(
                "/v5/account/balance", Collections.emptyMap(),
                new TypeReference<HtxRestResponse<HtxV5Balance>>() { });
        return mapResponse(response, balance -> mapBalance(balance, contractCode));
    }

    public HtxRestResponse<List<HtxSwapPosition>> positionInfo(String contractCode) {
        rateLimiter.acquire(HtxUidRateLimiter.RequestType.QUERY);
        Map<String, String> params = singletonQuery("contract_code", contractCode);
        HtxRestResponse<List<HtxV5Position>> response = rest.signedGet(
                "/v5/trade/position/opens", params,
                new TypeReference<HtxRestResponse<List<HtxV5Position>>>() { });
        return mapResponse(response, positions -> positions == null ? Collections.emptyList()
                : positions.stream().map(HtxUsdtSwapService::mapPosition).collect(Collectors.toList()));
    }

    public HtxRestResponse<HtxSwapOpenOrders> openOrders(String contractCode) {
        rateLimiter.acquire(HtxUidRateLimiter.RequestType.QUERY);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("contract_code", contractCode);
        params.put("margin_mode", MARGIN_MODE);
        params.put("limit", "100");
        HtxRestResponse<List<HtxV5Order>> response = rest.signedGet(
                "/v5/trade/order/opens", params,
                new TypeReference<HtxRestResponse<List<HtxV5Order>>>() { });
        return mapResponse(response, orders -> {
            HtxSwapOpenOrders result = new HtxSwapOpenOrders();
            List<HtxSwapOrder> mapped = orders == null ? Collections.emptyList()
                    : orders.stream().map(HtxUsdtSwapService::mapOrder).collect(Collectors.toList());
            result.setOrders(mapped);
            result.setTotalSize(mapped.size());
            return result;
        });
    }

    public HtxRestResponse<List<HtxSwapAvailableLeverage>> availableLeverage(String contractCode) {
        return availableLeverage(contractCode, "long");
    }

    public HtxRestResponse<List<HtxSwapAvailableLeverage>> availableLeverage(
            String contractCode, String positionSide) {
        rateLimiter.acquire(HtxUidRateLimiter.RequestType.QUERY);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("contract_code", contractCode);
        params.put("margin_mode", MARGIN_MODE);
        params.put("position_side", positionSide);
        HtxRestResponse<List<HtxV5Leverage>> response = rest.signedGet(
                "/v5/position/lever", params,
                new TypeReference<HtxRestResponse<List<HtxV5Leverage>>>() { });
        return mapResponse(response, values -> values == null ? Collections.emptyList()
                : values.stream().map(HtxUsdtSwapService::mapAvailableLeverage).collect(Collectors.toList()));
    }

    public HtxRestResponse<HtxSwapLeverageResult> switchLeverage(String contractCode, int leverage) {
        return switchLeverage(contractCode, "long", leverage);
    }

    public HtxRestResponse<HtxSwapLeverageResult> switchLeverage(
            String contractCode, String positionSide, int leverage) {
        rateLimiter.acquire(HtxUidRateLimiter.RequestType.TRADE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contract_code", contractCode);
        body.put("margin_mode", MARGIN_MODE);
        body.put("position_side", positionSide);
        body.put("lever_rate", Integer.toString(leverage));
        HtxRestResponse<HtxV5Leverage> response = rest.signedPost(
                "/v5/position/lever", Collections.emptyMap(), body,
                new TypeReference<HtxRestResponse<HtxV5Leverage>>() { });
        return mapResponse(response, HtxUsdtSwapService::mapLeverageResult);
    }

    /** Compatibility facade for callers still expressing V1 direction/offset semantics. */
    public HtxRestResponse<HtxSwapOrderResult> placeOrder(String contractCode,
                                                           long clientOrderId,
                                                           long volume,
                                                           String direction,
                                                           String offset,
                                                           int leverage,
                                                           String price,
                                                           String orderPriceType) {
        boolean close = "close".equalsIgnoreCase(offset);
        String positionSide = close
                ? ("sell".equalsIgnoreCase(direction) ? "long" : "short")
                : ("buy".equalsIgnoreCase(direction) ? "long" : "short");
        HtxSwapOrderRequest request = orderRequest(contractCode, clientOrderId, volume,
                direction, positionSide, close, price, orderPriceType);
        return placeOrder(request);
    }

    public HtxRestResponse<HtxSwapOrderResult> placeOrder(HtxSwapOrderRequest request) {
        rateLimiter.acquire(HtxUidRateLimiter.RequestType.TRADE);
        Map<String, Object> body = singleOrderBody(request);
        HtxRestResponse<HtxV5OrderResult> response = rest.signedPost(
                "/v5/trade/order", Collections.emptyMap(), body,
                new TypeReference<HtxRestResponse<HtxV5OrderResult>>() { });
        return mapResponse(response, HtxUsdtSwapService::mapOrderResult);
    }

    public HtxRestResponse<List<HtxSwapOrderResult>> batchOrders(List<HtxSwapOrderRequest> requests) {
        rateLimiter.acquire(HtxUidRateLimiter.RequestType.TRADE);
        List<Map<String, Object>> body = requests.stream()
                .map(HtxUsdtSwapService::singleOrderBody)
                .collect(Collectors.toList());
        HtxRestResponse<List<HtxV5OrderResult>> response = rest.signedPost(
                "/v5/trade/batch_orders", Collections.emptyMap(), body,
                new TypeReference<HtxRestResponse<List<HtxV5OrderResult>>>() { });
        return mapResponse(response, values -> values == null ? Collections.emptyList()
                : values.stream().map(HtxUsdtSwapService::mapOrderResult).collect(Collectors.toList()));
    }

    public HtxRestResponse<Map<String, Object>> cancelOrder(String contractCode, long orderId) {
        rateLimiter.acquire(HtxUidRateLimiter.RequestType.TRADE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contract_code", contractCode);
        body.put("order_id", Long.toString(orderId));
        return rest.signedPost("/v5/trade/cancel_order", Collections.emptyMap(), body,
                new TypeReference<HtxRestResponse<Map<String, Object>>>() { });
    }

    public HtxRestResponse<List<HtxSwapOrder>> queryOrder(String contractCode, long orderId) {
        rateLimiter.acquire(HtxUidRateLimiter.RequestType.QUERY);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("contract_code", contractCode);
        params.put("margin_mode", MARGIN_MODE);
        params.put("order_id", Long.toString(orderId));
        HtxRestResponse<HtxV5Order> response = rest.signedGet(
                "/v5/trade/order", params,
                new TypeReference<HtxRestResponse<HtxV5Order>>() { });
        return mapResponse(response, order -> order == null
                ? Collections.emptyList() : List.of(mapOrder(order)));
    }

    public HtxRestResponse<List<HtxSwapOrder>> queryOrderByClientOrderId(String contractCode,
                                                                         String clientOrderId) {
        rateLimiter.acquire(HtxUidRateLimiter.RequestType.QUERY);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("contract_code", contractCode);
        params.put("margin_mode", MARGIN_MODE);
        params.put("client_order_id", clientOrderId);
        HtxRestResponse<HtxV5Order> response = rest.signedGet(
                "/v5/trade/order", params,
                new TypeReference<HtxRestResponse<HtxV5Order>>() { });
        return mapResponse(response, order -> order == null
                ? Collections.emptyList() : List.of(mapOrder(order)));
    }

    public static HtxSwapOrderRequest orderRequest(String contractCode, long clientOrderId, long volume,
                                                    String side, String positionSide, boolean reduceOnly,
                                                    String price, String type) {
        return HtxSwapOrderRequest.builder()
                .contractCode(contractCode)
                .marginMode(MARGIN_MODE)
                .positionSide(positionSide)
                .side(side)
                .type(type)
                .clientOrderId(Long.toString(clientOrderId))
                .price(price)
                .volume(Long.toString(volume))
                .reduceOnly(reduceOnly)
                .timeInForce("gtc")
                .selfMatchPrevent(SELF_MATCH_PREVENT)
                .build();
    }

    private static Map<String, Object> singleOrderBody(HtxSwapOrderRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contract_code", request.getContractCode());
        body.put("margin_mode", request.getMarginMode());
        body.put("position_side", request.getPositionSide());
        body.put("side", request.getSide());
        body.put("type", request.getType());
        body.put("client_order_id", request.getClientOrderId());
        body.put("price", request.getPrice());
        body.put("volume", request.getVolume());
        body.put("reduce_only", Boolean.TRUE.equals(request.getReduceOnly()) ? 1 : 0);
        body.put("time_in_force", request.getTimeInForce());
        body.put("self_match_prevent", request.getSelfMatchPrevent());
        return body;
    }

    private static List<HtxSwapAccount> mapBalance(HtxV5Balance balance, String contractCode) {
        if (balance == null) {
            return Collections.emptyList();
        }
        String quoteCurrency = quoteCurrency(contractCode);
        HtxV5Balance.Detail detail = balance.getDetails() == null ? null : balance.getDetails().stream()
                .filter(item -> quoteCurrency.equalsIgnoreCase(item.getCurrency()))
                .findFirst().orElse(null);
        if (detail == null) {
            return Collections.emptyList();
        }
        HtxSwapAccount account = new HtxSwapAccount();
        account.setContractCode(contractCode);
        account.setCurrency(detail.getCurrency());
        account.setState(balance.getState());
        BigDecimal available = detail.getAvailable() != null ? detail.getAvailable() : detail.getAvailableMargin();
        account.setMarginAvailable(available);
        return List.of(account);
    }

    private static HtxSwapPosition mapPosition(HtxV5Position source) {
        HtxSwapPosition target = new HtxSwapPosition();
        target.setContractCode(source.getContractCode());
        target.setPositionSide(source.getPositionSide());
        target.setDirection(source.getDirection());
        target.setMarginMode(source.getMarginMode());
        target.setVolume(source.getVolume());
        target.setAvailable(source.getAvailable());
        target.setOpenCost(source.getOpenAveragePrice());
        target.setLiquidationPrice(source.getLiquidationPrice());
        target.setLastPrice(source.getLastPrice());
        target.setMarkPrice(source.getMarkPrice());
        target.setLeverage(source.getLeverage());
        target.setUnrealizedProfit(source.getUnrealizedProfit());
        return target;
    }

    private static HtxSwapAvailableLeverage mapAvailableLeverage(HtxV5Leverage source) {
        HtxSwapAvailableLeverage target = new HtxSwapAvailableLeverage();
        target.setContractCode(source.getContractCode());
        target.setMarginMode(source.getMarginMode());
        target.setPositionSide(source.getPositionSide());
        String available = source.getAvailableLeverage() == null ? ""
                : source.getAvailableLeverage().stream().map(String::valueOf).collect(Collectors.joining(","));
        target.setAvailableLevelRate(available);
        return target;
    }

    private static HtxSwapLeverageResult mapLeverageResult(HtxV5Leverage source) {
        HtxSwapLeverageResult target = new HtxSwapLeverageResult();
        target.setContractCode(source.getContractCode());
        target.setMarginMode(source.getMarginMode());
        target.setPositionSide(source.getPositionSide());
        target.setLeverage(source.getLeverage());
        return target;
    }

    private static HtxSwapOrderResult mapOrderResult(HtxV5OrderResult source) {
        HtxSwapOrderResult target = new HtxSwapOrderResult();
        target.setCode(source.getCode());
        target.setMessage(source.getMessage());
        target.setOrderId(source.getOrderId());
        target.setClientOrderId(source.getClientOrderId());
        return target;
    }

    private static HtxSwapOrder mapOrder(HtxV5Order source) {
        HtxSwapOrder target = new HtxSwapOrder();
        target.setOrderId(source.getOrderId());
        target.setClientOrderId(source.getClientOrderId());
        target.setContractCode(source.getContractCode());
        target.setLeverage(source.getLeverage());
        target.setSide(source.getSide());
        target.setDirection(source.getSide());
        target.setPositionSide(source.getPositionSide());
        target.setMarginMode(source.getMarginMode());
        target.setReduceOnly(source.getReduceOnly());
        target.setVolume(source.getVolume());
        target.setTradeVolume(source.getTradeVolume());
        target.setTradeAveragePrice(source.getTradeAveragePrice());
        target.setState(source.getState());
        target.setStatus(legacyStatus(source.getState()));
        target.setFee(source.getFee());
        target.setFeeCurrency(source.getFeeCurrency());
        target.setProfit(source.getProfit());
        target.setRealProfit(source.getRealProfit());
        return target;
    }

    private static Integer legacyStatus(String state) {
        if (state == null) {
            return null;
        }
        switch (state.toLowerCase(Locale.ROOT)) {
            case "new":
                return 3;
            case "partially_filled":
                return 4;
            case "filled":
                return 6;
            case "partially_canceled":
            case "canceled":
                return 5;
            case "rejected":
                return 7;
            default:
                return null;
        }
    }

    private static String quoteCurrency(String contractCode) {
        int separator = contractCode == null ? -1 : contractCode.lastIndexOf('-');
        return separator < 0 ? "USDT" : contractCode.substring(separator + 1).toUpperCase(Locale.ROOT);
    }

    private static Map<String, String> singletonQuery(String key, String value) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put(key, value);
        return query;
    }

    private static <S, T> HtxRestResponse<T> mapResponse(
            HtxRestResponse<S> source, Function<S, T> mapper) {
        if (source == null) {
            return HtxRestResponse.<T>builder().status("error").errMsg("null response").build();
        }
        T data = source.getData() == null ? null : mapper.apply(source.getData());
        return HtxRestResponse.<T>builder()
                .code(source.getCode())
                .message(source.getMessage())
                .status(source.getStatus())
                .ch(source.getCh())
                .ts(source.getTs())
                .data(data)
                .errCode(source.getErrCode())
                .errMsg(source.getErrMsg())
                .build();
    }
}
