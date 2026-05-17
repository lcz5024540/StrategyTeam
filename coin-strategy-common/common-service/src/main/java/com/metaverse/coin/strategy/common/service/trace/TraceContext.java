package com.metaverse.coin.strategy.common.service.trace;

import org.slf4j.MDC;

/**
 * 把 trace_id 写入 SLF4J MDC, 让 logback pattern %X{traceId} 可以自动附带.
 *
 * 推荐使用方式 (try-with-resources):
 *
 * <pre>
 * try (TraceContext.Scope ignored = TraceContext.scope(traceId)) {
 *     // ... 业务调用, 所有 log 自动带 traceId
 * }
 * </pre>
 *
 * 注意:
 *   - 必须 try-with-resources, 防止线程返还到线程池时 MDC 残留;
 *   - 同一线程内嵌套 scope 时, 外层值会在内层 close 后被恢复;
 *   - 异步 (CompletableFuture / @Async) 场景需要显式把 trace_id 传递过去.
 *
 * 通用化说明 (Tier A 抽取, 2026-05-16):
 *   - 原位置 coin-strategy-job/.../smartwallet/trace/, 已上移至 common-service
 *   - 决议文档 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十二节
 */
public final class TraceContext {

    public static final String MDC_KEY = "traceId";

    private TraceContext() {
    }

    /** 当前线程 MDC 中的 trace_id, 可能为 null. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    /**
     * 开一个 trace 作用域. 若 traceId 为 null/blank, 不写 MDC, scope.close() 也不做事 —
     * 这样调用方可以无脑包裹, 不需要先判空.
     */
    public static Scope scope(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return Scope.NOOP;
        }
        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, traceId);
        return new Scope(previous, false);
    }

    public static final class Scope implements AutoCloseable {

        static final Scope NOOP = new Scope(null, true);

        private final String previous;
        private final boolean noop;

        private Scope(String previous, boolean noop) {
            this.previous = previous;
            this.noop = noop;
        }

        @Override
        public void close() {
            if (noop) {
                return;
            }
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        }
    }
}
