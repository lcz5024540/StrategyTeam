package com.metaverse.coin.strategy.web.model.smartwallet;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletSignalShadowEntity;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * W1-T4 影子记账列表展示视图.
 * 与 entity 解耦, 仅暴露前端关心的字段, 避免 mybatis 内部字段(如 @TableId)直接外发.
 *
 * 详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十一节.
 */
@Data
@Builder
public class SignalShadowRecordView {

    private Long id;
    private String traceId;
    private Long followTaskId;
    private Long sourceEventId;
    private String walletAddress;
    private String decisionStatus;       // REJECTED / PAPER_SHADOW / DOWNGRADED
    private String rejectLayer;
    private String rejectReasonSummary;  // 截断到 500 字符以内
    private BigDecimal signalScore;
    private BigDecimal followProbability;
    private String side;
    private BigDecimal shadowEntryPrice;
    private BigDecimal shadowEntrySize;
    private BigDecimal shadowEntryCostUsdc;
    private Integer shadowEntrySlippageBps;
    private BigDecimal shadowExitPrice;
    private BigDecimal shadowExitProceedsUsdc;
    private BigDecimal shadowPnlUsdc;
    private Date shadowSettledAt;
    private String status;               // PENDING_SETTLEMENT / SETTLED / EXPIRED / NO_DEPTH / DRY_FALLBACK
    private Date createdAt;
    private Date updatedAt;

    public static SignalShadowRecordView from(SmartWalletSignalShadowEntity e) {
        if (e == null) return null;
        String reason = e.getRejectReasons();
        if (reason != null && reason.length() > 500) {
            reason = reason.substring(0, 500) + "...";
        }
        return SignalShadowRecordView.builder()
                .id(e.getId())
                .traceId(e.getTraceId())
                .followTaskId(e.getFollowTaskId())
                .sourceEventId(e.getSourceEventId())
                .walletAddress(e.getWalletAddress())
                .decisionStatus(e.getDecisionStatus())
                .rejectLayer(e.getRejectLayer())
                .rejectReasonSummary(reason)
                .signalScore(e.getSignalScore())
                .followProbability(e.getFollowProbability())
                .side(e.getSide())
                .shadowEntryPrice(e.getShadowEntryPrice())
                .shadowEntrySize(e.getShadowEntrySize())
                .shadowEntryCostUsdc(e.getShadowEntryCostUsdc())
                .shadowEntrySlippageBps(e.getShadowEntrySlippageBps())
                .shadowExitPrice(e.getShadowExitPrice())
                .shadowExitProceedsUsdc(e.getShadowExitProceedsUsdc())
                .shadowPnlUsdc(e.getShadowPnlUsdc())
                .shadowSettledAt(e.getShadowSettledAt())
                .status(e.getStatus())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
