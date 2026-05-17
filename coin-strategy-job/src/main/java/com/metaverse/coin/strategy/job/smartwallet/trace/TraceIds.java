package com.metaverse.coin.strategy.job.smartwallet.trace;

/**
 * 已迁移到 {@link com.metaverse.coin.strategy.common.service.trace.TraceIds}.
 *
 * 迁移原因: Tier A 通用化 (2026-05-16) — trace_id 是任何策略都需要的基础设施,
 * 不应该绑死在 smartwallet 包下.
 *
 * 本类是 deprecation shim, 仅静态委托到新位置, 不要新增代码到本类.
 * 所有新代码请直接 import 新位置. 既有导入应一并迁移.
 *
 * 详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十二节 Tier A.
 *
 * @deprecated 请改用 {@link com.metaverse.coin.strategy.common.service.trace.TraceIds}
 */
@Deprecated
public final class TraceIds {

    public static final String TRADE_EVENT_PREFIX =
            com.metaverse.coin.strategy.common.service.trace.TraceIds.TRADE_EVENT_PREFIX;
    public static final int TRADE_EVENT_TRACE_LENGTH =
            com.metaverse.coin.strategy.common.service.trace.TraceIds.TRADE_EVENT_TRACE_LENGTH;

    private TraceIds() {
    }

    public static String fromTradeEventId(Long tradeEventId) {
        return com.metaverse.coin.strategy.common.service.trace.TraceIds.fromTradeEventId(tradeEventId);
    }

    public static Long extractTradeEventId(String traceId) {
        return com.metaverse.coin.strategy.common.service.trace.TraceIds.extractTradeEventId(traceId);
    }

    public static boolean isValid(String traceId) {
        return com.metaverse.coin.strategy.common.service.trace.TraceIds.isValid(traceId);
    }
}
