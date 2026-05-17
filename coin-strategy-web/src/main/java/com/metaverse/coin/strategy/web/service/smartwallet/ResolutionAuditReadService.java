package com.metaverse.coin.strategy.web.service.smartwallet;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.PmResolutionAuditMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.PmResolutionAuditEntity;
import com.metaverse.coin.strategy.web.model.smartwallet.ResolutionAuditView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Web BFF 端: pm_resolution_audit 表只读查询。
 *
 * <p>支持三类入口:
 * <ul>
 *     <li>按 marketId 列出所有历史审计快照 (按 observed_at desc)</li>
 *     <li>列出最近 N 条需要人工 review (manualCheckReason 非空) 的记录</li>
 *     <li>列出最近 N 条 UMA / Polymarket 状态不一致的记录 (closed + uma 未 resolved 等)</li>
 * </ul>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十八节。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResolutionAuditReadService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;

    private final PmResolutionAuditMapper auditMapper;

    /** 按 marketId 反查全部历史审计快照。 */
    public List<ResolutionAuditView> listByMarketId(String marketId, Integer limit) {
        if (!StringUtils.hasText(marketId)) {
            return List.of();
        }
        int lim = sanitize(limit);
        List<PmResolutionAuditEntity> rows = auditMapper.selectList(
                new LambdaQueryWrapper<PmResolutionAuditEntity>()
                        .eq(PmResolutionAuditEntity::getMarketId, marketId.trim())
                        .orderByDesc(PmResolutionAuditEntity::getObservedAt)
                        .last("limit " + lim));
        return rows.stream().map(ResolutionAuditView::from).collect(Collectors.toList());
    }

    /** 列出最近 N 条 manualCheckReason 非空的记录 (人工介入入口)。 */
    public List<ResolutionAuditView> listNeedsReview(Integer limit, Date sinceObservedAt) {
        int lim = sanitize(limit);
        LambdaQueryWrapper<PmResolutionAuditEntity> q = new LambdaQueryWrapper<PmResolutionAuditEntity>()
                .isNotNull(PmResolutionAuditEntity::getManualCheckReason)
                .ne(PmResolutionAuditEntity::getManualCheckReason, "")
                .orderByDesc(PmResolutionAuditEntity::getObservedAt)
                .last("limit " + lim);
        if (sinceObservedAt != null) {
            q.ge(PmResolutionAuditEntity::getObservedAt, sinceObservedAt);
        }
        List<PmResolutionAuditEntity> rows = auditMapper.selectList(q);
        return rows.stream().map(ResolutionAuditView::from).collect(Collectors.toList());
    }

    /**
     * 列出最近 N 条 "closed=true 但 uma_resolved=false" 的可疑记录,
     * 帮助运营在 Gamma / UMA 三方 diff 未上线前提前发现"卡 settlement"市场。
     */
    public List<ResolutionAuditView> listUmaInconsistent(Integer limit) {
        int lim = sanitize(limit);
        List<PmResolutionAuditEntity> rows = auditMapper.selectList(
                new LambdaQueryWrapper<PmResolutionAuditEntity>()
                        .eq(PmResolutionAuditEntity::getClosed, Boolean.TRUE)
                        .eq(PmResolutionAuditEntity::getUmaResolved, Boolean.FALSE)
                        .orderByDesc(PmResolutionAuditEntity::getObservedAt)
                        .last("limit " + lim));
        return rows.stream().map(ResolutionAuditView::from).collect(Collectors.toList());
    }

    private int sanitize(Integer limit) {
        if (limit == null || limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }
}
