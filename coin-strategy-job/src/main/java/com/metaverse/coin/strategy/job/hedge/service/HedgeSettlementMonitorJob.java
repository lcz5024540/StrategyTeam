package com.metaverse.coin.strategy.job.hedge.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.HedgePositionEntity;
import com.metaverse.coin.strategy.job.hedge.config.HedgeProperties;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

        String resolvedOutcome = meta.getResolvedOutcome(); // "Yes" or "No"
        if (!StringUtils.hasText(resolvedOutcome)) {
            return false;
        }

        log.info("[HedgeSettlement] Market resolved: positionId={} market={} outcome={}",
                position.getId(), position.getMarketId(), resolvedOutcome);

        // 标记进入结算中（如果还是 OPEN 状态）
        if ("OPEN".equals(position.getStatus())) {
            positionTracker.markSettling(position.getId(), resolvedOutcome);
        }

        // 对冲策略的收益计算：
        //   - 买入 Q 股 YES（at ask_yes）+ Q 股 NO（at ask_no）
        //   - 无论哪边赢，赢方 token 结算为 $1 / 股
        //   - claimed = Q × $1
        //   - realizedPnl = claimed - totalCost - fee ≈ Q × netEdgeRate
        BigDecimal quantity = position.getQuantity();
        BigDecimal claimed = quantity != null ? quantity : BigDecimal.ZERO; // 1 USDC per winning token
        BigDecimal estimatedFee = claimed.multiply(properties.getEstimatedFeeRate())
                .setScale(4, RoundingMode.HALF_UP);

        positionTracker.markSettled(position.getId(), claimed, estimatedFee);

        log.info("[HedgeSettlement] Position settled: id={} claimed={} USDC outcome={}",
                position.getId(), claimed, resolvedOutcome);
        return true;
    }
}
