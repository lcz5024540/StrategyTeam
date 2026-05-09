package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletExecutionResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPositionExitRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPositionExitResultView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 资金回收退出服务 — Capital Recycling Exit Service
 *
 * 对长时间持有、ROI 在死区（既不盈利也不明显亏损）的仓位进行评分，
 * 当评分超过阈值时主动退出以释放资金供后续信号使用。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CapitalRecycleExitService {

    public static final String EXIT_REASON = "CAPITAL_RECYCLE_AUTO_EXIT";
    private static final String LOG_STAGE = "S9_CAPITAL_RECYCLE";

    private final SmartWalletProperties properties;
    private final SmartWalletPositionMapper positionMapper;
    private final SmartWalletOrderMapper orderMapper;
    private final SmartWalletAccountingService accountingService;
    private final SmartWalletMarketInfoService marketInfoService;
    private final PolymarketClobExecutionService clobExecutionService;
    private final ExecutionWalletContextLoader walletContextLoader;
    private final ExitSignalProvider exitSignalProvider;
    private final PolymarketMarketDataCacheService marketDataCacheService;
    private final SmartWalletManualAllowlistService manualAllowlistService;

    /** 每个仓位的冷却截止时间，防止短时间内反复评估同一仓位 */
    private final ConcurrentHashMap<Long, Instant> cooldownMap = new ConcurrentHashMap<>();

    /** 当日已回收金额（按自然日 UTC 重置） */
    private final AtomicReference<DailyBudgetTracker> dailyBudget = new AtomicReference<>(new DailyBudgetTracker());

    // ─── Scheduled Entry Point ───────────────────────────────────────────────────

    @Scheduled(fixedDelay = 300_000)
    public void checkCapitalRecycle() {
        SmartWalletProperties.CapitalRecycle config = resolveConfig();
        if (config == null || !config.isEnabled()) {
            return;
        }
        scanPaperPositionsForRecycle(config);
        scanLivePositionsForRecycle(config);
    }

    // ─── Allowlist Position Filter ──────────────────────────────────────────────

    private List<SmartWalletPositionEntity> filterOutAllowlistPositions(List<SmartWalletPositionEntity> positions) {
        if (positions == null || positions.isEmpty()) {
            return positions;
        }
        Set<Long> positionIds = positions.stream()
                .map(SmartWalletPositionEntity::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (positionIds.isEmpty()) {
            return positions;
        }
        List<SmartWalletOrderEntity> entryOrders = orderMapper.selectList(
                new LambdaQueryWrapper<SmartWalletOrderEntity>()
                        .in(SmartWalletOrderEntity::getPositionId, positionIds)
                        .eq(SmartWalletOrderEntity::getSide, "BUY"));
        Map<Long, String> positionWallets = new HashMap<>();
        for (SmartWalletOrderEntity order : entryOrders) {
            if (order.getPositionId() != null && order.getWalletAddress() != null) {
                positionWallets.putIfAbsent(order.getPositionId(), order.getWalletAddress());
            }
        }
        Set<Long> allowlistPositionIds = new HashSet<>();
        Map<String, Boolean> walletCache = new HashMap<>();
        for (Map.Entry<Long, String> entry : positionWallets.entrySet()) {
            String wallet = entry.getValue().toLowerCase();
            String originalAddress = entry.getValue();
            walletCache.computeIfAbsent(wallet, w -> manualAllowlistService.findActive(originalAddress).isPresent());
            if (Boolean.TRUE.equals(walletCache.get(wallet))) {
                allowlistPositionIds.add(entry.getKey());
            }
        }
        if (allowlistPositionIds.isEmpty()) {
            return positions;
        }
        log.info("[ALLOWLIST_EXIT_SKIP] 跳过白名单仓位资金回收 positionIds={}", allowlistPositionIds);
        return positions.stream()
                .filter(p -> !allowlistPositionIds.contains(p.getId()))
                .collect(Collectors.toList());
    }

    // ─── Paper Position Scan ─────────────────────────────────────────────────────

    private void scanPaperPositionsForRecycle(SmartWalletProperties.CapitalRecycle config) {
        List<SmartWalletPositionEntity> openPositions = positionMapper.selectList(
                new LambdaQueryWrapper<SmartWalletPositionEntity>()
                        .eq(SmartWalletPositionEntity::getExecutionAddress, "paper-book")
                        .in(SmartWalletPositionEntity::getStatus, List.of("OPEN", "PARTIALLY_CLOSED")));
        openPositions = filterOutAllowlistPositions(openPositions);
        marketInfoService.populatePositions(openPositions);

        List<RecycleCandidate> candidates = scoreCandidates(openPositions, config);
        int recycled = 0;

        for (RecycleCandidate candidate : candidates) {
            if (recycled >= config.getMaxRecyclePerRound()) {
                break;
            }
            if (exceedsDailyBudget(config, candidate.position.getCostBasisUsdc())) {
                log.info("Capital recycle daily budget exhausted, stopping.");
                break;
            }

            SmartWalletPositionEntity position = candidate.position;
            try {
                BigDecimal exitPrice = resolveRealtimeExitPrice(position);
                if (exitPrice.compareTo(BigDecimal.ZERO) <= 0) {
                    log.debug("CAPITAL_RECYCLE skipped positionId={} — unable to resolve realtime exit price", position.getId());
                    applyCooldown(position.getId(), config);
                    continue;
                }

                SmartWalletPositionExitRequest exitRequest = new SmartWalletPositionExitRequest();
                exitRequest.setPrice(exitPrice);
                exitRequest.setQuantity(position.getQuantity());
                exitRequest.setReason(EXIT_REASON);
                SmartWalletPositionExitResultView exitResult = accountingService.exitPosition(position.getId(), exitRequest);
                exitSignalProvider.clearPositionHistory(position.getId());

                recordDailyRecycled(safe(position.getCostBasisUsdc()));
                recycled++;

                log.info("CAPITAL_RECYCLE triggered positionId={} marketId={} outcome={} score={} holdingH={} roi={}% cost={} exitPrice={} pnl={} currentStatus={}",
                        position.getId(),
                        position.getMarketId(),
                        position.getOutcome(),
                        candidate.score,
                        candidate.holdingHours,
                        candidate.roiRate.multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP),
                        safe(position.getCostBasisUsdc()).setScale(2, RoundingMode.HALF_UP),
                        exitPrice,
                        safe(exitResult.getRealizedPnlDeltaUsdc()).setScale(2, RoundingMode.HALF_UP),
                        exitResult.getCurrentStatus());
            } catch (Exception ex) {
                log.warn("CAPITAL_RECYCLE exit failed for positionId={} marketId={}: {}",
                        position.getId(),
                        position.getMarketId(),
                        ex.getMessage(),
                        ex);
            }
            // Apply cooldown regardless of success/failure
            applyCooldown(position.getId(), config);
        }
    }

    // ─── Live Position Scan ──────────────────────────────────────────────────────

    private void scanLivePositionsForRecycle(SmartWalletProperties.CapitalRecycle config) {
        ExecutionWalletContextLoader.ExecutionWalletContext wallet;
        try {
            wallet = walletContextLoader.requireLiveContext();
        } catch (Exception ex) {
            return;
        }
        if (wallet.getAddress() == null || wallet.getAddress().isBlank()) {
            return;
        }

        List<SmartWalletPositionEntity> openPositions = positionMapper.selectList(
                new LambdaQueryWrapper<SmartWalletPositionEntity>()
                        .eq(SmartWalletPositionEntity::getExecutionAddress, wallet.getAddress())
                        .in(SmartWalletPositionEntity::getStatus, List.of("OPEN", "PARTIALLY_CLOSED")));
        openPositions = filterOutAllowlistPositions(openPositions);
        marketInfoService.populatePositions(openPositions);

        List<RecycleCandidate> candidates = scoreCandidates(openPositions, config);
        int recycled = 0;

        for (RecycleCandidate candidate : candidates) {
            if (recycled >= config.getMaxRecyclePerRound()) {
                break;
            }
            if (exceedsDailyBudget(config, candidate.position.getCostBasisUsdc())) {
                break;
            }

            SmartWalletPositionEntity position = candidate.position;
            try {
                BigDecimal exitPrice = resolveRealtimeExitPrice(position);
                if (exitPrice.compareTo(BigDecimal.ZERO) <= 0) {
                    log.debug("LIVE CAPITAL_RECYCLE skipped positionId={} — unable to resolve realtime exit price", position.getId());
                    applyCooldown(position.getId(), config);
                    continue;
                }

                SmartWalletExecutionResult result = clobExecutionService.executeLiveExit(
                        position,
                        exitPrice,
                        EXIT_REASON
                );
                accountingService.recordLiveExitExecution(position, result, EXIT_REASON);

                if (result != null && result.isSuccess()) {
                    exitSignalProvider.clearPositionHistory(position.getId());
                    recordDailyRecycled(safe(position.getCostBasisUsdc()));
                    recycled++;
                }

                log.info("LIVE CAPITAL_RECYCLE submitted positionId={} marketId={} outcome={} score={} holdingH={} roi={}% exitPrice={} status={} orderId={}",
                        position.getId(),
                        position.getMarketId(),
                        position.getOutcome(),
                        candidate.score,
                        candidate.holdingHours,
                        candidate.roiRate.multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP),
                        exitPrice,
                        result == null ? "UNKNOWN" : result.getStatus(),
                        result == null ? null : result.getExternalOrderId());
            } catch (Exception ex) {
                log.warn("LIVE CAPITAL_RECYCLE exit failed for positionId={} marketId={}: {}",
                        position.getId(),
                        position.getMarketId(),
                        ex.getMessage(),
                        ex);
            }
            applyCooldown(position.getId(), config);
        }
    }

    // ─── Scoring Logic ───────────────────────────────────────────────────────────

    /**
     * 对候选仓位进行评分，返回按分数降序排列的候选列表（仅包含通过阈值的仓位）。
     */
    private List<RecycleCandidate> scoreCandidates(
            List<SmartWalletPositionEntity> positions,
            SmartWalletProperties.CapitalRecycle config) {

        // 计算全部 OPEN 仓位的总成本（用于资金占用比评分）
        BigDecimal totalOpenCost = positions.stream()
                .map(p -> safe(p.getCostBasisUsdc()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 计算当前排队信号数（用于机会成本评分）
        int pendingSignals = countPendingSignals();
        int maxPositions = properties.getSignalRisk() != null
                ? properties.getSignalRisk().getMaxOpenPositions()
                : 10;

        List<RecycleCandidate> candidates = new ArrayList<>();

        for (SmartWalletPositionEntity position : positions) {
            // 冷却中跳过
            if (isInCooldown(position.getId())) {
                continue;
            }

            // 计算持仓时长（小时）
            long holdingHours = computeHoldingHours(position);
            if (holdingHours < config.getHoldingPeriodHours()) {
                continue;
            }

            // 计算当前 ROI（使用实时 orderbook 价格）
            BigDecimal markPrice = resolveRealtimeExitPrice(position);
            BigDecimal quantity = safe(position.getQuantity());
            BigDecimal costBasis = safe(position.getCostBasisUsdc());
            if (costBasis.compareTo(BigDecimal.ZERO) <= 0 || markPrice.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal currentValue = markPrice.multiply(quantity).setScale(8, RoundingMode.HALF_UP);
            BigDecimal roiRate = currentValue.subtract(costBasis)
                    .divide(costBasis, 8, RoundingMode.HALF_UP);

            // 检查 ROI 是否在死区
            if (roiRate.compareTo(config.getDeadZoneLowRate()) < 0
                    || roiRate.compareTo(config.getDeadZoneHighRate()) > 0) {
                continue;
            }

            // ── 评分 ──
            // ① 持仓时长分 (0~40)
            double holdingScore = Math.min((double) holdingHours / 24.0, 1.0) * 40.0;

            // ② 资金占用比分 (0~25)
            double costRatio = totalOpenCost.compareTo(BigDecimal.ZERO) > 0
                    ? costBasis.divide(totalOpenCost, 8, RoundingMode.HALF_UP).doubleValue()
                    : 0.0;
            double costScore = Math.min(costRatio * 2.5, 1.0) * 25.0; // 占 40%+ 时满分

            // ③ 市场流动性分 (0~15) — 简化实现：基于仓位价值变化幅度
            // 价格几乎没动说明流动性差或市场不活跃
            double absRoi = Math.abs(roiRate.doubleValue());
            double liquidityScore = absRoi < 0.02 ? 15.0 : (absRoi < 0.04 ? 10.0 : 5.0);

            // ④ 机会成本分 (0~20)
            double opportunityScore = maxPositions > 0
                    ? Math.min((double) pendingSignals / maxPositions, 1.0) * 20.0
                    : 0.0;

            int totalScore = (int) Math.round(holdingScore + costScore + liquidityScore + opportunityScore);

            if (totalScore >= config.getRecycleThreshold()) {
                candidates.add(new RecycleCandidate(position, totalScore, holdingHours, roiRate));
            }
        }

        // 按评分降序排列
        candidates.sort(Comparator.comparingInt(RecycleCandidate::score).reversed());

        // 安全护栏：至少保留 minRetainPositions 个仓位
        int totalOpen = positions.size();
        int maxRecyclable = Math.max(0, totalOpen - config.getMinRetainPositions());
        if (candidates.size() > maxRecyclable) {
            candidates = candidates.subList(0, maxRecyclable);
        }

        return candidates;
    }

    // ─── Helper Methods ──────────────────────────────────────────────────────────

    private long computeHoldingHours(SmartWalletPositionEntity position) {
        Date openedAt = position.getOpenedAt();
        if (openedAt == null) {
            openedAt = position.getCreatedAt();
        }
        if (openedAt == null) {
            return 0;
        }
        return Duration.between(openedAt.toInstant(), Instant.now()).toHours();
    }

    private int countPendingSignals() {
        // 简化实现：返回 0，机会成本评分暂不启用。
        // 后续可接入 SmartWalletFollowTaskMapper 查询 PENDING/EXECUTING 任务数
        // 以获得更精确的排队信号计数。
        return 0;
    }

    private boolean isInCooldown(Long positionId) {
        Instant until = cooldownMap.get(positionId);
        if (until == null) {
            return false;
        }
        if (Instant.now().isAfter(until)) {
            cooldownMap.remove(positionId);
            return false;
        }
        return true;
    }

    private void applyCooldown(Long positionId, SmartWalletProperties.CapitalRecycle config) {
        int minutes = config.getCooldownMinutes() != null ? config.getCooldownMinutes() : 30;
        cooldownMap.put(positionId, Instant.now().plusSeconds(minutes * 60L));
    }

    private boolean exceedsDailyBudget(SmartWalletProperties.CapitalRecycle config, BigDecimal positionCost) {
        DailyBudgetTracker tracker = dailyBudget.get();
        if (!tracker.isToday()) {
            tracker = new DailyBudgetTracker();
            dailyBudget.set(tracker);
        }
        BigDecimal limit = config.getDailyRecycleBudgetUsdc();
        if (limit == null || limit.compareTo(BigDecimal.ZERO) <= 0) {
            return false; // no budget limit
        }
        return tracker.getRecycledUsdc().add(safe(positionCost)).compareTo(limit) > 0;
    }

    private void recordDailyRecycled(BigDecimal amount) {
        DailyBudgetTracker tracker = dailyBudget.get();
        if (!tracker.isToday()) {
            tracker = new DailyBudgetTracker();
            dailyBudget.set(tracker);
        }
        tracker.add(amount);
    }

    private SmartWalletProperties.CapitalRecycle resolveConfig() {
        if (properties.getSignalRisk() == null) {
            return null;
        }
        return properties.getSignalRisk().getCapitalRecycle();
    }

    /**
     * 从 Polymarket CLOB API 实时获取退出价格。
     * 优先使用 orderbook best-bid（卖出方即 bid 侧），fallback 到 midpoint，
     * 再 fallback 到最近 trade event markPrice。
     */
    private BigDecimal resolveRealtimeExitPrice(SmartWalletPositionEntity position) {
        String tokenId = position.getTokenId();
        if (tokenId == null || tokenId.isBlank()) {
            // 无 tokenId 则回退到 markToMarket
            accountingService.markToMarket(position);
            return safe(position.getLastMarkPrice());
        }
        try {
            PolymarketApiClient.OrderBook orderBook = marketDataCacheService.getOrderBook(tokenId);
            if (orderBook != null) {
                // 卖出使用 best bid 作为退出价
                BigDecimal bestBid = extractBestBid(orderBook);
                if (bestBid != null && bestBid.compareTo(BigDecimal.ZERO) > 0) {
                    // 同时更新 position 的 lastMarkPrice 以保持一致
                    position.setLastMarkPrice(bestBid);
                    return bestBid;
                }
                // fallback: midpoint
                BigDecimal midpoint = safe(orderBook.getMidpoint());
                if (midpoint.compareTo(BigDecimal.ZERO) > 0) {
                    position.setLastMarkPrice(midpoint);
                    return midpoint;
                }
            }
        } catch (Exception ex) {
            log.debug("Failed to fetch realtime orderbook for tokenId={}: {}", tokenId, ex.getMessage());
        }
        // 最终 fallback: markToMarket（基于 trade events）
        accountingService.markToMarket(position);
        return safe(position.getLastMarkPrice());
    }

    private BigDecimal extractBestBid(PolymarketApiClient.OrderBook orderBook) {
        if (orderBook.getBids() == null || orderBook.getBids().isEmpty()) {
            return null;
        }
        // bids 按价格降序排列，第一个即 best bid
        return orderBook.getBids().get(0).getPrice();
    }

    private static BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    // ─── Inner Types ─────────────────────────────────────────────────────────────

    private record RecycleCandidate(
            SmartWalletPositionEntity position,
            int score,
            long holdingHours,
            BigDecimal roiRate
    ) {}

    private static class DailyBudgetTracker {
        private final int dayOfYear;
        private BigDecimal recycledUsdc = BigDecimal.ZERO;

        DailyBudgetTracker() {
            this.dayOfYear = java.time.LocalDate.now().getDayOfYear();
        }

        boolean isToday() {
            return java.time.LocalDate.now().getDayOfYear() == dayOfYear;
        }

        BigDecimal getRecycledUsdc() {
            return recycledUsdc;
        }

        synchronized void add(BigDecimal amount) {
            recycledUsdc = recycledUsdc.add(amount);
        }
    }
}
