package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class SmartWalletOrderPnlCategoryAttributionView {
    private List<WindowBucket> windows;

    @Data
    @Builder
    public static class WindowBucket {
        private String window;
        private String label;
        private List<OrderPnlCategoryRow> marketCategories;
        private List<OrderPnlCategoryRow> walletCategories;
    }

    @Data
    @Builder
    public static class OrderPnlCategoryRow {
        private String key;
        private String label;
        private String walletAddress;
        private String category;
        private Integer profitOrderCount;
        private Integer lossOrderCount;
        private Integer totalOrderCount;
    }
}
