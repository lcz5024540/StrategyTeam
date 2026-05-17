package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 第十二批 #4 — 拉取 Polymarket Gamma + UMA "当前实时" 状态, 与 audit 表中的"观测时刻"
 * 状态对比, 强化 {@link ResolutionTriDiffService} 三方 diff 的诊断能力.
 *
 * <p><b>核心动作</b>: 给定 marketId 列表 (通常是 ResolutionTriDiff 报表 top conflictedMarkets),
 * 对每个 marketId:
 * <ol>
 *   <li>调 {@link PolymarketApiClient#fetchMarketMetadata(String)} 拿当前 Gamma+UMA 状态</li>
 *   <li>构造 {@link MarketResolutionResolver.Request} 走 {@link
 *       MarketResolutionResolver#resolveWithAudit} 重新解析</li>
 *   <li>把 {@link ResolutionAuditRecord} 通过 {@link ResolutionAuditPersistenceService} 写新
 *       一条 audit 记录 (历史不一致变化自然被 ResolutionTriDiffService 下次扫描发现)</li>
 * </ol>
 *
 * <p><b>限频</b>: 每次最多刷新 maxMarketsPerRun (默认 20) 个市场, 串行调用; Gamma metadata
 * cooling-down 时跳过, 避免压垮上游.</p>
 *
 * <p><b>对开单效率 / 资金使用率 / 是否会开单 / 盈亏点 / 胜率均无任何直接影响</b> — 纯只读
 * + 写 audit 自表, 不触碰任何业务表; 失败仅 warn.</p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十九节.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResolutionLiveRefreshService {

    private static final String LOG_PREFIX = "[ResolutionLiveRefresh]";

    private final SmartWalletProperties properties;
    private final PolymarketApiClient polymarketApiClient;
    private final MarketResolutionResolver resolutionResolver;
    private final ResolutionAuditPersistenceService auditPersistenceService;

    /**
     * 对给定 marketId 集合刷新 live 状态, 写新 audit 记录.
     *
     * @return 各 marketId 的处理状态 (REFRESHED / SKIPPED_COOLING / SKIPPED_NULL / ERROR)
     */
    public Map<String, String> refresh(Collection<String> marketIds) {
        if (!isEnabled()) {
            return Map.of();
        }
        if (marketIds == null || marketIds.isEmpty()) {
            return Map.of();
        }
        int cap = effectiveMaxPerRun();
        List<String> bounded = marketIds.stream()
                .filter(Objects::nonNull).distinct().limit(cap).toList();
        Map<String, String> outcomes = new LinkedHashMap<>();
        for (String marketId : bounded) {
            outcomes.put(marketId, refreshOne(marketId));
        }
        long refreshed = outcomes.values().stream().filter("REFRESHED"::equals).count();
        log.info("{} attempted={} refreshed={} statuses={}",
                LOG_PREFIX, outcomes.size(), refreshed, outcomes);
        return outcomes;
    }

    private String refreshOne(String marketId) {
        try {
            if (polymarketApiClient.isMetadataEndpointCoolingDown()) {
                return "SKIPPED_COOLING";
            }
            PolymarketApiClient.MarketMetadata md = polymarketApiClient.fetchMarketMetadata(marketId);
            if (md == null) {
                return "SKIPPED_NULL";
            }
            MarketResolutionResolver.Request req = MarketResolutionResolver.Request.builder()
                    .closed(md.isClosed())
                    .acceptingOrders(md.isAcceptingOrders())
                    .acceptingOrdersPresent(true)
                    .umaResolutionStatus(md.getUmaResolutionStatus())
                    // F-P1-12: 透传 UMA 争议标记
                    .umaDisputed(md.isUmaDisputed())
                    .outcomes(md.getOutcomes())
                    .outcomePrices(md.getOutcomePrices())
                    .build();
            MarketResolutionResolver.ResolutionWithAudit pair = resolutionResolver.resolveWithAudit(marketId, req);
            if (pair == null || pair.audit() == null) {
                return "ERROR";
            }
            auditPersistenceService.persist(pair.audit());
            return "REFRESHED";
        } catch (RuntimeException ex) {
            log.warn("{} marketId={} refresh failed reason={}", LOG_PREFIX, marketId, ex.toString());
            return "ERROR";
        }
    }

    private boolean isEnabled() {
        SmartWalletProperties.Audit audit = properties.getAudit();
        return audit != null && audit.isResolutionTriDiffLiveRefreshEnabled();
    }

    private int effectiveMaxPerRun() {
        SmartWalletProperties.Audit audit = properties.getAudit();
        if (audit == null || audit.getResolutionTriDiffLiveRefreshMaxPerRun() == null) return 20;
        return Math.max(1, audit.getResolutionTriDiffLiveRefreshMaxPerRun());
    }
}
