package com.metaverse.coin.strategy.job.smartwallet.model;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeOrderEntity;
import lombok.Builder;
import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * 按 trace_id 反查的全链路时间线视图.
 * 来源: docs/polymarket/copy-trading-improvement-decision-2026-05-16.md (W1-T3).
 */
@Data
@Builder
public class TraceTimelineView {

    /** 请求的 trace_id, 原样回显. */
    private String traceId;

    /** 是否找到任何记录. */
    private boolean found;

    /** 链路源: trade_event. */
    private SmartWalletTradeEventEntity tradeEvent;

    /** 同一 trace_id 下的所有 follow_task (含 SKIPPED). */
    private List<SmartWalletFollowTaskEntity> followTasks;

    /** 同一 trace_id 下的所有 CLOB 单 (entry + exit). */
    private List<SmartWalletOrderEntity> orders;

    /** 同一 trace_id 下的聚合 trade_order. */
    private List<SmartWalletTradeOrderEntity> tradeOrders;

    /** 通过 origin_trace_id 反查的仓位 (仅首次开仓的仓位). */
    private List<SmartWalletPositionEntity> positions;

    /** 链路开始时间 (trade_event.opened_at). */
    private Date startedAt;

    /** 链路最新一次更新时间 (max of any entity's updated_at). */
    private Date lastTouchedAt;

    /**
     * 端到端关键耗时 (ms), 顺次:
     *   eventToFirstTask    — trade_event.created_at -> 第一个 follow_task.created_at
     *   firstTaskToFirstOrder — 第一个 follow_task.created_at -> 第一个 order.placed_at
     *   firstOrderToFilled  — 第一个 order.placed_at -> 第一个 order.filled_at
     */
    private Long eventToFirstTaskMs;
    private Long firstTaskToFirstOrderMs;
    private Long firstOrderToFilledMs;
}
