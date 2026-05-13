package com.metaverse.coin.strategy.common.service.smartwallet.model;

import java.util.List;

/**
 * 基于页码的分页响应。
 *
 * <p>原 job 端 {@code com.metaverse.coin.strategy.job.strategy.model.OffsetPageView}
 * 和 web 端 {@code com.metaverse.coin.strategy.web.model.OffsetPageView} 两份重复定义，
 * Phase 3 下沉为 common-service 统一来源。</p>
 *
 * @param items      当前页数据
 * @param page       当前页（从 1 开始）
 * @param pageSize   每页条数
 * @param totalItems 总条数
 * @param totalPages 总页数
 */
public record OffsetPageView<T>(
        List<T> items,
        int page,
        int pageSize,
        int totalItems,
        int totalPages
) {
    public static <T> OffsetPageView<T> of(List<T> items, int page, int pageSize, int totalItems) {
        int safePageSize = Math.max(pageSize, 1);
        int safeTotalItems = Math.max(totalItems, 0);
        int totalPages = Math.max(1, (int) Math.ceil((double) safeTotalItems / safePageSize));
        int safePage = Math.min(Math.max(page, 1), totalPages);
        return new OffsetPageView<>(items, safePage, safePageSize, safeTotalItems, totalPages);
    }
}
