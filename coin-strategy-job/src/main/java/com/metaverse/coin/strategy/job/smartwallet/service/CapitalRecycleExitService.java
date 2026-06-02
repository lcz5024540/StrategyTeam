package com.metaverse.coin.strategy.job.smartwallet.service;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletManualAllowlistService;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletMarketInfoService;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
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

    /**
     * F-P1-17: 机会成本评分维度（占满分 20）所依赖的 PENDING 任务查询。
     * 通过 setter 注入避免破坏现有测试构造器；bean 缺失时降级为返回 0（与旧"简化实现"等价）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SmartWalletFollowTaskMapper followTaskMapper;

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
        if (config.isPaperEnabled()) {
            scanPaperPositionsForRecycle(config);
        }
        if (config.isLiveEnabled()) {
            scanLivePositionsForRecycle(config);
        }
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

                // 2026-05-29 ray@cowork (亏损根因 #1 修复)：退出前校验 bid 侧吸纳能力。
                // 防止"挂 SELL 但盘口没人接"→ 真实场景挂单残留、PAPER 场景账面价值与实际可成交价脱节。
                BidLiquidityCheck liquidityCheck = checkBidLiquidity(position, config);
                if (!liquidityCheck.passed()) {
                    log.info("CAPITAL_RECYCLE skipped positionId={} reason=INSUFFICIENT_BID_DEPTH bidDepth={} required>={} absorbRatio={} mode=PAPER",
                            position.getId(),
                            liquidityCheck.bidDepth() == null ? "n/a" : liquidityCheck.bidDepth().setScale(2, RoundingMode.HALF_UP),
                            liquidityCheck.requiredDepth() == null ? "n/a" : liquidityCheck.requiredDepth().setScale(2, RoundingMode.HALF_UP),
                            liquidityCheck.absorbRatio() == null ? "n/a" : liquidityCheck.absorbRatio().setScale(3, RoundingMode.HALF_UP));
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

                log.info("CAPITAL_RECYCLE triggered positionId={} marketId={} outcome={} score={} holdingH={} roi={}% cost={} exitPrice={} pnl={} currentStatus={} bidAbsorb={}",
                        position.getId(),
                        position.getMarketId(),
                        position.getOutcome(),
                        candidate.score,
                        candidate.holdingHours,
                        candidate.roiRate.multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP),
                        safe(position.getCostBasisUsdc()).setScale(2, RoundingMode.HALF_UP),
                        exitPrice,
                        safe(exitResult.getRealizedPnlDeltaUsdc()).setScale(2, RoundingMode.HALF_UP),
                        exitResult.getCurrentStatus(),
                        liquidityCheck.absorbRatio() == null ? "n/a" : liquidityCheck.absorbRatio().setScale(3, RoundingMode.HALF_UP));
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

                // 2026-05-29 ray@cowork (亏损根因 #1 修复)：退出前校验 bid 侧吸纳能力。
                // LIVE 模式下尤为关键——空盘口挂 SELL 会挂单不成交、占用槽位、并被 stale-order GC 误判。
                BidLiquidityCheck liquidityCheck = checkBidLiquidity(position, config);
                if (!liquidityCheck.passed()) {
                    log.info("LIVE CAPITAL_RECYCLE skipped positionId={} reason=INSUFFICIENT_BID_DEPTH bidDepth={} required>={} absorbRatio={}",
                            position.getId(),
                            liquidityCheck.bidDepth() == null ? "n/a" : liquidityCheck.bidDepth().setScale(2, RoundingMode.HALF_UP),
                            liquidityCheck.requiredDepth() == null ? "n/a" : liquidityCheck.requiredDepth().setScale(2, RoundingMode.HALF_UP),
                            liquidityCheck.absorbRatio() == null ? "n/a" : liquidityCheck.absorbRatio().setScale(3, RoundingMode.HALF_UP));
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

            // ③ 市场流动性分 (0~15) — F-P1-16: 真实 bid-side 深度评分
            // 旧实现用 |ROI| 反向衡量，会把"长期未动但盘口极薄"误判为流动性好；
            // 现在直接读 orderbook bid 侧 3c 范围内累计 size, 与本仓位 quantity 比较:
            //   absorbRatio = bid_depth_3c / position_quantity
            //   ratio ≥ 1.5 → 15 (盘口能从容吸纳, 退出滑点低)
            //   1.0 ≤ ratio < 1.5 → 12
            //   0.5 ≤ ratio < 1.0 → 8 (部分滑点)
            //   ratio < 0.5 → 4 (退出代价高, 不鼓励回收)
            //   orderbook 不可用 → 退回旧 |ROI| 启发式 (保持向后兼容)
            double liquidityScore = computeLiquidityScore(position, quantity, roiRate);

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

    /**
     * F-P1-17: 真实查询排队信号数（PENDING 状态的跟单任务），用于机会成本评分。
     *
     * <p>评分公式（详见 {@link #scoreCandidates}）：
     * {@code opportunityScore = min(pendingSignals / maxPositions, 1.0) × 20}
     * <ul>
     *   <li>队列越长 → 当前未释放仓位的机会成本越高 → 评分越高 → 越倾向回收；</li>
     *   <li>队列为空 → opportunityScore = 0，回收完全靠 holding / cost / liquidity 三维度。</li>
     * </ul>
     *
     * <p>查询窗口：仅统计 24h 内创建的 PENDING 任务，避免历史僵尸数据虚增队列长度
     * （上游 {@code SmartWalletFollowTaskService} 已有兜底超时清理，但 24h 窗口给一层
     * 防御性兜底；窗口可通过 {@code signal-risk.pending-signal-lookback-hours} 配置）。
     *
     * <p>失败语义：mapper bean 缺失 / 查询异常时返回 0（与旧"简化实现"等价），
     * 不影响主路径的其他评分维度。
     */
    private int countPendingSignals() {
        if (followTaskMapper == null) {
            return 0;
        }
        try {
            int lookbackHours = 24;
            if (properties != null && properties.getSignalRisk() != null) {
                Integer configured = properties.getSignalRisk().getPendingSignalLookbackHours();
                if (configured != null && configured > 0) {
                    lookbackHours = configured;
                }
            }
            Date cutoff = Date.from(Instant.now().minus(Duration.ofHours(lookbackHours)));
            Long count = followTaskMapper.selectCount(
                    new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                            .eq(SmartWalletFollowTaskEntity::getStatus, "PENDING")
                            .ge(SmartWalletFollowTaskEntity::getCreatedAt, cutoff));
            return count == null ? 0 : count.intValue();
        } catch (RuntimeException ex) {
            log.debug("countPendingSignals fallback to 0 (mapper failed): {}", ex.getMessage());
            return 0;
        }
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

    /**
     * F-P1-16: 真实 bid-side 流动性深度评分.
     *
     * <p>逻辑:
     * <ol>
     *   <li>拉 token 的实时 orderbook (走 `marketDataCacheService`, 已有缓存兜底);</li>
     *   <li>累计 best bid 下 3 美分以内所有档位的 size → bidDepth3c;</li>
     *   <li>absorbRatio = bidDepth3c / positionQuantity:
     *     <ul>
     *       <li>≥ 1.5 → 15 分: 盘口能从容吸纳本仓位, 退出滑点极低;</li>
     *       <li>≥ 1.0 → 12 分: 单次性吃完, 边际滑点;</li>
     *       <li>≥ 0.5 → 8 分: 部分滑点风险;</li>
     *       <li>&lt; 0.5 → 4 分: 退出代价高, 不鼓励回收.</li>
     *     </ul>
     *   </li>
     *   <li>orderbook 不可用 / 异常时退回旧 |ROI| 启发式 (保持向后兼容).</li>
     * </ol>
     *
     * <p>注意: 这里取 bid 侧而非 ask 侧, 因为资金回收是 SELL 操作, 流动性由对手方 bid depth 决定.
     */
    private double computeLiquidityScore(SmartWalletPositionEntity position, BigDecimal quantity, BigDecimal roiRate) {
        BigDecimal bidDepth3c = fetchBidDepthWithinCents(position, new BigDecimal("0.03"));
        if (bidDepth3c == null || quantity == null || quantity.signum() <= 0) {
            // Fallback to legacy heuristic (绝对 ROI 越小评分越高)
            double absRoi = Math.abs(roiRate.doubleValue());
            return absRoi < 0.02 ? 15.0 : (absRoi < 0.04 ? 10.0 : 5.0);
        }
        double absorbRatio = bidDepth3c.divide(quantity, 6, RoundingMode.HALF_UP).doubleValue();
        if (absorbRatio >= 1.5) return 15.0;
        if (absorbRatio >= 1.0) return 12.0;
        if (absorbRatio >= 0.5) return 8.0;
        return 4.0;
    }

    /**
     * F-P1-16 helper: 累计 best bid 下 maxOffset 美分以内所有 bid 档位的 size.
     * 返回 null 表示 orderbook 不可用 (调用方走 fallback).
     */
    private BigDecimal fetchBidDepthWithinCents(SmartWalletPositionEntity position, BigDecimal maxOffset) {
        String tokenId = position.getTokenId();
        if (tokenId == null || tokenId.isBlank()) {
            return null;
        }
        try {
            PolymarketApiClient.OrderBook orderBook = marketDataCacheService.getOrderBook(tokenId);
            if (orderBook == null || orderBook.getBids() == null || orderBook.getBids().isEmpty()) {
                return null;
            }
            BigDecimal bestBid = orderBook.getBids().get(0).getPrice();
            if (bestBid == null || bestBid.signum() <= 0) {
                return null;
            }
            BigDecimal cutoff = bestBid.subtract(maxOffset);
            BigDecimal cumulative = BigDecimal.ZERO;
            for (PolymarketApiClient.OrderBookLevel level : orderBook.getBids()) {
                if (level == null || level.getPrice() == null || level.getSize() == null) {
                    continue;
                }
                if (level.getPrice().compareTo(cutoff) < 0) {
                    break; // bids 按价格降序, 一旦低于 cutoff 后续都不在窗口内
                }
                cumulative = cumulative.add(level.getSize());
            }
            return cumulative;
        } catch (Exception ex) {
            log.debug("[F-P1-16] fetchBidDepthWithinCents failed tokenId={}: {}", tokenId, ex.getMessage());
            return null;
        }
    }

    private static BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    // ─── Bid-Liquidity Guard (亏损根因 #1 修复, 2026-05-29 ray@cowork) ──────────────

    /**
     * 退出前盘口吸纳能力校验。
     * <p>
     * 与 {@link #computeLiquidityScore} 的差异：
     * <ul>
     *   <li>{@code computeLiquidityScore}：用 0~15 分纳入 candidate score，作为"是否值得回收"的软性输入；</li>
     *   <li>本方法：硬性闸门——absorbRatio &lt; {@code minBidAbsorbRatio} 时直接跳过本轮回收，
     *       理由是"挂 SELL 但盘口没人接 → PAPER 账面损益脱离真实可成交价 / LIVE 挂单残留"。</li>
     * </ul>
     * <p>
     * absorbRatio = (best bid 下 {@code bidDepthWindowCents} 美分内累计 size) / position.quantity。
     * 配置缺失或 ratio = 0 时视为不校验（向后兼容 + 紧急关闭通道）。
     * orderbook 不可用（API 失败 / 缓存空）时**保守放行** —— 避免外部依赖故障导致止损路径全瘫。
     */
    private BidLiquidityCheck checkBidLiquidity(SmartWalletPositionEntity position,
                                                SmartWalletProperties.CapitalRecycle config) {
        BigDecimal minRatio = config.getMinBidAbsorbRatio();
        if (minRatio == null || minRatio.signum() <= 0) {
            return BidLiquidityCheck.passThrough("disabled");
        }
        BigDecimal quantity = position.getQuantity();
        if (quantity == null || quantity.signum() <= 0) {
            return BidLiquidityCheck.passThrough("no_quantity");
        }
        BigDecimal window = config.getBidDepthWindowCents();
        if (window == null || window.signum() <= 0) {
            window = new BigDecimal("0.03");
        }
        BigDecimal bidDepth = fetchBidDepthWithinCents(position, window);
        if (bidDepth == null) {
            // orderbook 不可用：保守放行，让 resolveRealtimeExitPrice 的 markToMarket fallback 处理。
            return BidLiquidityCheck.passThrough("orderbook_unavailable");
        }
        BigDecimal required = quantity.multiply(minRatio);
        BigDecimal absorbRatio = bidDepth.divide(quantity, 6, RoundingMode.HALF_UP);
        boolean ok = bidDepth.compareTo(required) >= 0;
        return new BidLiquidityCheck(ok, bidDepth, required, absorbRatio);
    }

    /**
     * Bid-liquidity 校验结果。
     * <p>
     * - {@code passed=false} 时调用方应跳过本轮 + cooldown；
     * - {@code passed=true} 但 {@code bidDepth==null} 表示 orderbook 不可用，保守放行。
     */
    private record BidLiquidityCheck(boolean passed,
                                     BigDecimal bidDepth,
                                     BigDecimal requiredDepth,
                                     BigDecimal absorbRatio) {
        static BidLiquidityCheck passThrough(String reason) {
            return new BidLiquidityCheck(true, null, null, null);
        }
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
