package com.metaverse.coin.strategy.web.service.smartwallet;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletSignalShadowMapper;
import com.metaverse.coin.strategy.common.dao.mapper.StrategyAlertMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletSignalShadowEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyAlertEntity;
import com.metaverse.coin.strategy.web.model.smartwallet.AuditAlertView;
import com.metaverse.coin.strategy.web.model.smartwallet.SignalShadowRecordView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Web BFF 端 W1-T4 影子记账 + 审计告警查询服务.
 *
 * 直查 DB, 不依赖 job 端任何 service. 适合前端 dashboard 直接消费.
 *
 * 详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十一节.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmartWalletAuditReadService {

    /** 审计 sink 写入 strategy_alert 时使用的固定 strategyId. */
    public static final String AUDIT_STRATEGY_ID = "SMART_WALLET_AUDIT";

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 500;

    private final SmartWalletSignalShadowMapper shadowMapper;
    private final StrategyAlertMapper strategyAlertMapper;

    // ── W1-T4 影子记账列表 ─────────────────────────────────────────

    /**
     * 列影子记账记录, 按 created_at desc 分页。
     *
     * @param leaderAddress  null/空=不过滤
     * @param decisionStatus REJECTED / PAPER_SHADOW / DOWNGRADED, null=不过滤
     * @param rejectLayer    G2b_LEADER_ROLLING_SUSPENDED 等, null=不过滤
     * @param status         PENDING_SETTLEMENT / SETTLED / EXPIRED / NO_DEPTH / DRY_FALLBACK, null=不过滤
     * @param limit          单页大小 (1-500, 默认 50)
     */
    public List<SignalShadowRecordView> listShadowRecords(String leaderAddress,
                                                          String decisionStatus,
                                                          String rejectLayer,
                                                          String status,
                                                          Integer limit) {
        int effectiveLimit = clampLimit(limit);
        LambdaQueryWrapper<SmartWalletSignalShadowEntity> q = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(leaderAddress)) {
            q.eq(SmartWalletSignalShadowEntity::getWalletAddress, leaderAddress.toLowerCase());
        }
        if (StringUtils.hasText(decisionStatus)) {
            q.eq(SmartWalletSignalShadowEntity::getDecisionStatus, decisionStatus);
        }
        if (StringUtils.hasText(rejectLayer)) {
            q.eq(SmartWalletSignalShadowEntity::getRejectLayer, rejectLayer);
        }
        if (StringUtils.hasText(status)) {
            q.eq(SmartWalletSignalShadowEntity::getStatus, status);
        }
        q.orderByDesc(SmartWalletSignalShadowEntity::getId)
         .last("limit " + effectiveLimit);

        return shadowMapper.selectList(q).stream()
                .map(SignalShadowRecordView::from)
                .collect(Collectors.toList());
    }

    /** 单条详情, id 直查. */
    public SignalShadowRecordView getShadowRecord(Long id) {
        if (id == null) return null;
        return SignalShadowRecordView.from(shadowMapper.selectById(id));
    }

    // ── 审计告警列表 ──────────────────────────────────────────────

    /**
     * 列审计告警(strategy_alert WHERE strategy_id=SMART_WALLET_AUDIT).
     *
     * @param alertType         AUDIT_LIVE_REPLAY_DIFF 等, null=不过滤
     * @param onlyUnresolved    true=仅未恢复(默认推荐)
     * @param sinceCreatedAt    时间下限, null=不过滤
     * @param limit             单页大小 (1-500, 默认 50)
     */
    public List<AuditAlertView> listAuditAlerts(String alertType,
                                                 Boolean onlyUnresolved,
                                                 Date sinceCreatedAt,
                                                 Integer limit) {
        int effectiveLimit = clampLimit(limit);
        LambdaQueryWrapper<StrategyAlertEntity> q = new LambdaQueryWrapper<>();
        q.eq(StrategyAlertEntity::getStrategyId, AUDIT_STRATEGY_ID);
        if (StringUtils.hasText(alertType)) {
            q.eq(StrategyAlertEntity::getAlertType, alertType);
        }
        if (Boolean.TRUE.equals(onlyUnresolved)) {
            q.and(w -> w.isNull(StrategyAlertEntity::getResolved)
                    .or().eq(StrategyAlertEntity::getResolved, Boolean.FALSE));
        }
        if (sinceCreatedAt != null) {
            q.ge(StrategyAlertEntity::getCreatedAt, sinceCreatedAt);
        }
        q.orderByDesc(StrategyAlertEntity::getId)
         .last("limit " + effectiveLimit);

        return strategyAlertMapper.selectList(q).stream()
                .map(AuditAlertView::from)
                .collect(Collectors.toList());
    }

    private int clampLimit(Integer requested) {
        if (requested == null || requested <= 0) return DEFAULT_PAGE_SIZE;
        return Math.min(requested, MAX_PAGE_SIZE);
    }
}
