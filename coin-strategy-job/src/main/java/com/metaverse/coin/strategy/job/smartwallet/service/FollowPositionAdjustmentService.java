package com.metaverse.coin.strategy.job.smartwallet.service;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletMarketInfoService;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.ExecutionMode;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletExecutionResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPositionExitRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPositionExitResultView;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

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
 *   REDUCE_THEN_REVERSE — leader 买入反向 outcome (F-P1-11: 平仓后可选自动反手建仓,
 *                          开关 signal-risk.enable-auto-reverse-on-opposite-buy=true 启用)
 *   NO_POSITION_SKIP   — follower 无持仓，安全跳过
 *
 * <p><b>F-P1-10 (2026-05-17 第四轮)</b>: 当同一事件触发多条 follower 仓位调整时，
 * 由 for 循环顺序执行改为 {@link CompletableFuture#allOf(CompletableFuture[])} 并发执行，
 * 并发上限由 {@code signal-risk.position-adjustment-max-concurrency} 控制（默认 4），
 * 使用专用 fixed thread pool + Semaphore 控制并发度，避免 leader 同时退出多仓时延迟累积。
 * 兜底：单 task 失败不影响其他 task，所有 task 都失败时仍返回失败 result（保持原行为）。
 * 紧急回滚开关 {@code signal-risk.enable-position-adjustment-concurrency=false} 可退化回顺序执行。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FollowPositionAdjustmentService {

    private static final String LOG_PREFIX = "[FollowPositionAdjustment]";

    /** F-P1-10: 单仓位调整超时（秒）。超时后视为失败但不阻塞其他仓位。 */
    private static final long PER_POSITION_TIMEOUT_SECONDS = 30L;

    private final SmartWalletPositionMapper positionMapper;
    private final SmartWalletMarketInfoService marketInfoService;
    private final SmartWalletAccountingService accountingService;
    private final PolymarketClobExecutionService clobExecutionService;
    private final SmartWalletProperties properties;

    /**
     * F-P1-11: 反手建仓时插入新 follow_task 让既有调度链路接管。可选注入：
     * 测试 / mapper 缺失时反手功能静默关闭，不影响主路径。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SmartWalletFollowTaskMapper followTaskMapper;

    /**
     * F1（2026-06-05 补充场景库：按 leader 自身减仓比例镜像）。可选注入：
     * 缺失时退回既有 leaderSellUsdc/followerTotalCost 代理口径，不影响主路径。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SmartWalletTradeEventMapper tradeEventMapper;

    /** F-P1-10: 并发执行专用线程池；@PostConstruct 创建，@PreDestroy 关闭。 */
    private ExecutorService adjustmentExecutor;
    /** F-P1-10: Semaphore 二级限流，确保即便外部线程池更大，本服务仍按配置上限发起任务。 */
    private Semaphore concurrencyLimiter;
    /** F-P1-10: 当前生效的并发上限（缓存避免每次仓位调整都查 properties）。 */
    private int effectiveMaxConcurrency = 4;

    @PostConstruct
    void initExecutor() {
        SmartWalletProperties.SignalRisk risk = properties == null ? null : properties.getSignalRisk();
        int maxConcurrency = (risk == null) ? 4 : Math.max(1, risk.getPositionAdjustmentMaxConcurrency());
        this.effectiveMaxConcurrency = maxConcurrency;
        this.concurrencyLimiter = new Semaphore(maxConcurrency);
        // 线程池容量 = 上限 + 1 (留一个 slot 给 main 提交线程，避免 fixed pool 饱和卡死)
        this.adjustmentExecutor = Executors.newFixedThreadPool(maxConcurrency + 1, new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger(0);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "follow-position-adjust-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
        log.info("{} action=initExecutor maxConcurrency={}", LOG_PREFIX, maxConcurrency);
    }

    @PreDestroy
    void shutdownExecutor() {
        if (adjustmentExecutor == null) return;
        try {
            adjustmentExecutor.shutdown();
            if (!adjustmentExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                adjustmentExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            adjustmentExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

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
            // leader 买了反向 outcome —— 先平原仓
            AdjustmentResult exitResult = executeFullExit(positions, task, event, mode, AdjustmentAction.REDUCE_THEN_REVERSE,
                    "OPPOSITE_BUY: leader bought opposite outcome, closing follower same-side position.");
            // F-P1-11: 反手建仓 —— 平仓成功 + 开关启用 时插入反向 PENDING follow_task,
            // 让既有调度链路（信号风控 / 价格漂移 / 执行）接管。失败 swallow，不影响主结果。
            if (exitResult.isSuccess() && isAutoReverseEnabled() && followTaskMapper != null) {
                String reverseSummary = submitReverseEntryTask(task, event, exitResult, mode);
                if (reverseSummary != null) {
                    String original = exitResult.getMessage() == null ? "" : exitResult.getMessage();
                    return AdjustmentResult.builder()
                            .action(exitResult.getAction())
                            .success(exitResult.isSuccess())
                            .exitRatio(exitResult.getExitRatio())
                            .exitedQuantity(exitResult.getExitedQuantity())
                            .exitedNotionalUsdc(exitResult.getExitedNotionalUsdc())
                            .message(original + reverseSummary)
                            .build();
                }
            }
            return exitResult;
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
     * 全部平仓：遍历所有匹配仓位，逐个（或并发）执行退出。
     */
    private AdjustmentResult executeFullExit(List<SmartWalletPositionEntity> positions,
                                             SmartWalletFollowTaskEntity task,
                                             SmartWalletTradeEventEntity event,
                                             ExecutionMode mode,
                                             AdjustmentAction action,
                                             String reason) {
        // F-P1-10: 多仓位并发；单仓位 / 开关关闭时退化为顺序
        if (positions.size() <= 1 || !isConcurrencyEnabled()) {
            return executeFullExitSequential(positions, task, event, mode, action, reason);
        }
        return executeFullExitConcurrent(positions, task, event, mode, action, reason);
    }

    private AdjustmentResult executeFullExitSequential(List<SmartWalletPositionEntity> positions,
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
            PositionExitOutcome out = exitSinglePositionFull(position, event, mode, reason);
            totalExitedQty = totalExitedQty.add(out.exitedQty);
            totalExitedUsdc = totalExitedUsdc.add(out.exitedUsdc);
            allSuccess = allSuccess && out.success;
            messages.append(out.message);
        }
        log.info("{} action=全平仓完成(顺序) action={} success={} totalExitedQty={} totalExitedUsdc={} followTaskId={}",
                LOG_PREFIX, action, allSuccess, totalExitedQty, totalExitedUsdc, task.getId());
        return AdjustmentResult.builder()
                .action(action)
                .success(allSuccess)
                .message(messages.toString())
                .exitedQuantity(totalExitedQty)
                .exitedNotionalUsdc(totalExitedUsdc)
                .build();
    }

    private AdjustmentResult executeFullExitConcurrent(List<SmartWalletPositionEntity> positions,
                                                       SmartWalletFollowTaskEntity task,
                                                       SmartWalletTradeEventEntity event,
                                                       ExecutionMode mode,
                                                       AdjustmentAction action,
                                                       String reason) {
        long startMs = System.currentTimeMillis();
        List<CompletableFuture<PositionExitOutcome>> futures = new ArrayList<>(positions.size());
        for (SmartWalletPositionEntity position : positions) {
            CompletableFuture<PositionExitOutcome> f = CompletableFuture.supplyAsync(() -> {
                try {
                    concurrencyLimiter.acquire();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return PositionExitOutcome.failed(position.getId(),
                            "INTERRUPTED while waiting for concurrency slot");
                }
                try {
                    return exitSinglePositionFull(position, event, mode, reason);
                } finally {
                    concurrencyLimiter.release();
                }
            }, adjustmentExecutor);
            futures.add(f);
        }
        BigDecimal totalExitedQty = BigDecimal.ZERO;
        BigDecimal totalExitedUsdc = BigDecimal.ZERO;
        StringBuilder messages = new StringBuilder();
        boolean allSuccess = true;
        for (int i = 0; i < futures.size(); i++) {
            CompletableFuture<PositionExitOutcome> f = futures.get(i);
            SmartWalletPositionEntity position = positions.get(i);
            try {
                PositionExitOutcome out = f.get(PER_POSITION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                totalExitedQty = totalExitedQty.add(out.exitedQty);
                totalExitedUsdc = totalExitedUsdc.add(out.exitedUsdc);
                allSuccess = allSuccess && out.success;
                messages.append(out.message);
            } catch (TimeoutException te) {
                f.cancel(true);
                allSuccess = false;
                log.warn("{} action=并发全平仓超时 positionId={} timeoutSec={}",
                        LOG_PREFIX, position.getId(), PER_POSITION_TIMEOUT_SECONDS);
                messages.append("EXIT positionId=").append(position.getId())
                        .append(" TIMEOUT(").append(PER_POSITION_TIMEOUT_SECONDS).append("s); ");
            } catch (ExecutionException | InterruptedException ee) {
                if (ee instanceof InterruptedException) Thread.currentThread().interrupt();
                allSuccess = false;
                Throwable cause = (ee.getCause() != null) ? ee.getCause() : ee;
                log.error("{} action=并发全平仓异常 positionId={} error={}",
                        LOG_PREFIX, position.getId(), cause.toString(), cause);
                messages.append("EXIT positionId=").append(position.getId())
                        .append(" ERROR: ").append(cause.toString()).append("; ");
            }
        }
        long elapsedMs = System.currentTimeMillis() - startMs;
        log.info("{} action=全平仓完成(并发) action={} success={} totalExitedQty={} totalExitedUsdc={} positions={} elapsedMs={} followTaskId={}",
                LOG_PREFIX, action, allSuccess, totalExitedQty, totalExitedUsdc, positions.size(), elapsedMs, task.getId());
        return AdjustmentResult.builder()
                .action(action)
                .success(allSuccess)
                .message(messages.toString())
                .exitedQuantity(totalExitedQty)
                .exitedNotionalUsdc(totalExitedUsdc)
                .build();
    }

    /** F-P1-10 抽出的单仓位全平实现，供顺序 / 并发两条路径共用。 */
    private PositionExitOutcome exitSinglePositionFull(SmartWalletPositionEntity position,
                                                       SmartWalletTradeEventEntity event,
                                                       ExecutionMode mode,
                                                       String reason) {
        marketInfoService.populate(position);
        BigDecimal posQty = safe(position.getQuantity());
        BigDecimal exitPrice = resolveExitPrice(event, position);
        if (exitPrice == null || exitPrice.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("{} action=全平仓SKIP reason=EXIT_PRICE_UNAVAILABLE positionId={} qty={} mode={}",
                    LOG_PREFIX, position.getId(), posQty, mode);
            return PositionExitOutcome.skipped(position.getId(), "EXIT positionId=" + position.getId()
                    + " SKIPPED: EXIT_PRICE_UNAVAILABLE; ");
        }
        log.info("{} action=执行全平仓 positionId={} qty={} exitPrice={} mode={} reason={}",
                LOG_PREFIX, position.getId(), posQty, exitPrice, mode, reason);
        try {
            if (mode == ExecutionMode.PAPER) {
                SmartWalletPositionExitRequest exitReq = new SmartWalletPositionExitRequest();
                exitReq.setPrice(exitPrice);
                exitReq.setQuantity(posQty);
                exitReq.setReason("FOLLOW_EXIT:" + reason);
                SmartWalletPositionExitResultView exitResult = accountingService.exitPosition(position.getId(), exitReq);
                return PositionExitOutcome.success(position.getId(), posQty, safe(position.getCostBasisUsdc()),
                        "PAPER_EXIT positionId=" + position.getId() + " OK; ");
            } else {
                SmartWalletExecutionResult liveResult = clobExecutionService.executeLiveExit(position, exitPrice, "FOLLOW_EXIT:" + reason);
                if (liveResult.isSuccess()) {
                    return PositionExitOutcome.success(position.getId(), posQty, safe(position.getCostBasisUsdc()),
                            "LIVE_EXIT positionId=" + position.getId() + " orderId=" + liveResult.getExternalOrderId() + " OK; ");
                } else {
                    return PositionExitOutcome.failed(position.getId(),
                            "LIVE_EXIT positionId=" + position.getId() + " FAILED: " + liveResult.getMessage() + "; ");
                }
            }
        } catch (Exception e) {
            log.error("{} action=执行全平仓异常 positionId={} mode={} error={}",
                    LOG_PREFIX, position.getId(), mode, e.getMessage(), e);
            return PositionExitOutcome.failed(position.getId(),
                    "EXIT positionId=" + position.getId() + " ERROR: " + e.getMessage() + "; ");
        }
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

        BigDecimal exitRatio = null;

        // ── F1（2026-06-05 补充场景库）：优先按 leader 自身减仓比例镜像 ──────────────────
        // reduceRatio = leader 本次卖出份额 / leader 卖出前自身持仓份额。
        // 既有代理口径 leaderSellUsdc/followerTotalCost 在 leader 远大于我方时会严重过度平仓
        // （leader 只卖其 5% 仓位，我方却被平 50%）。flag 默认关、数据不可用时回退代理口径，零风险。
        boolean proRataEnabled = properties != null
                && properties.getSignalRisk() != null
                && properties.getSignalRisk().isEnableLeaderProRataExit();
        if (proRataEnabled) {
            BigDecimal leaderSellShares = resolveLeaderSellShares(event);
            BigDecimal leaderPriorQty = computeLeaderPriorPositionShares(event);
            BigDecimal proRata = computeLeaderProRataRatio(leaderSellShares, leaderPriorQty);
            if (proRata != null) {
                exitRatio = proRata;
                log.info("{} action=按leader减仓比例镜像 leaderSellShares={} leaderPriorQty={} exitRatio={}",
                        LOG_PREFIX, leaderSellShares, leaderPriorQty, exitRatio);
            } else {
                log.info("{} action=leader减仓比例不可用,回退代理口径 leaderSellShares={} leaderPriorQty={}",
                        LOG_PREFIX, leaderSellShares, leaderPriorQty);
            }
        }

        // ── 回退/默认：减仓比例 = leader卖出USDC / follower总成本，上限 100% ──
        if (exitRatio == null) {
            if (followerTotalCost.compareTo(BigDecimal.ZERO) <= 0 || leaderSellUsdc.compareTo(BigDecimal.ZERO) <= 0) {
                // 无法计算比例，退化为全平
                log.warn("{} action=部分减仓 result=退化为全平 followerTotalCost={} leaderSellUsdc={}",
                        LOG_PREFIX, followerTotalCost, leaderSellUsdc);
                return executeFullExit(positions, task, event, mode, AdjustmentAction.FULL_EXIT,
                        "SOURCE_SELL: unable to compute partial ratio, falling back to full exit.");
            }
            exitRatio = leaderSellUsdc.divide(followerTotalCost, 4, RoundingMode.HALF_UP).min(BigDecimal.ONE);
            log.info("{} action=计算部分减仓比例(代理口径) leaderSellUsdc={} followerTotalCost={} exitRatio={}",
                    LOG_PREFIX, leaderSellUsdc, followerTotalCost, exitRatio);
        }

        // F-P0-1: LIVE 部分退出已实现，配置开关 enableLivePartialExit 控制
        boolean liveLivePartialExitEnabled = properties != null
                && properties.getExecutionWallet() != null
                && properties.getExecutionWallet().isEnableLivePartialExit();

        final BigDecimal finalExitRatio = exitRatio;
        // F-P1-10: 多仓位并发；单仓位 / 开关关闭时退化为顺序
        AdjustmentResult result;
        if (positions.size() <= 1 || !isConcurrencyEnabled()) {
            result = executePartialExitSequential(positions, task, finalExitRatio, event, mode, liveLivePartialExitEnabled);
        } else {
            result = executePartialExitConcurrent(positions, task, finalExitRatio, event, mode, liveLivePartialExitEnabled);
        }

        // 保留原有：若计算出的 ratio>=1 则上报为 FULL_EXIT 动作
        AdjustmentAction finalAction = finalExitRatio.compareTo(BigDecimal.ONE) >= 0
                ? AdjustmentAction.FULL_EXIT
                : AdjustmentAction.PARTIAL_EXIT;

        log.info("{} action=部分减仓完成 finalAction={} exitRatio={} success={} totalExitedQty={} totalExitedUsdc={} followTaskId={}",
                LOG_PREFIX, finalAction, finalExitRatio, result.isSuccess(),
                result.getExitedQuantity(), result.getExitedNotionalUsdc(), task.getId());

        // 复用 result, 仅刷新 action / exitRatio
        return AdjustmentResult.builder()
                .action(finalAction)
                .success(result.isSuccess())
                .exitRatio(finalExitRatio)
                .message(result.getMessage())
                .exitedQuantity(result.getExitedQuantity())
                .exitedNotionalUsdc(result.getExitedNotionalUsdc())
                .build();
    }

    private AdjustmentResult executePartialExitSequential(List<SmartWalletPositionEntity> positions,
                                                          SmartWalletFollowTaskEntity task,
                                                          BigDecimal exitRatio,
                                                          SmartWalletTradeEventEntity event,
                                                          ExecutionMode mode,
                                                          boolean liveLivePartialExitEnabled) {
        BigDecimal totalExitedQty = BigDecimal.ZERO;
        BigDecimal totalExitedUsdc = BigDecimal.ZERO;
        StringBuilder messages = new StringBuilder();
        boolean allSuccess = true;
        for (SmartWalletPositionEntity position : positions) {
            PositionExitOutcome out = exitSinglePositionPartial(position, exitRatio, event, mode, liveLivePartialExitEnabled);
            totalExitedQty = totalExitedQty.add(out.exitedQty);
            totalExitedUsdc = totalExitedUsdc.add(out.exitedUsdc);
            allSuccess = allSuccess && out.success;
            messages.append(out.message);
        }
        return AdjustmentResult.builder()
                .action(AdjustmentAction.PARTIAL_EXIT)
                .success(allSuccess)
                .exitRatio(exitRatio)
                .message(messages.toString())
                .exitedQuantity(totalExitedQty)
                .exitedNotionalUsdc(totalExitedUsdc)
                .build();
    }

    private AdjustmentResult executePartialExitConcurrent(List<SmartWalletPositionEntity> positions,
                                                          SmartWalletFollowTaskEntity task,
                                                          BigDecimal exitRatio,
                                                          SmartWalletTradeEventEntity event,
                                                          ExecutionMode mode,
                                                          boolean liveLivePartialExitEnabled) {
        long startMs = System.currentTimeMillis();
        List<CompletableFuture<PositionExitOutcome>> futures = new ArrayList<>(positions.size());
        for (SmartWalletPositionEntity position : positions) {
            CompletableFuture<PositionExitOutcome> f = CompletableFuture.supplyAsync(() -> {
                try {
                    concurrencyLimiter.acquire();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return PositionExitOutcome.failed(position.getId(),
                            "INTERRUPTED while waiting for concurrency slot");
                }
                try {
                    return exitSinglePositionPartial(position, exitRatio, event, mode, liveLivePartialExitEnabled);
                } finally {
                    concurrencyLimiter.release();
                }
            }, adjustmentExecutor);
            futures.add(f);
        }
        BigDecimal totalExitedQty = BigDecimal.ZERO;
        BigDecimal totalExitedUsdc = BigDecimal.ZERO;
        StringBuilder messages = new StringBuilder();
        boolean allSuccess = true;
        for (int i = 0; i < futures.size(); i++) {
            CompletableFuture<PositionExitOutcome> f = futures.get(i);
            SmartWalletPositionEntity position = positions.get(i);
            try {
                PositionExitOutcome out = f.get(PER_POSITION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                totalExitedQty = totalExitedQty.add(out.exitedQty);
                totalExitedUsdc = totalExitedUsdc.add(out.exitedUsdc);
                allSuccess = allSuccess && out.success;
                messages.append(out.message);
            } catch (TimeoutException te) {
                f.cancel(true);
                allSuccess = false;
                log.warn("{} action=并发部分减仓超时 positionId={} timeoutSec={}",
                        LOG_PREFIX, position.getId(), PER_POSITION_TIMEOUT_SECONDS);
                messages.append("PARTIAL positionId=").append(position.getId())
                        .append(" TIMEOUT(").append(PER_POSITION_TIMEOUT_SECONDS).append("s); ");
            } catch (ExecutionException | InterruptedException ee) {
                if (ee instanceof InterruptedException) Thread.currentThread().interrupt();
                allSuccess = false;
                Throwable cause = (ee.getCause() != null) ? ee.getCause() : ee;
                log.error("{} action=并发部分减仓异常 positionId={} error={}",
                        LOG_PREFIX, position.getId(), cause.toString(), cause);
                messages.append("PARTIAL positionId=").append(position.getId())
                        .append(" ERROR: ").append(cause.toString()).append("; ");
            }
        }
        long elapsedMs = System.currentTimeMillis() - startMs;
        log.info("{} action=部分减仓完成(并发) exitRatio={} success={} totalExitedQty={} totalExitedUsdc={} positions={} elapsedMs={} followTaskId={}",
                LOG_PREFIX, exitRatio, allSuccess, totalExitedQty, totalExitedUsdc, positions.size(), elapsedMs, task.getId());
        return AdjustmentResult.builder()
                .action(AdjustmentAction.PARTIAL_EXIT)
                .success(allSuccess)
                .exitRatio(exitRatio)
                .message(messages.toString())
                .exitedQuantity(totalExitedQty)
                .exitedNotionalUsdc(totalExitedUsdc)
                .build();
    }

    /** F-P1-10 抽出的单仓位部分减仓实现，供顺序 / 并发两条路径共用。 */
    private PositionExitOutcome exitSinglePositionPartial(SmartWalletPositionEntity position,
                                                          BigDecimal exitRatio,
                                                          SmartWalletTradeEventEntity event,
                                                          ExecutionMode mode,
                                                          boolean liveLivePartialExitEnabled) {
        marketInfoService.populate(position);
        BigDecimal posQty = safe(position.getQuantity());
        BigDecimal exitQty = posQty.multiply(exitRatio).setScale(8, RoundingMode.HALF_UP);
        if (exitQty.compareTo(BigDecimal.ZERO) <= 0) {
            return PositionExitOutcome.empty(position.getId(),
                    "PARTIAL positionId=" + position.getId() + " SKIPPED: ZERO_EXIT_QTY; ");
        }
        BigDecimal exitPrice = resolveExitPrice(event, position);
        if (exitPrice == null || exitPrice.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("{} action=部分减仓SKIP reason=EXIT_PRICE_UNAVAILABLE positionId={} qty={} mode={}",
                    LOG_PREFIX, position.getId(), posQty, mode);
            return PositionExitOutcome.skipped(position.getId(),
                    "PARTIAL positionId=" + position.getId() + " SKIPPED: EXIT_PRICE_UNAVAILABLE; ");
        }
        log.info("{} action=执行部分减仓 positionId={} totalQty={} exitQty={} exitRatio={} exitPrice={} mode={}",
                LOG_PREFIX, position.getId(), posQty, exitQty, exitRatio, exitPrice, mode);
        try {
            if (mode == ExecutionMode.PAPER) {
                SmartWalletPositionExitRequest exitReq = new SmartWalletPositionExitRequest();
                exitReq.setPrice(exitPrice);
                exitReq.setQuantity(exitQty);
                exitReq.setReason("FOLLOW_PARTIAL_EXIT:ratio=" + exitRatio);
                accountingService.exitPosition(position.getId(), exitReq);
                BigDecimal exitedUsdc = safe(position.getCostBasisUsdc()).multiply(exitRatio).setScale(2, RoundingMode.HALF_UP);
                return PositionExitOutcome.success(position.getId(), exitQty, exitedUsdc,
                        "PAPER_PARTIAL positionId=" + position.getId() + " exitQty=" + exitQty + " OK; ");
            } else {
                SmartWalletExecutionResult liveResult;
                if (liveLivePartialExitEnabled && exitRatio.compareTo(BigDecimal.ONE) < 0) {
                    liveResult = clobExecutionService.executeLivePartialExit(
                            position, exitRatio, exitPrice,
                            "FOLLOW_PARTIAL_EXIT:ratio=" + exitRatio);
                } else {
                    if (exitRatio.compareTo(BigDecimal.ONE) < 0) {
                        log.warn("{} action=LIVE部分减仓 result=回退全平 reason=enableLivePartialExit=false positionId={} exitRatio={}",
                                LOG_PREFIX, position.getId(), exitRatio);
                    }
                    liveResult = clobExecutionService.executeLiveExit(position, exitPrice,
                            "FOLLOW_PARTIAL_EXIT:ratio=" + exitRatio);
                }
                if (liveResult.isSuccess()) {
                    BigDecimal actualSize = safe(liveResult.getSize());
                    if (actualSize.compareTo(BigDecimal.ZERO) <= 0) {
                        actualSize = liveLivePartialExitEnabled ? exitQty : posQty;
                    }
                    BigDecimal actualCostShare = posQty.compareTo(BigDecimal.ZERO) > 0
                            ? safe(position.getCostBasisUsdc())
                                    .multiply(actualSize)
                                    .divide(posQty, 2, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;
                    return PositionExitOutcome.success(position.getId(), actualSize, actualCostShare,
                            "LIVE_PARTIAL positionId=" + position.getId()
                                    + " exitQty=" + actualSize
                                    + " orderId=" + liveResult.getExternalOrderId() + " OK; ");
                } else {
                    return PositionExitOutcome.failed(position.getId(),
                            "LIVE_PARTIAL positionId=" + position.getId() + " FAILED: " + liveResult.getMessage() + "; ");
                }
            }
        } catch (Exception e) {
            log.error("{} action=部分减仓异常 positionId={} mode={} error={}",
                    LOG_PREFIX, position.getId(), mode, e.getMessage(), e);
            return PositionExitOutcome.failed(position.getId(),
                    "PARTIAL positionId=" + position.getId() + " ERROR: " + e.getMessage() + "; ");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════

    /**
     * F-P1-10: 并发开关 — 默认开启 (true); 紧急回滚切 false 退化为顺序。
     */
    private boolean isConcurrencyEnabled() {
        SmartWalletProperties.SignalRisk risk = properties == null ? null : properties.getSignalRisk();
        return risk != null && risk.isEnablePositionAdjustmentConcurrency()
                && adjustmentExecutor != null
                && concurrencyLimiter != null;
    }

    /**
     * F-P1-11: OPPOSITE_BUY 自动反手开关 — 默认 false 灰度，开启后才插入 reverse follow_task。
     */
    private boolean isAutoReverseEnabled() {
        SmartWalletProperties.SignalRisk risk = properties == null ? null : properties.getSignalRisk();
        return risk != null && risk.isEnableAutoReverseOnOppositeBuy();
    }

    /**
     * F-P1-11: 真正实现 REDUCE_THEN_REVERSE 的 reverse leg。
     *
     * <p>策略：插入一条 status=PENDING 的新 {@link SmartWalletFollowTaskEntity}，
     * outcome 翻转为 leader 刚买入的对手 outcome（优先用 event.outcome，
     * fallback flip(task.outcome)），由既有 follow-task 调度链路完成
     * <ul>
     *   <li>全 13 Gate 风控（G1-G13 + W2 子段）；</li>
     *   <li>价格漂移二次检查（F-P0-5）；</li>
     *   <li>意图聚合 cap（F-P0-4）；</li>
     *   <li>执行（PAPER / LIVE，受 task.executionMode 控制）；</li>
     *   <li>账本登记 + 后续退出归集。</li>
     * </ul>
     *
     * <p>金额：{@code reverseStake = min(exitedNotionalUsdc × reverseStakeRatio, reverseStakeCapUsdc)},
     * 不预先校验 wallet.minOrderUsdc（下游风控 G1 会拒绝过小金额，统一在那里看）。
     *
     * <p>失败语义：mapper 缺失 / 反向 outcome 找不到 market_info / insert 异常时返回 null,
     * 上游打 WARN 日志并保留原 exit result，不影响主路径资金安全。
     *
     * @return reverse 任务摘要文本（成功时如 "REVERSE_LEG taskId=123 outcome=NO stake=15.00 PENDING;"，
     *         失败 / 跳过时返回简短原因；返回 null 表示静默忽略（不污染主 message）
     */
    private String submitReverseEntryTask(SmartWalletFollowTaskEntity originalTask,
                                          SmartWalletTradeEventEntity event,
                                          AdjustmentResult exitResult,
                                          ExecutionMode mode) {
        try {
            String originalOutcome = originalTask.getOutcome();
            String marketId = originalTask.getMarketId();
            if (marketId == null || originalOutcome == null) {
                return " REVERSE_LEG SKIPPED: missing marketId/outcome;";
            }
            // 反向 outcome：优先用 leader 事件中的 outcome（更准确），否则 flip(originalOutcome)
            String reverseOutcome = event != null && event.getOutcome() != null
                    ? event.getOutcome().trim().toUpperCase(Locale.ROOT)
                    : flipOutcome(originalOutcome);
            if (reverseOutcome == null) {
                return " REVERSE_LEG SKIPPED: cannot derive reverse outcome;";
            }
            Long reverseMarketInfoId = marketInfoService.findIdByMarketIdAndOutcome(marketId, reverseOutcome);
            if (reverseMarketInfoId == null) {
                log.warn("{} action=反手建仓SKIP reason=REVERSE_MARKET_INFO_MISSING marketId={} reverseOutcome={}",
                        LOG_PREFIX, marketId, reverseOutcome);
                return " REVERSE_LEG SKIPPED: reverse market_info missing;";
            }

            // 计算反手金额
            SmartWalletProperties.SignalRisk risk = properties.getSignalRisk();
            BigDecimal ratio = risk.getReverseStakeRatio() == null ? BigDecimal.ONE : risk.getReverseStakeRatio();
            BigDecimal exited = safe(exitResult.getExitedNotionalUsdc());
            BigDecimal reverseStake = exited.multiply(ratio).setScale(2, RoundingMode.HALF_UP);
            BigDecimal cap = risk.getReverseStakeCapUsdc();
            if (cap != null && cap.compareTo(BigDecimal.ZERO) > 0 && reverseStake.compareTo(cap) > 0) {
                reverseStake = cap;
            }
            if (reverseStake.compareTo(BigDecimal.ZERO) <= 0) {
                return " REVERSE_LEG SKIPPED: stake=0 (exit notional 0?);";
            }

            // 构造新 follow_task：让既有调度链路接管
            SmartWalletFollowTaskEntity reverseTask = new SmartWalletFollowTaskEntity();
            reverseTask.setWalletAddress(originalTask.getWalletAddress());
            reverseTask.setEntityId(originalTask.getEntityId());
            reverseTask.setSourceEventId(event == null ? originalTask.getSourceEventId() : event.getId());
            reverseTask.setTraceId(event != null && event.getTraceId() != null
                    ? event.getTraceId() : originalTask.getTraceId());
            reverseTask.setMarketInfoId(reverseMarketInfoId);
            reverseTask.setSide("BUY");
            reverseTask.setTriggerPrice(event != null ? event.getPrice() : null);
            reverseTask.setTriggerSizeUsdc(event != null ? event.getSizeUsdc() : null);
            reverseTask.setRecommendedStakeUsdc(reverseStake);
            reverseTask.setStatus("PENDING");
            reverseTask.setExecutionMode(mode == null ? ExecutionMode.PAPER.name() : mode.name());
            reverseTask.setIntentKey("REVERSE_FROM_" + originalTask.getId());
            reverseTask.setFragmentCount(1);
            Date now = new Date();
            reverseTask.setScheduledAt(now);
            reverseTask.setCreatedAt(now);
            reverseTask.setUpdatedAt(now);

            followTaskMapper.insert(reverseTask);
            log.info("{} action=反手建仓PENDING插入 reverseTaskId={} originalTaskId={} marketId={} reverseOutcome={} stake={} mode={}",
                    LOG_PREFIX, reverseTask.getId(), originalTask.getId(), marketId, reverseOutcome, reverseStake, mode);
            return " REVERSE_LEG taskId=" + reverseTask.getId()
                    + " outcome=" + reverseOutcome
                    + " stake=" + reverseStake
                    + " PENDING;";
        } catch (RuntimeException ex) {
            log.warn("{} action=反手建仓异常 originalTaskId={} err={}",
                    LOG_PREFIX, originalTask == null ? null : originalTask.getId(), ex.toString(), ex);
            return " REVERSE_LEG ERROR: " + ex.getMessage() + ";";
        }
    }

    /**
     * F-P1-11: outcome 翻转 helper —— YES ↔ NO，其他值返回 null（不强行猜测）。
     */
    private String flipOutcome(String outcome) {
        if (outcome == null) return null;
        String up = outcome.trim().toUpperCase(Locale.ROOT);
        if ("YES".equals(up)) return "NO";
        if ("NO".equals(up)) return "YES";
        return null;
    }

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
     * 解析退出价格。
     *
     * <p>R8(2026-06-03 盈利审计修复)：<b>优先用"本账户实时盘口 bid"</b>而非 leader 的成交价。
     * leader 通常体量更大/更快，成交在 follower 拿不到的更优档位；若用 leader 价记账，PAPER 的退出
     * 收益/胜率会系统性虚高，且与真实可成交价背离。这里先 markToMarket 刷新一次本账户标记价
     * （来源是实时 orderbook bestBid），再按 [本账户 bid → leader 价 → 均价] 回退。LIVE 路径
     * executeLiveExit 仍会自行再解析 bid 下单，此处口径与之一致、互不冲突。</p>
     *
     * <p>F-P0-2: 三段价格回退链全部不可用时返回 {@code null}（让调用方走 SKIP），
     * 不再硬编码 0.50 兜底，避免在极端情况下用远偏离市场的价格强行下单。</p>
     *
     * @return 可用的退出价格；若无任何可用价则返回 {@code null}，调用方应标 EXIT_PRICE_UNAVAILABLE 并 SKIP。
     */
    private BigDecimal resolveExitPrice(SmartWalletTradeEventEntity event, SmartWalletPositionEntity position) {
        // R8：先用实时盘口刷新本账户标记价（bestBid）；市场不活跃/取价失败时保留上次值，不阻断。
        try {
            accountingService.markToMarket(position);
        } catch (Exception ignore) {
            // best-effort，失败则用仓位已有 lastMarkPrice
        }
        if (position.getLastMarkPrice() != null && position.getLastMarkPrice().compareTo(BigDecimal.ZERO) > 0) {
            return position.getLastMarkPrice();
        }
        if (event != null && event.getPrice() != null && event.getPrice().compareTo(BigDecimal.ZERO) > 0) {
            return event.getPrice();
        }
        if (position.getAverageEntryPrice() != null && position.getAverageEntryPrice().compareTo(BigDecimal.ZERO) > 0) {
            return position.getAverageEntryPrice();
        }
        // F-P0-2: 拒绝 0.50 硬兜底，让调用方 SKIP（primarySkipReason=EXIT_PRICE_UNAVAILABLE）
        return null;
    }

    // ════════════════════════════════════════════════════════════════════
    // F1（2026-06-05 补充场景库）：按 leader 自身减仓比例镜像跟随退出
    // ════════════════════════════════════════════════════════════════════

    /**
     * F1 纯函数：leader 按比例减仓的镜像比例 = 本次卖出份额 / 卖出前自身持仓份额。
     * 任一输入为空/非正、或比例≤0 时返回 null（调用方回退既有代理口径）；结果钳到上限 1.0。
     * 抽为 package-private static 便于单测，不依赖任何实例状态。
     */
    static BigDecimal computeLeaderProRataRatio(BigDecimal leaderSellShares,
                                                BigDecimal leaderPriorPositionShares) {
        if (leaderSellShares == null || leaderPriorPositionShares == null) {
            return null;
        }
        if (leaderSellShares.compareTo(BigDecimal.ZERO) <= 0
                || leaderPriorPositionShares.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        BigDecimal ratio = leaderSellShares.divide(leaderPriorPositionShares, 4, RoundingMode.HALF_UP);
        if (ratio.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return ratio.min(BigDecimal.ONE);
    }

    /** leader 本次卖出份额：优先 sizeShares，缺失时用 sizeUsdc/price 估算；都不可用返回 0。 */
    private BigDecimal resolveLeaderSellShares(SmartWalletTradeEventEntity event) {
        if (event == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal shares = safe(event.getSizeShares());
        if (shares.compareTo(BigDecimal.ZERO) > 0) {
            return shares;
        }
        BigDecimal usdc = safe(event.getSizeUsdc());
        BigDecimal price = safe(event.getPrice());
        if (usdc.compareTo(BigDecimal.ZERO) > 0 && price.compareTo(BigDecimal.ZERO) > 0) {
            return usdc.divide(price, 8, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO;
    }

    /**
     * leader 在本次卖出前的净持仓份额 = Σ(更早 BUY 份额) − Σ(更早 SELL 份额)，
     * 取自 trade_event 历史（openedAt 早于当前事件）。mapper 缺失/无历史/净仓≤0 时返回 null
     * （调用方回退代理口径）。份额优先 sizeShares，缺失用 sizeUsdc/price 估算。
     */
    private BigDecimal computeLeaderPriorPositionShares(SmartWalletTradeEventEntity event) {
        if (tradeEventMapper == null || event == null
                || event.getWalletAddress() == null || event.getWalletAddress().isBlank()
                || event.getMarketId() == null || event.getMarketId().isBlank()
                || event.getOutcome() == null || event.getOutcome().isBlank()
                || event.getOpenedAt() == null) {
            return null;
        }
        try {
            List<SmartWalletTradeEventEntity> priors = tradeEventMapper.selectList(
                    new LambdaQueryWrapper<SmartWalletTradeEventEntity>()
                            .eq(SmartWalletTradeEventEntity::getWalletAddress, event.getWalletAddress())
                            .eq(SmartWalletTradeEventEntity::getMarketId, event.getMarketId())
                            .eq(SmartWalletTradeEventEntity::getOutcome, event.getOutcome())
                            .lt(SmartWalletTradeEventEntity::getOpenedAt, event.getOpenedAt()));
            if (priors == null || priors.isEmpty()) {
                return null;
            }
            BigDecimal net = BigDecimal.ZERO;
            for (SmartWalletTradeEventEntity e : priors) {
                BigDecimal sh = safe(e.getSizeShares());
                if (sh.compareTo(BigDecimal.ZERO) <= 0) {
                    BigDecimal usdc = safe(e.getSizeUsdc());
                    BigDecimal price = safe(e.getPrice());
                    if (usdc.compareTo(BigDecimal.ZERO) > 0 && price.compareTo(BigDecimal.ZERO) > 0) {
                        sh = usdc.divide(price, 8, RoundingMode.HALF_UP);
                    }
                }
                if ("SELL".equalsIgnoreCase(e.getSide())) {
                    net = net.subtract(sh);
                } else {
                    net = net.add(sh);
                }
            }
            return net.compareTo(BigDecimal.ZERO) > 0 ? net : null;
        } catch (Exception ex) {
            log.debug("{} computeLeaderPriorPositionShares failed: {}", LOG_PREFIX, ex.toString());
            return null;
        }
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

    /**
     * F-P1-10: 单仓位退出结果，用于并发 / 顺序两条路径汇总。
     */
    private static final class PositionExitOutcome {
        final Long positionId;
        final boolean success;
        final BigDecimal exitedQty;
        final BigDecimal exitedUsdc;
        final String message;

        private PositionExitOutcome(Long positionId, boolean success,
                                    BigDecimal exitedQty, BigDecimal exitedUsdc, String message) {
            this.positionId = positionId;
            this.success = success;
            this.exitedQty = exitedQty == null ? BigDecimal.ZERO : exitedQty;
            this.exitedUsdc = exitedUsdc == null ? BigDecimal.ZERO : exitedUsdc;
            this.message = message == null ? "" : message;
        }

        static PositionExitOutcome success(Long id, BigDecimal qty, BigDecimal usdc, String msg) {
            return new PositionExitOutcome(id, true, qty, usdc, msg);
        }

        static PositionExitOutcome failed(Long id, String msg) {
            return new PositionExitOutcome(id, false, BigDecimal.ZERO, BigDecimal.ZERO, msg);
        }

        static PositionExitOutcome skipped(Long id, String msg) {
            // SKIP 算作不成功（与原顺序实现一致 allSuccess=false）
            return new PositionExitOutcome(id, false, BigDecimal.ZERO, BigDecimal.ZERO, msg);
        }

        static PositionExitOutcome empty(Long id, String msg) {
            // exitQty=0 的合法跳过 (不计入 allSuccess)
            return new PositionExitOutcome(id, true, BigDecimal.ZERO, BigDecimal.ZERO, msg);
        }
    }
}
