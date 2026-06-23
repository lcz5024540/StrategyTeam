package com.metaverse.coin.strategy.web.model.smartwallet;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.PmResolutionAuditEntity;
import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * W1-T2.2 + 第十一批 第四波 web 接口: 单条 resolution audit 记录的 web 视图.
 *
 * <p>对应 {@code pm_resolution_audit} 表; 由 {@code ResolutionAuditPersistenceService}
 * 在第十批落地写入。本视图为 web 端只读消费, 不直接对外暴露 entity。</p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十八节。</p>
 */
@Data
@Builder
public class ResolutionAuditView {

    private Long id;
    private String marketId;
    private Date observedAt;

    private String resolutionStatus;
    private String resolutionSource;
    private String winningOutcome;
    private String winningTokenId;
    private String manualCheckReason;

    private Boolean closed;
    private Boolean acceptingOrdersPresent;
    private Boolean acceptingOrders;

    private String umaResolutionStatus;
    private Boolean umaResolved;

    private String outcomesJson;
    private String outcomePricesJson;
    private String outcomeTokenIdsJson;

    private Date createdAt;

    /** 派生字段: 是否需要人工 review (manualCheckReason 非空)。 */
    private boolean needsReview;
    /** 派生字段: 是否最终已 resolved (resolutionStatus=RESOLVED 或 winningOutcome 非空)。 */
    private boolean resolved;

    public static ResolutionAuditView from(PmResolutionAuditEntity e) {
        if (e == null) return null;
        return ResolutionAuditView.builder()
                .id(e.getId())
                .marketId(e.getMarketId())
                .observedAt(e.getObservedAt())
                .resolutionStatus(e.getResolutionStatus())
                .resolutionSource(e.getResolutionSource())
                .winningOutcome(e.getWinningOutcome())
                .winningTokenId(e.getWinningTokenId())
                .manualCheckReason(e.getManualCheckReason())
                .closed(e.getClosed())
                .acceptingOrdersPresent(e.getAcceptingOrdersPresent())
                .acceptingOrders(e.getAcceptingOrders())
                .umaResolutionStatus(e.getUmaResolutionStatus())
                .umaResolved(e.getUmaResolved())
                .outcomesJson(e.getOutcomesJson())
                .outcomePricesJson(e.getOutcomePricesJson())
                .outcomeTokenIdsJson(e.getOutcomeTokenIdsJson())
                .createdAt(e.getCreatedAt())
                .needsReview(e.getManualCheckReason() != null && !e.getManualCheckReason().isBlank())
                .resolved("RESOLVED".equalsIgnoreCase(e.getResolutionStatus())
                        || (e.getWinningOutcome() != null && !e.getWinningOutcome().isBlank()))
                .build();
    }
}
