package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletSignalShadowMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketSnapshotEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletSignalShadowEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalRiskDecision;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * W1-T4 影子记账录入器.
 *
 * 调用方: SmartWalletFollowTaskService 在拒绝路径 (硬 SKIPPED / PAPER_SHADOW / DOWNGRADED) 中调用
 * 一次, 将"如果当时放行"的入场模拟落表, 等待后续 ShadowSettlementBackfillJob 回填出场.
 *
 * 设计原则:
 *   - 不接 mapper 以外的重业务依赖, 失败时只 warn, 不影响主链路;
 *   - 模拟结果 status=NO_DEPTH/DRY_FALLBACK 也照样落表 (便于事后分析门控误伤);
 *   - 与 SmartWalletSignalRiskService 解耦, 调用方决定何时录.
 *
 * 详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md (W1-T4).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignalShadowRecorder {

    private final SmartWalletSignalShadowMapper shadowMapper;
    private final ShadowFillSimulationService shadowFillSimulationService;
    private final MarketSnapshotCollectorService marketSnapshotCollectorService;

    /**
     * 录一笔 shadow entry.
     *
     * @param event           源 trade_event (提供 trace_id / source_event_id / wallet)
     * @param decision        风控决策 (提供 reject_layer / reasons / scores)
     * @param decisionStatus  REJECTED / PAPER_SHADOW / DOWNGRADED
     * @param followTaskId    若已生成 follow_task, 关联 id; 否则 null
     * @param marketInfoId    关联 market_info.id; 可能 null
     * @param side            BUY / SELL
     * @param stakeUsdc       原本要下的本金 (USDC), 用于影子模拟
     * @return 入库后的 entity, 失败返回 null
     */
    public SmartWalletSignalShadowEntity record(SmartWalletTradeEventEntity event,
                                                 SignalRiskDecision decision,
                                                 String decisionStatus,
                                                 Long followTaskId,
                                                 Long marketInfoId,
                                                 String side,
                                                 BigDecimal stakeUsdc) {
        if (event == null || decision == null) {
            log.debug("shadow recorder skipped: event or decision is null");
            return null;
        }
        try {
            SmartWalletMarketSnapshotEntity snapshot = lookupNearestSnapshot(event);
            ShadowFillSimulationService.ShadowFill fill =
                    shadowFillSimulationService.simulate(side, stakeUsdc, snapshot);

            SmartWalletSignalShadowEntity row = new SmartWalletSignalShadowEntity()
                    .setTraceId(event.getTraceId())
                    .setFollowTaskId(followTaskId)
                    .setSourceEventId(event.getId())
                    .setMarketInfoId(marketInfoId)
                    .setWalletAddress(event.getWalletAddress())
                    .setDecisionStatus(decisionStatus)
                    .setRejectLayer(decision.getFirstRejectGate())
                    .setRejectReasons(joinReasons(decision.getReasons()))
                    .setSignalScore(decision.getSignalScore())
                    .setFollowProbability(decision.getFollowProbability())
                    .setSide(side)
                    .setShadowEntryPrice(fill.getEntryPrice())
                    .setShadowEntrySize(fill.getEntrySize())
                    .setShadowEntryCostUsdc(fill.getEntryCostUsdc())
                    .setShadowEntrySlippageBps(fill.getSlippageBps())
                    // 第十四批 #1: 透传 SignalRiskDecision.priceImpactBpsSource 落表,
                    // 供 ShadowVsLive 周报按 VWAP / PROXY 分桶量化估算精度差异
                    .setEntryEstimatorSource(decision.getPriceImpactBpsSource())
                    .setStatus(resolveStatus(fill))
                    .setCreatedAt(new Date())
                    .setUpdatedAt(new Date());
            shadowMapper.insert(row);
            log.info("shadow recorded id={} traceId={} eventId={} decisionStatus={} rejectLayer={} fillStatus={} entryPrice={} size={} slippageBps={}",
                    row.getId(), row.getTraceId(), row.getSourceEventId(), decisionStatus,
                    decision.getFirstRejectGate(), fill.getStatus(), fill.getEntryPrice(),
                    fill.getEntrySize(), fill.getSlippageBps());
            return row;
        } catch (RuntimeException ex) {
            log.warn("shadow recorder failed traceId={} eventId={} decisionStatus={}: {}",
                    event.getTraceId(), event.getId(), decisionStatus, ex.toString());
            return null;
        }
    }

    private SmartWalletMarketSnapshotEntity lookupNearestSnapshot(SmartWalletTradeEventEntity event) {
        if (event == null || event.getMarketId() == null || event.getOpenedAt() == null) {
            return null;
        }
        // 优先用决策时刻之前最近的快照 (避免使用未来数据导致 lookahead bias)
        return marketSnapshotCollectorService.findLatestSnapshotAtOrBefore(
                event.getMarketId(), event.getOutcome(), event.getOpenedAt());
    }

    private String joinReasons(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < reasons.size(); i++) {
            if (i > 0) sb.append(" | ");
            sb.append(reasons.get(i));
        }
        // 保护字段长度, 超长截断
        String s = sb.toString();
        return s.length() > 4000 ? s.substring(0, 4000) : s;
    }

    private String resolveStatus(ShadowFillSimulationService.ShadowFill fill) {
        if (fill == null) return "NO_DEPTH";
        switch (fill.getStatus()) {
            case "OK":              return "PENDING_SETTLEMENT";
            case "DRY_FALLBACK":    return "DRY_FALLBACK";
            case "NO_DEPTH":        return "NO_DEPTH";
            case "MISSING_SNAPSHOT":return "NO_DEPTH";
            case "INVALID_INPUT":   return "EXPIRED";
            default:                return "NO_DEPTH";
        }
    }
}
