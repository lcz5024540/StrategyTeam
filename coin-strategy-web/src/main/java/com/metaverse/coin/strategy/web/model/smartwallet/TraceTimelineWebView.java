package com.metaverse.coin.strategy.web.model.smartwallet;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletSignalShadowEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeOrderEntity;
import lombok.Builder;
import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * Web BFF 端按 trace_id 反查的全链路时间线视图。
 * 与 job 端 TraceTimelineView 字段对齐,新增 shadow 部分。
 *
 * 详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十一节。
 */
@Data
@Builder
public class TraceTimelineWebView {

    private String traceId;
    private boolean found;

    private SmartWalletTradeEventEntity tradeEvent;
    private List<SmartWalletFollowTaskEntity> followTasks;
    private List<SmartWalletOrderEntity> orders;
    private List<SmartWalletTradeOrderEntity> tradeOrders;
    private List<SmartWalletPositionEntity> positions;
    /** W1-T4 影子记账记录(若该 trace 被风控拒/降级,可在此查到模拟数据). */
    private List<SmartWalletSignalShadowEntity> shadowRecords;

    private Date startedAt;
    private Date lastTouchedAt;

    private Long eventToFirstTaskMs;
    private Long firstTaskToFirstOrderMs;
    private Long firstOrderToFilledMs;
}
