package com.metaverse.coin.strategy.web.model.smartwallet;

import java.util.List;

public record SmartWalletOrderPnlCategoryAttributionView(
        List<WindowBucket> windows
) {
    public record WindowBucket(
            String window,
            String label,
            List<OrderPnlCategoryRow> marketCategories,
            List<OrderPnlCategoryRow> walletCategories
    ) {
    }

    public record OrderPnlCategoryRow(
            String key,
            String label,
            String walletAddress,
            String category,
            Integer profitOrderCount,
            Integer lossOrderCount,
            Integer totalOrderCount
    ) {
    }
}
