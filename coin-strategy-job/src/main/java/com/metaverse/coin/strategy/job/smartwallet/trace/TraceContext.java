package com.metaverse.coin.strategy.job.smartwallet.trace;

/**
 * 已迁移到 {@link com.metaverse.coin.strategy.common.service.trace.TraceContext}.
 *
 * 迁移原因: Tier A 通用化 (2026-05-16) — MDC trace 注入是任何策略的基础设施.
 *
 * 本类是 deprecation shim, 委托到新位置. 所有新代码请直接 import 新位置.
 *
 * 详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十二节 Tier A.
 *
 * @deprecated 请改用 {@link com.metaverse.coin.strategy.common.service.trace.TraceContext}
 */
@Deprecated
public final class TraceContext {

    public static final String MDC_KEY =
            com.metaverse.coin.strategy.common.service.trace.TraceContext.MDC_KEY;

    private TraceContext() {
    }

    public static String current() {
        return com.metaverse.coin.strategy.common.service.trace.TraceContext.current();
    }

    public static com.metaverse.coin.strategy.common.service.trace.TraceContext.Scope scope(String traceId) {
        return com.metaverse.coin.strategy.common.service.trace.TraceContext.scope(traceId);
    }
}
