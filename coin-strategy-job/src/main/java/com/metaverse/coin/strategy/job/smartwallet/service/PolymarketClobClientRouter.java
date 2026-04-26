package com.metaverse.coin.strategy.job.smartwallet.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class PolymarketClobClientRouter {

    private static final String LOG_PREFIX = "[POLY_FOLLOW]";

    private final List<PolymarketClobClient> clients;

    public boolean isAvailable(ExecutionWalletContextLoader.ExecutionWalletContext context) {
        PolymarketClobClient client = resolve(context);
        return client.isAvailable(context);
    }

    public Map<String, Object> deriveApiCreds(ExecutionWalletContextLoader.ExecutionWalletContext context) {
        PolymarketClobClient client = resolve(context);
        log.info("{} action=路由CLOB派生凭证请求 result=开始 sdkMode={} client={}",
                LOG_PREFIX, sdkMode(context), client.getClass().getSimpleName());
        Map<String, Object> result = client.deriveApiCreds(context);
        log.info("{} action=路由CLOB派生凭证请求 result=完成 sdkMode={} client={} responseKeys={}",
                LOG_PREFIX, sdkMode(context), client.getClass().getSimpleName(), result == null ? null : result.keySet());
        return result;
    }

    public Map<String, Object> placeOrder(ExecutionWalletContextLoader.ExecutionWalletContext context,
                                          PolymarketOrderRequestFactory.PreparedPolymarketOrder order) {
        PolymarketClobClient client = resolve(context);
        log.info("{} action=路由CLOB下单请求 result=开始 sdkMode={} client={} marketId={} tokenId={} signalId={}",
                LOG_PREFIX, sdkMode(context), client.getClass().getSimpleName(),
                order == null ? null : order.getMarketId(),
                order == null ? null : order.getTokenId(),
                order == null ? null : order.getSignalId());
        Map<String, Object> result = client.placeOrder(context, order);
        log.info("{} action=路由CLOB下单请求 result=完成 sdkMode={} client={} marketId={} tokenId={} signalId={} responseKeys={}",
                LOG_PREFIX, sdkMode(context), client.getClass().getSimpleName(),
                order == null ? null : order.getMarketId(),
                order == null ? null : order.getTokenId(),
                order == null ? null : order.getSignalId(),
                result == null ? null : result.keySet());
        return result;
    }

    public Map<String, Object> getOrder(ExecutionWalletContextLoader.ExecutionWalletContext context, String orderId) {
        PolymarketClobClient client = resolve(context);
        log.info("{} action=路由CLOB查单请求 result=开始 sdkMode={} client={} orderId={}",
                LOG_PREFIX, sdkMode(context), client.getClass().getSimpleName(), orderId);
        Map<String, Object> result = client.getOrder(context, orderId);
        log.info("{} action=路由CLOB查单请求 result=完成 sdkMode={} client={} orderId={} responseKeys={}",
                LOG_PREFIX, sdkMode(context), client.getClass().getSimpleName(), orderId, result == null ? null : result.keySet());
        return result;
    }

    public Map<String, Object> cancelOrder(ExecutionWalletContextLoader.ExecutionWalletContext context, String orderId) {
        PolymarketClobClient client = resolve(context);
        log.info("{} action=路由CLOB撤单请求 result=开始 sdkMode={} client={} orderId={}",
                LOG_PREFIX, sdkMode(context), client.getClass().getSimpleName(), orderId);
        Map<String, Object> result = client.cancelOrder(context, orderId);
        log.info("{} action=路由CLOB撤单请求 result=完成 sdkMode={} client={} orderId={} responseKeys={}",
                LOG_PREFIX, sdkMode(context), client.getClass().getSimpleName(), orderId, result == null ? null : result.keySet());
        return result;
    }

    private PolymarketClobClient resolve(ExecutionWalletContextLoader.ExecutionWalletContext context) {
        return clients.stream()
                .filter(client -> client.supports(context))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No Polymarket CLOB client supports sdk-mode: " + context.getSdkMode()));
    }

    private String sdkMode(ExecutionWalletContextLoader.ExecutionWalletContext context) {
        return context == null || context.getSdkMode() == null ? "JAVA" : context.getSdkMode();
    }
}
