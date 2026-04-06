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

    private final List<PolymarketClobClient> clients;

    public boolean isAvailable(ExecutionWalletContextLoader.ExecutionWalletContext context) {
        PolymarketClobClient client = resolve(context);
        return client.isAvailable(context);
    }

    public Map<String, Object> deriveApiCreds(ExecutionWalletContextLoader.ExecutionWalletContext context) {
        PolymarketClobClient client = resolve(context);
        log.info("Polymarket CLOB route operation=derive-api-creds sdkMode={} client={}",
                sdkMode(context), client.getClass().getSimpleName());
        return client.deriveApiCreds(context);
    }

    public Map<String, Object> placeOrder(ExecutionWalletContextLoader.ExecutionWalletContext context,
                                          PolymarketOrderRequestFactory.PreparedPolymarketOrder order) {
        PolymarketClobClient client = resolve(context);
        log.info("Polymarket CLOB route operation=place-order sdkMode={} client={} marketId={} tokenId={} signalId={}",
                sdkMode(context), client.getClass().getSimpleName(),
                order == null ? null : order.getMarketId(),
                order == null ? null : order.getTokenId(),
                order == null ? null : order.getSignalId());
        return client.placeOrder(context, order);
    }

    public Map<String, Object> getOrder(ExecutionWalletContextLoader.ExecutionWalletContext context, String orderId) {
        PolymarketClobClient client = resolve(context);
        log.info("Polymarket CLOB route operation=get-order sdkMode={} client={} orderId={}",
                sdkMode(context), client.getClass().getSimpleName(), orderId);
        return client.getOrder(context, orderId);
    }

    public Map<String, Object> cancelOrder(ExecutionWalletContextLoader.ExecutionWalletContext context, String orderId) {
        PolymarketClobClient client = resolve(context);
        log.info("Polymarket CLOB route operation=cancel-order sdkMode={} client={} orderId={}",
                sdkMode(context), client.getClass().getSimpleName(), orderId);
        return client.cancelOrder(context, orderId);
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
