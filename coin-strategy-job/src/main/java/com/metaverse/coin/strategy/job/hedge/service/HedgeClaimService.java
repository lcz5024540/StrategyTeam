package com.metaverse.coin.strategy.job.hedge.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.HedgeOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.HedgePositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.job.hedge.config.HedgeProperties;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletOnChainClaimGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 对冲持仓链上 Claim 服务
 *
 * 职责：对处于 SETTLING 状态的对冲持仓，调用 Polymarket 链上 redeemPositions，
 * 将赢方 token（YES 或 NO）兑换为 USDC，并最终将持仓状态推进到 SETTLED。
 *
 * 设计说明：
 *   - 复用跟单策略的 SmartWalletOnChainClaimGateway（链上交互逻辑不重复实现）
 *   - 通过构造最小化的 SmartWalletPositionEntity 传入 gateway，仅使用 marketId 和 quantity 字段
 *   - PnL 计算：realizedPnl = claimedUsdc - totalCostUsdc - estimatedFee
 *     - claimedUsdc = 赢方 token 实际成交量 × $1（二元市场赢方 = $1/token）
 *     - totalCostUsdc 经 HedgeOrderPollingJob 同步为实际成交成本
 *
 * 与结算监控的分工：
 *   - HedgeSettlementMonitorJob：检测市场是否 Resolved → 标记 SETTLING
 *   - HedgeClaimService（本类）：SETTLING → 链上 Claim → SETTLED
 *
 * Claim 模式由 smart-wallet.claim.live-gateway-mode 配置（与跟单策略共享）：
 *   - MANUAL_CONFIRM：不自动提交，等待人工确认
 *   - SIMULATED_SUCCESS / SIMULATED_SUBMITTED：测试用
 *   - RELAYER：通过 Polymarket Relayer 提交（生产推荐）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HedgeClaimService {

    private final HedgeProperties hedgeProperties;
    private final HedgePositionTracker positionTracker;
    private final SmartWalletOnChainClaimGateway onChainClaimGateway;

    @Scheduled(fixedDelayString = "${polymarket.hedge.claim-fixed-delay-ms:60000}")
    public void processPendingClaims() {
        if (!hedgeProperties.isEnabled()) {
            return;
        }
        try {
            doProcessClaims();
        } catch (Exception e) {
            log.error("[HedgeClaim] Unexpected error: {}", e.getMessage(), e);
        }
    }

    /**
     * 手动触发（供 Controller 调用）
     */
    public int triggerClaims() {
        return doProcessClaims();
    }

    private int doProcessClaims() {
        List<HedgePositionEntity> settlingPositions =
                positionTracker.getSettlementPendingPositions(hedgeProperties.getSettlementBatchSize())
                        .stream()
                        .filter(p -> "SETTLING".equals(p.getStatus()))
                        .toList();

        if (settlingPositions.isEmpty()) {
            return 0;
        }

        int claimed = 0;
        for (HedgePositionEntity position : settlingPositions) {
            try {
                if (claimPosition(position)) {
                    claimed++;
                }
            } catch (Exception e) {
                log.warn("[HedgeClaim] Error claiming positionId={}: {}", position.getId(), e.getMessage());
            }
        }
        if (claimed > 0) {
            log.info("[HedgeClaim] Successfully claimed {} positions", claimed);
        }
        return claimed;
    }

    private boolean claimPosition(HedgePositionEntity position) {
        if (!StringUtils.hasText(position.getFinalOutcome())) {
            log.warn("[HedgeClaim] positionId={} has SETTLING status but no finalOutcome, skipping", position.getId());
            return false;
        }

        // 查找赢方订单，获取实际成交量（经 HedgeOrderPollingJob 同步后的真实数量）
        BigDecimal claimableQty = resolveClaimableQuantity(position);
        if (claimableQty == null || claimableQty.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("[HedgeClaim] positionId={} has no claimable quantity (orders may not be filled yet)", position.getId());
            return false;
        }

        // 构造最小化 SmartWalletPositionEntity 复用链上 Claim 网关（仅使用 marketId 和 quantity）
        // marketId 现在通过 marketInfo 委托 getter 获取
        SmartWalletMarketInfoEntity proxyMarketInfo = new SmartWalletMarketInfoEntity()
                .setMarketId(position.getMarketId());
        SmartWalletPositionEntity proxyPosition = new SmartWalletPositionEntity()
                .setMarketInfo(proxyMarketInfo)
                .setQuantity(claimableQty);

        SmartWalletOnChainClaimGateway.ClaimSubmission submission;
        try {
            submission = onChainClaimGateway.submitClaim(proxyPosition);
        } catch (Exception e) {
            log.error("[HedgeClaim] Gateway error for positionId={}: {}", position.getId(), e.getMessage());
            return false;
        }

        switch (submission.status()) {
            case CLAIMED, SUBMITTED -> {
                // Claim 已提交或确认，标记持仓为 SETTLED
                BigDecimal claimedUsdc = claimableQty; // 1 token = $1 USDC
                BigDecimal feeUsdc = claimedUsdc
                        .multiply(hedgeProperties.getEstimatedFeeRate())
                        .setScale(4, RoundingMode.HALF_UP);
                positionTracker.markSettled(position.getId(), claimedUsdc, feeUsdc);
                log.info("[HedgeClaim] Position SETTLED: id={} market={} outcome={} claimed={} fee={} msg={}",
                        position.getId(), position.getMarketId(), position.getFinalOutcome(),
                        claimedUsdc, feeUsdc, submission.message());
                return true;
            }
            case MANUAL_CONFIRM_REQUIRED -> {
                // 网关配置为手动确认模式，等待人工操作，不推进状态
                log.info("[HedgeClaim] positionId={} requires manual on-chain confirmation: {}",
                        position.getId(), submission.message());
                return false;
            }
            case RETRYABLE_FAILURE -> {
                log.warn("[HedgeClaim] positionId={} retryable failure, will retry next cycle: {}",
                        position.getId(), submission.message());
                return false;
            }
            case PERMANENT_FAILURE -> {
                log.error("[HedgeClaim] positionId={} permanent failure, manual intervention required: {}",
                        position.getId(), submission.message());
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * 确定可 Claim 的数量：
     *   1. 优先使用赢方订单的实际成交量（经 HedgeOrderPollingJob 确认的真实数据）
     *   2. 若订单尚未轮询确认（旧数据），回退到持仓记录的 quantity
     */
    private BigDecimal resolveClaimableQuantity(HedgePositionEntity position) {
        String winSide = normalizeOutcomeSide(position.getFinalOutcome());
        if (StringUtils.hasText(winSide)) {
            List<HedgeOrderEntity> orders = positionTracker.getOrdersByPositionId(position.getId());
            BigDecimal winFilledQty = orders.stream()
                    .filter(o -> winSide.equals(o.getSide()))
                    .filter(o -> "FILLED".equals(o.getStatus()))
                    .map(o -> o.getFilledQuantity() != null ? o.getFilledQuantity() : BigDecimal.ZERO)
                    .findFirst().orElse(BigDecimal.ZERO);
            if (winFilledQty.compareTo(BigDecimal.ZERO) > 0) {
                return winFilledQty;
            }
        }
        // 回退：使用持仓记录的 quantity（已被轮询 Job 同步为实际值，或为原始预期量）
        return position.getQuantity();
    }

    /**
     * 将 Polymarket resolvedOutcome 标准化为订单 side 字段格式（"YES"/"NO"）
     * PolymarketApiClient 将 outcome 标准化为全大写
     */
    private String normalizeOutcomeSide(String finalOutcome) {
        if (!StringUtils.hasText(finalOutcome)) return null;
        String upper = finalOutcome.trim().toUpperCase();
        if (upper.startsWith("YES")) return "YES";
        if (upper.startsWith("NO")) return "NO";
        return null;
    }
}
