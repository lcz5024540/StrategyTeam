package com.metaverse.coin.strategy.web.model.smartwallet;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyAlertEntity;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 审计告警列表视图(strategy_alert 表过滤 strategy_id=SMART_WALLET_AUDIT).
 *
 * 详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十一节.
 */
@Data
@Builder
public class AuditAlertView {

    private Long id;
    private String strategyId;
    private String alertType;        // AUDIT_LIVE_REPLAY_DIFF / AUDIT_SHADOW_VS_LIVE / ...
    private String alertLevel;       // INFO / WARN / CRITICAL / FATAL
    private String title;
    private String message;
    private BigDecimal metricValue;
    private BigDecimal thresholdValue;
    private Boolean acknowledged;
    private Date acknowledgedAt;
    private Boolean resolved;
    private Date resolvedAt;
    private Date createdAt;

    public static AuditAlertView from(StrategyAlertEntity e) {
        if (e == null) return null;
        return AuditAlertView.builder()
                .id(e.getId())
                .strategyId(e.getStrategyId())
                .alertType(e.getAlertType())
                .alertLevel(e.getAlertLevel())
                .title(e.getTitle())
                .message(e.getMessage())
                .metricValue(e.getMetricValue())
                .thresholdValue(e.getThresholdValue())
                .acknowledged(e.getAcknowledged())
                .acknowledgedAt(e.getAcknowledgedAt())
                .resolved(e.getResolved())
                .resolvedAt(e.getResolvedAt())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
