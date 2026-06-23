package com.metaverse.coin.strategy.web.model;

import java.util.List;

public record CursorPageView<T>(
        List<T> items,
        String nextCursor,
        int size,
        boolean hasMore
) {
    public static <T> CursorPageView<T> of(List<T> items, String nextCursor) {
        return new CursorPageView<>(items, nextCursor, items.size(), nextCursor != null);
    }

    public static <T> CursorPageView<T> empty() {
        return new CursorPageView<>(List.of(), null, 0, false);
    }
}
