package com.metaverse.coin.strategy.web.model;

import java.util.List;

public record OffsetPageView<T>(List<T> items, int page, int pageSize, int totalItems, int totalPages) {
    public static <T> OffsetPageView<T> of(List<T> items, int page, int pageSize, int totalItems) {
        int totalPages = pageSize > 0 ? (totalItems + pageSize - 1) / pageSize : 0;
        return new OffsetPageView<>(items, page, pageSize, totalItems, totalPages);
    }
}
