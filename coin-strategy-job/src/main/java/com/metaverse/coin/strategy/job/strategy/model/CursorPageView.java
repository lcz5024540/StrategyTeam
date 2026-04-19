package com.metaverse.coin.strategy.job.strategy.model;

import java.util.List;

/**
 * 游标分页通用响应包装。
 *
 * @param <T> 列表元素类型
 */
public record CursorPageView<T>(
        /** 本页数据 */
        List<T> items,
        /** 下一页游标（null 表示没有更多数据） */
        String nextCursor,
        /** 本页实际条数 */
        int size,
        /** 是否还有更多数据 */
        boolean hasMore
) {
    public static <T> CursorPageView<T> of(List<T> items, String nextCursor) {
        return new CursorPageView<>(items, nextCursor, items.size(), nextCursor != null);
    }

    public static <T> CursorPageView<T> empty() {
        return new CursorPageView<>(List.of(), null, 0, false);
    }
}
