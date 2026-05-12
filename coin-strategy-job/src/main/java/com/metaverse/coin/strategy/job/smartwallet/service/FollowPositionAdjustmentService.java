package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.ExecutionMode;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletExecutionResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPositionExitRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPositionExitResultView;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.List;

/**
 * 跟单仓位调整服务 —— P0-1 核心。
 *
 * 当 leader 发出 SELL 或 OPPOSITE_BUY 信号时，
 * SmartWalletFollowTaskService 不再简单 SKIP，而是转调本服务，
 * 根据 follower 现有仓位生成并执行对应的减仓/平仓动作。
 *
 * 支持四种调整动作：
 *   FULL_EXIT          — leader 清仓，follower 同步全部平仓
 *   PARTIAL_EXIT       — leader 部分减仓，follower 按比例同步减仓
 *   REDUCE_THEN_REVERSE — leader 买入反向 outcome（P0 只做减仓部分，不自动反手建仓）
 *   NO_POSITION_SKIP   — follower 无持仓，安全跳过
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FollowPositionAdjustmentService {

    private static final String LOG_PREFIX = "[FollowPositionAdjustment]";

    private final SmartWalletPositionMapper positionMapper;
    private final SmartWalletMarketInfoService marketInfoService;
    private final SmartWalletAccountingService accountingService;
    private final PolymarketClobExecutionService clobExecutionService;

    // ════════════════════════════════════════════════════════════════════
    // Public API
    // ════════════════════════════════════════════════════════════════════

    /**
     * 根据退出型跟单信号，对 follower 仓位执行调整。
     *
     * @param task        跟单任务实体（已填充 marketInfo）
     * @param event       原始交易事件（leader 的 SELL / BUY）
     * @param exitIntent  退出意图：SOURCE_SELL / OPPOSITE_BUY
     * @param mode        执行模式：PAPER / LIVE
     * @return 调整结果
     */
    public AdjustmentResult adjust(SmartWalletFollowTaskEntity task,
                                   SmartWalletTradeEventEntity event,
                                   String exitIntent,
                                   ExecutionMode mode) {
        String marketId = task.getMarketId();
        String outcome = task.getOutcome();

        log.info("{} action=开始仓位调整 exitIntent={} mode={} marketId={} outcome={} followTaskId={} eventId={}",
                LOG_PREFIX, exitIntent, mode, marketId, outcome, task.getId(),
                event != null ? event.getId() : "null");

        // ── 1. 查找 follower 在同 market+outcome 上的 OPEN 仓位 ──
        List<SmartWalletPositionEntity> positions = findOpenPositions(marketId, outcome);

        if (positions.isEmpty()) {
            log.info("{} action=仓位调整 result=NO_POSITION_SKIP marketId={} outcome={} followTaskId={}",
                    LOG_PREFIX, marketId, outcome, task.getId());
            return AdjustmentResult.builder()
                    .action(AdjustmentAction.NO_POSITION_SKIP)
                    .success(true)
                    .message("Follower has no open position for this market+outcome. Safe skip.")
                    .exitedQuantity(BigDecimal.ZERO)
                    .exitedNotionalUsdc(BigDecimal.ZERO)
                    .build();
        }

        // ── 2. 根据 exitIntent 决定调整动作 ──
        if ("OPPOSITE_BUY".equalsIgnoreCase(exitIntent)) {
            // leader 买了反向 outcome —— P0 只做平原仓，不自动反手建仓
            return executeFullExit(positions, task, event, mode, AdjustmentAction.REDUCE_THEN_REVERSE,
                    "OPPOSITE_BUY: leader bought opposite outcome, closing follower same-side position.");
        }

        // SOURCE_SELL: leader 卖出
        if (event != null && isPartialSell(event, positions)) {
            return executePartialExit(positions, task, event, mode);
        } else {
            return executeFullExit(positions, task, event, mode, AdjustmentAction.FULL_EXIT,
                    "SOURCE_SELL: leader exited position, closing follower position.");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Exit execution
    // ════════════════════════════════════════════════════════════════════

    /**
     * 全部平仓：遍历所有匹配仓位，逐个执行退出。
     */
    private AdjustmentResult executeFullExit(List<SmartWalletPositionEntity> positions,
                                             SmartWalletFollowTaskEntity task,
                                             SmartWalletTradeEventEntity event,
                                             ExecutionMode mode,
                                             AdjustmentAction action,
                                             String reason) {
        BigDecimal totalExitedQty = BigDecimal.ZERO;
        BigDecimal totalExitedUsdc = BigDecimal.ZERO;
        StringBuilder messages = new StringBuilder();
        boolean allSuccess = true;

        for (SmartWalletPositionEntity position : positions) {
            marketInfoService.populate(position);
            BigDecimal posQty = safe(position.getQuantity());
            BigDecimal exitPrice = resolveExitPrice(event, position);

            log.info("{} action=执行全平仓 positionId={} qty={} exitPrice={} mode={} reason={}",
                    LOG_PREFIX, position.getId(), posQty, exitPrice, mode, reason);

            try {
                if (mode == ExecutionMode.PAPER) {
                    SmartWalletPositionExitRequest exitReq = new SmartWalletPositionExitRequest();
                    exitReq.setPrice(exitPrice);
                    exitReq.setQuantity(posQty);
                    exitReq.setReason("FOLLOW_EXIT:" + reason);
                    SmartWalletPositionExitResultView exitResult = accountingService.exitPosition(position.getId(), exitReq);
                    totalExitedQty = totalExitedQty.add(posQty);
                    totalExitedUsdc = totalExitedUsdc.add(safe(position.getCostBasisUsdc()));
                    messages.append("PAPER_EXIT positionId=").append(position.getId()).append(" OK; ");
                } else {
                    SmartWalletExecutionResult liveResult = clobExecutionService.executeLiveExit(position, exitPrice, "FOLLOW_EXIT:" + reason);
                    if (liveResult.isSuccess()) {
                        totalExitedQty = totalExitedQty.add(posQty);
                        totalExitedUsdc = totalExitedUsdc.add(safe(position.getCostBasisUsdc()));
                        messages.append("LIVE_EXIT positionId=").append(position.getId())
                                .append(" orderId=").append(liveResult.getExternalOrderId()).append(" OK; ");
                    } else {
                        allSuccess = false;
                        messages.append("LIVE_EXIT positionId=").append(position.getId())
                                .append(" FAILED: ").append(liveResult.getMessage()).append("; ");
                    }
                }
            } catch (Exception e) {
                allSuccess = false;
                log.error("{} action=执行全平仓异常 positionId={} mode={} error={}",
                        LOG_PREFIX, position.getId(), mode, e.getMessage(), e);
                messages.append("EXIT positionId=").append(position.getId())
                        .append(" ERROR: ").append(e.getMessage()).append("; ");
            }
        }

        log.info("{} action=全平仓完成 action={} success={} totalExitedQty={} totalExitedUsdc={} followTaskId={}",
                LOG_PREFIX, action, allSuccess, totalExitedQty, totalExitedUsdc, task.getId());

        return AdjustmentResult.builder()
                .action(action)
                .success(allSuccess)
                .message(messages.toString())
                .exitedQuantity(totalExitedQty)
                .exitedNotionalUsdc(totalExitedUsdc)
                .build();
    }

    /**
     * 部分减仓：按 leader 卖出比例同步减仓。
     *
     * 近似逻辑：leader event sizeUsdc / follower 总 costBasis → 减仓比例。
     * 由于缺少 leader 实时持仓快照，只能做近似比例同步（P0 限制）。
     */
    private AdjustmentResult executePartialExit(List<SmartWalletPositionEntity> positions,
                                                SmartWalletFollowTaskEntity task,
                                                SmartWalletTradeEventEntity event,
                                                ExecutionMode mode) {
        // 计算 follower 侧总持仓
        BigDecimal followerTotalCost = positions.stream()
                .map(p -> safe(p.getCostBasisUsdc()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // leader 卖出金额
        BigDecimal leaderSellUsdc = safe(event.getSizeUsdc());

        // 减仓比例 = leader卖出 / follower总成本，上限 100%
        BigDecimal exitRatio;
        if (followerTotalCost.compareTo(BigDecimal.ZERO) <= 0 || leaderSellUsdc.compareTo(BigDecimal.ZERO) <= 0) {
            // 无法计算比例，退化为全平
            log.warn("{} action=部分减仓 result=退化为全平 followerTotalCost={} leaderSellUsdc={}",
                    LOG_PREFIX, followerTotalCost, leaderSellUsdc);
            return executeFullExit(positions, task, event, mode, AdjustmentAction.FULL_EXIT,
                    "SOURCE_SELL: unable to compute partial ratio, falling back to full exit.");
        }
        exitRatio = leaderSellUsdc.divide(followerTotalCost, 4, RoundingMode.HALF_UP).min(BigDecimal.ONE);

        log.info("{} action=计算部分减仓比例 leaderSellUsdc={} followerTotalCost={} exitRatio={}",
                LOG_PREFIX, leaderSellUsdc, followerTotalCost, exitRatio);

        BigDecimal totalExitedQty = BigDecimal.ZERO;
        BigDecimal totalExitedUsdc = BigDecimal.ZERO;
        StringBuilder messages = new StringBuilder();
        boolean allSuccess = true;

        for (SmartWalletPositionEntity position : positions) {
            marketInfoService.populate(position);
            BigDecimal posQty = safe(position.getQuantity());
            BigDecimal exitQty = posQty.multiply(exitRatio).setScale(8, RoundingMode.HALF_UP);
            if (exitQty.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal exitPrice = resolveExitPrice(event, position);

            log.info("{} action=执行部分减仓 positionId={} totalQty={} exitQty={} exitRatio={} exitPrice={} mode={}",
                    LOG_PREFIX, position.getId(), posQty, exitQty, exitRatio, exitPrice, mode);

            try {
                if (mode == ExecutionMode.PAPER) {
                    SmartWalletPositionExitRequest exitReq = new SmartWalletPositionExitRequest();
                    exitReq.setPrice(exitPrice);
                    exitReq.setQuantity(exitQty);
                    exitReq.setReason("FOLLOW_PARTIAL_EXIT:ratio=" + exitRatio);
                    accountingService.exitPosition(position.getId(), exitReq);
                    totalExitedQty = totalExitedQty.add(exitQty);
                    totalExitedUsdc = totalExitedUsdc.add(
                            safe(position.getCostBasisUsdc()).multiply(exitRatio).setScale(2, RoundingMode.HALF_UP));
                    messages.append("PAPER_PARTIAL positionId=").append(position.getId())
                            .append(" exitQty=").append(exitQty).append(" OK; ");
                } else {
                    // LIVE 部分退出：executeLiveExit 目前退出全部仓位，
                    // P0 阶段如果比例 < 1 退化为全平并记录 warning
                    if (exitRatio.compareTo(BigDecimal.ONE) < 0) {
                        log.warn("{} action=LIVE部分减仓 result=退化为全平 positionId={} exitRatio={} " +
                                        "(LIVE partial exit not yet supported, closing full position)",
                                LOG_PREFIX, position.getId(), exitRatio);
                    }
                    SmartWalletExecutionResult liveResult = clobExecutionService.executeLiveExit(position, exitPrice,
                            "FOLLOW_PARTIAL_EXIT:ratio=" + exitRatio);
                    if (liveResult.isSuccess()) {
                        totalExitedQty = totalExitedQty.add(posQty); // LIVE 实际退出全部
                        totalExitedUsdc = totalExitedUsdc.add(safe(position.getCostBasisUsdc()));
                        messages.append("LIVE_PARTIAL→FULL positionId=").append(position.getId())
                                .append(" orderId=").append(liveResult.getExternalOrderId()).append(" OK; ");
                    } else {
                        allSuccess = false;
                        messages.append("LIVE_PARTIAL positionId=").append(position.getId())
                                .append(" FAILED: ").append(liveResult.getMessage()).append("; ");
                    }
                }
            } catch (Exception e) {
                allSuccess = false;
                log.error("{} action=部分减仓异常 positionId={} mode={} error={}",
                        LOG_PREFIX, position.getId(), mode, e.getMessage(), e);
                messages.append("PARTIAL positionId=").append(position.getId())
                        .append(" ERROR: ").append(e.getMessage()).append("; ");
            }
        }

        AdjustmentAction finalAction = exitRatio.compareTo(BigDecimal.ONE) >= 0
                ? AdjustmentAction.FULL_EXIT
                : AdjustmentAction.PARTIAL_EXIT;

        log.info("{} action=部分减仓完成 finalAction={} exitRatio={} success={} totalExitedQty={} totalExitedUsdc={} followTaskId={}",
                LOG_PREFIX, finalAction, exitRatio, allSuccess, totalExitedQty, totalExitedUsdc, task.getId());

        return AdjustmentResult.builder()
                .action(finalAction)
                .success(allSuccess)
                .exitRatio(exitRatio)
                .message(messages.toString())
                .exitedQuantity(totalExitedQty)
                .exitedNotionalUsdc(totalExitedUsdc)
                .build();
    }

    // ════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════

    /**
     * 查找 follower 在指定 market+outcome 上的所有 OPEN/PARTIALLY_CLOSED 仓位。
     * 通过 market_info_id 关联，与 FollowExposureLimitService 查询方式一致。
     */
    private List<SmartWalletPositionEntity> findOpenPositions(String marketId, String outcome) {
        Long marketInfoId = marketInfoService.findIdByMarketIdAndOutcome(marketId, outcome);
        if (marketInfoId == null) {
            log.info("{} action=查找仓位 result=未找到market_info marketId={} outcome={}", LOG_PREFIX, marketId, outcome);
            return List.of();
        }
        return positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .eq(SmartWalletPositionEntity::getMarketInfoId, marketInfoId)
                .in(SmartWalletPositionEntity::getStatus, List.of("OPEN", "PARTIALLY_CLOSED")));
    }

    /**
     * 判断是否为部分卖出（而非全部清仓）。
     *
     * 启发式判断：如果 leader 的卖出金额 < follower 持仓总成本的 80%，视为部分减仓。
     * 这是 P0 的近似做法，因为我们没有 leader 的实时持仓快照。
     */
    private boolean isPartialSell(SmartWalletTradeEventEntity event, List<SmartWalletPositionEntity> positions) {
        BigDecimal leaderSellUsdc = safe(event.getSizeUsdc());
        BigDecimal followerTotalCost = positions.stream()
                .map(p -> safe(p.getCostBasisUsdc()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (followerTotalCost.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }

        BigDecimal ratio = leaderSellUsdc.divide(followerTotalCost, 4, RoundingMode.HALF_UP);
        // 如果 leader 卖出金额 < follower 持仓的 80%，认为是部分减仓
        return ratio.compareTo(new BigDecimal("0.80")) < 0;
    }

    /**
     * 解析退出价格：优先用 leader 事件中的价格，回退到仓位的 lastMarkPrice / averageEntryPrice。
     */
    private BigDecimal resolveExitPrice(SmartWalletTradeEventEntity event, SmartWalletPositionEntity position) {
        if (event != null && event.getPrice() != null && event.getPrice().compareTo(BigDecimal.ZERO) > 0) {
            return event.getPrice();
        }
        if (position.getLastMarkPrice() != null && position.getLastMarkPrice().compareTo(BigDecimal.ZERO) > 0) {
            return position.getLastMarkPrice();
        }
        if (position.getAverageEntryPrice() != null && position.getAverageEntryPrice().compareTo(BigDecimal.ZERO) > 0) {
            return position.getAverageEntryPrice();
        }
        // 最后兜底
        return new BigDecimal("0.50");
    }

    private BigDecimal safe(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    // ════════════════════════════════════════════════════════════════════
    // Models
    // ════════════════════════════════════════════════════════════════════

    public enum AdjustmentAction {
        /** leader 清仓，follower 全部平仓 */
        FULL_EXIT,
        /** leader 部分减仓，follower 按比例减仓 */
        PARTIAL_EXIT,
        /** leader 买入反向 outcome，先平原仓（P0 不做反手建仓） */
        REDUCE_THEN_REVERSE,
        /** follower 无持仓，安全跳过 */
        NO_POSITION_SKIP
    }

    @Data
    @Builder
    public static class AdjustmentResult {
        /** 执行的调整动作 */
        private AdjustmentAction action;
        /** 是否执行成功（全部仓位都处理成功） */
        private boolean success;
        /** 减仓比例（仅 PARTIAL_EXIT 有值） */
        private BigDecimal exitRatio;
        /** 实际退出的总数量 */
        private BigDecimal exitedQuantity;
        /** 实际退出的总 USDC 名义值 */
        private BigDecimal exitedNotionalUsdc;
        /** 描述信息 */
        private String message;
    }
}
