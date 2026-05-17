package com.metaverse.coin.strategy.common.service.trace;

/**
 * 跟单链路 trace_id 生成与解析工具.
 *
 * 设计:
 *   trace_id = "tev_" + format(trade_event.id, "%012d")
 *   例如: trade_event.id = 123456 -> trace_id = "tev_000000123456"
 *
 * 语义保证:
 *   - 1 个 trade_event = 1 个 trace_id (1:1)
 *   - follow_task / order / trade_order / position 通过 sourceEventId 反查
 *   - 长度恒定 16 字符, 便于日志 / 索引 / 跨表 join
 *
 * 通用化说明 (Tier A 抽取, 2026-05-16):
 *   - 原位置 coin-strategy-job/.../smartwallet/trace/, 已上移至 common-service
 *   - 后续天气 / BTC 5min 等策略均可直接复用 (trace_id 命名空间通过新增 PREFIX 区分)
 *   - 决议文档 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十二节
 */
public final class TraceIds {

    /** trace_id 前缀, 用于区分 (未来可能加入其它源, 比如 weather/btc 自有事件). */
    public static final String TRADE_EVENT_PREFIX = "tev_";

    /** trade_event 派生 trace_id 的固定长度. */
    public static final int TRADE_EVENT_TRACE_LENGTH = 16;

    private TraceIds() {
    }

    /**
     * 由 trade_event.id 生成 trace_id.
     *
     * @param tradeEventId 自增主键, 非空且 >= 0
     * @return "tev_%012d" 形式的 trace_id
     */
    public static String fromTradeEventId(Long tradeEventId) {
        if (tradeEventId == null || tradeEventId < 0L) {
            throw new IllegalArgumentException("tradeEventId must be non-null and non-negative, got " + tradeEventId);
        }
        return TRADE_EVENT_PREFIX + String.format("%012d", tradeEventId);
    }

    /**
     * 反解 trace_id, 若是合法的 trade_event 派生格式, 返回对应 id, 否则返回 null.
     */
    public static Long extractTradeEventId(String traceId) {
        if (traceId == null || traceId.length() != TRADE_EVENT_TRACE_LENGTH) return null;
        if (!traceId.startsWith(TRADE_EVENT_PREFIX)) return null;
        try {
            return Long.parseLong(traceId.substring(TRADE_EVENT_PREFIX.length()));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 简单合法性检查, 用于参数校验. */
    public static boolean isValid(String traceId) {
        return extractTradeEventId(traceId) != null;
    }
}
