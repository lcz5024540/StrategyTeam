package com.metaverse.coin.strategy.job.hedge.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.HedgePositionEntity;
import com.metaverse.coin.strategy.job.hedge.config.HedgeProperties;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 对冲持仓结算监控任务
 *
 * 每 {@code polymarket.hedge.settlement-fixed-delay-ms} 毫秒（默认 30 秒）轮询一次：
 *   1. 查询 OPEN / SETTLING 状态持仓
 *   2. 通过 PolymarketApiClient.fetchMarketMetadata() 检查市场是否 resolved
 *   3. 已解决 → 标记 SETTLING → 计算最终 PnL → 标记 SETTLED
 *
 * 实际 claim（调用智能合约 redeemPositions）可通过跟单策略的 Claim 模块人工触发，
 * 或在后续版本中扩展 HedgeClaimService 自动执行。
 * 本任务仅负责状态流转和 PnL 记录，不直接操作链上资产。
 *
 * 数据完全隔离：只操作 hedge_position 和 hedge_order 表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HedgeSettlementMonitorJob {

    private final HedgeProperties properties;
    private final HedgePositionTracker positionTracker;
    private final PolymarketApiClient apiClient;

    @Scheduled(fixedDelayString = "${polymarket.hedge.settlement-fixed-delay-ms:30000}")
    public void checkSettlements() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            doCheck();
        } catch (Exception e) {
            log.error("[HedgeSettlement] Unexpected error: {}", e.getMessage(), e);
        }
    }

    private void doCheck() {
        List<HedgePositionEntity> pending = positionTracker
                .getSettlementPendingPositions(properties.getSettlementBatchSize());

        if (pending.isEmpty()) {
            return;
        }

        int settled = 0;
        for (HedgePositionEntity position : pending) {
            try {
                if (checkAndSettle(position)) {
                    settled++;
                }
            } catch (Exception e) {
                log.warn("[HedgeSettlement] Error checking position id={} market={}: {}",
                        position.getId(), position.getMarketId(), e.getMessage());
            }
        }

        if (settled > 0) {
            log.info("[HedgeSettlement] Settled {} positions this run", settled);
        }
    }

    private boolean checkAndSettle(HedgePositionEntity position) {
        // 使用 conditionId = marketId 查询市场元数据
        PolymarketApiClient.MarketMetadata meta = apiClient.fetchMarketMetadata(position.getMarketId());
        if (meta == null) {
            return false;
        }

        if (!meta.isClosed() || !meta.isResolved()) {
            // 超时告警：市场已过期 24h 仍未结算
            if (position.getMarketEndAt() != null
                    && position.getMarketEndAt() > 0
                    && System.currentTimeMillis() > position.getMarketEndAt() + 24L * 60 * 60 * 1000) {
                log.warn("[HedgeSettlement] Position id={} market={} expired 24h+ ago but still unresolved",
                        position.getId(), position.getMarketId());
            }
            return false;
        }

        String resolvedOutcome = meta.getResolvedOutcome(); // "Yes" or "No"（已由 PolymarketApiClient 标准化为大写）
        if (!StringUtils.hasText(resolvedOutcome)) {
            return false;
        }

        log.info("[HedgeSettlement] Market resolved: positionId={} market={} outcome={}",
                position.getId(), position.getMarketId(), resolvedOutcome);

        // 仅将持仓推进到 SETTLING 状态，记录结果方向
        // 实际链上 Claim（redeemPositions）和最终 SETTLED 标记由 HedgeClaimService 负责
        if ("OPEN".equals(position.getStatus())) {
            positionTracker.markSettling(position.getId(), resolvedOutcome);
        }
        return true;
    }
}
