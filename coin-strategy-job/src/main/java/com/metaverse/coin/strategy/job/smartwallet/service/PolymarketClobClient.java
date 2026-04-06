package com.metaverse.coin.strategy.job.smartwallet.service;

import java.util.Map;

public interface PolymarketClobClient {

    boolean supports(ExecutionWalletContextLoader.ExecutionWalletContext context);

    boolean isAvailable(ExecutionWalletContextLoader.ExecutionWalletContext context);

    Map<String, Object> deriveApiCreds(ExecutionWalletContextLoader.ExecutionWalletContext context);

    Map<String, Object> placeOrder(ExecutionWalletContextLoader.ExecutionWalletContext context,
                                   PolymarketOrderRequestFactory.PreparedPolymarketOrder order);

    Map<String, Object> getOrder(ExecutionWalletContextLoader.ExecutionWalletContext context, String orderId);

    Map<String, Object> cancelOrder(ExecutionWalletContextLoader.ExecutionWalletContext context, String orderId);
}
