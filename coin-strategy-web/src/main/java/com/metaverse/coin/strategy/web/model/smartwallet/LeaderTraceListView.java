package com.metaverse.coin.strategy.web.model.smartwallet;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 第十三批 第六波: 按 leader 反查全部 trace_ids 列表视图.
 * 简化版的 trace timeline, 仅列 trade_event 维度的关键信息, 不展开下游全链路;
 * 前端需要单 trace 详情时再调 /trace/{traceId}.
 *
 * 详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第二十节.
 */
@Data
@Builder
public class LeaderTraceListView {

    private String leaderAddress;
    private int lookbackDays;
    private int totalEvents;
    private int returnedEvents;
    private boolean truncated;
    private List<TraceItem> traces;

    @Data
    @Builder
    public static class TraceItem {
        private Long tradeEventId;
        private String traceId;
        private String marketId;
        private String question;
        private String outcome;
        private String side;
        private BigDecimal price;
        private BigDecimal sizeUsdc;
        private Date openedAt;
        private Boolean processed;
    }
}
