package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketSnapshotEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletMarketInfoService;
import com.metaverse.coin.strategy.job.engine.monitor.AlertType;
import com.metaverse.coin.strategy.job.smartwallet.audit.SmartWalletAuditAlertSink;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletExecutionResult;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 持仓侧价格监控 — 纯观测 + WARN 告警, 不下任何订单。
 *
 * <p>目标场景: 解决既有退出链路的两个盲区。
 * <ol>
 *   <li><b>"卖出吃单空"判负盲区</b> — 持仓的 token best bid 完全为空 = 无人接货, 但
 *       {@link CapitalRecycleExitService} 5min 节奏 + 仅在 ROI 死区触发, 抓不到这种"已浮亏
 *       但没流动性的"仓位; {@link TimeAwareStopLossService} 依赖 mark price 实际下跌, 而
 *       bid 空时 mark price 可能仍由 ask / last trade 撑住, 也不触发。</li>
 *   <li><b>低 entry 仓位刷新频率不足</b> — {@link MarketSnapshotCollectorService} 是按市场维度
 *       统一节奏, 没有按"我持仓的 entry 价位"分档加密刷新; entry < 0.10 的仓位对价格变化
 *       更敏感, 应当高频刷新, 但现状是和高价仓位走同一节奏。</li>
 * </ol>
 *
 * <p>解决方式: 单一 {@code @Scheduled} 每 30s 唤醒, 对每个 OPEN/PARTIALLY_CLOSED 仓位:
 * <ul>
 *   <li>按 entry 价位算"应当刷新间隔" (低/中/高三档, 默认 2min / 10min / 30min)</li>
 *   <li>若距上次刷新 < tier 间隔, 跳过</li>
 *   <li>否则 invalidate orderbook 缓存 + 重拉, 若 bids 为空 → WARN 日志 +
 *       {@link SmartWalletAuditAlertSink} publish 进 strategy_alert 表 (自带去重)</li>
 *   <li>同仓位告警冷却 30min, 避免反复告警</li>
 *   <li>最近 N 条告警写入内存 ring buffer, 暴露 {@link #recentAlerts()} 供 dashboard 回看</li>
 * </ul>
 *
 * <p><b>不下单</b>: 不调用 PolymarketClobExecutionService。bids 空时只产告警, 运维基于告警
 * 自行评估是否手动减仓。这是有意为之 — bids 真空时市价单可能被 0.001 接货导致 -90%+, 不应
 * 自动决策。</p>
 *
 * <p>紧急回滚: {@code smart-wallet.position-price-monitor.enabled=false}.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PositionPriceMonitorService {

    private static final String LOG_PREFIX = "[PositionPriceMonitor]";

    private final SmartWalletProperties properties;
    private final SmartWalletPositionMapper positionMapper;
    private final SmartWalletMarketInfoService marketInfoService;
    private final PolymarketMarketDataCacheService marketDataCacheService;

    /** 告警 sink 可选注入: 测试 / 早期部署无 sink bean 时降级为只打 WARN 日志。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SmartWalletAuditAlertSink auditAlertSink;

    /** 自动减仓执行通道, 可选注入: 缺失时自动减仓静默关闭, 仅观测路径不受影响。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PolymarketClobExecutionService clobExecutionService;

    /** B2: 查活跃 SELL 单 (替换前 cancel 旧单); 可选, 缺失时退化为"不查直接下"行为。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SmartWalletOrderMapper orderMapper;

    /** E4: PAPER 影子撮合, 可选; 缺失时 PAPER 路径退化回仅 log。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ShadowFillSimulationService shadowFillSimulationService;

    /** E4: PAPER 影子撮合所需的 market snapshot 查询; 可选。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MarketSnapshotCollectorService marketSnapshotCollectorService;

    /** positionId → 最近一次实际刷新 orderbook 的时间, 用于按 tier 间隔判断是否到点。 */
    private final Map<Long, Instant> lastRefreshedAt = new ConcurrentHashMap<>();

    /** positionId → 最近一次为该仓位触发"空 bid"告警的时间, 用于 publish 节流。 */
    private final Map<Long, Instant> lastAlertedAt = new ConcurrentHashMap<>();

    /** D2: positionId → 连续观测到空 bid 的次数, 达 consecutiveEmptyBidThreshold 才告警。 */
    private final Map<Long, Integer> consecutiveEmptyBidCount = new ConcurrentHashMap<>();

    /** E1: positionId → 最近一次自动减仓失败时间 (短 retry cooldown 用)。 */
    private final Map<Long, Instant> lastAutoExitFailureAt = new ConcurrentHashMap<>();

    /** E1: positionId → 连续失败次数, 超 autoExitFailureMaxRetries 后退化为长 cooldown。 */
    private final Map<Long, Integer> autoExitFailureStreak = new ConcurrentHashMap<>();

    /** E2: outcome → 累计次数, 不持久化, 供 controller 暴露给运维面板。 */
    private final Map<AutoExitOutcome, AtomicLong> autoExitOutcomeCounters = new EnumMap<>(AutoExitOutcome.class);

    /** E2: 累计空 bid 告警次数。 */
    private final AtomicLong emptyBidAlertCount = new AtomicLong();

    /** E2: 累计扫描轮次。 */
    private final AtomicLong scanRoundCount = new AtomicLong();

    /** 最近 N 条告警的内存 ring buffer; 由 {@link #recentAlerts()} 返回快照。 */
    private final Deque<AlertObservation> recentAlerts = new ArrayDeque<>();

    /**
     * 主扫描入口; 节奏由 {@code scanIntervalMs} 控制 (默认 30s)。
     * tier 间隔是每仓位的"自身刷新节奏"; 扫描节奏 ≤ 最小 tier 间隔 即可保证不漏触发。
     */
    @Scheduled(fixedDelayString = "${smart-wallet.position-price-monitor.scan-interval-ms:30000}")
    public void scan() {
        SmartWalletProperties.PositionPriceMonitor cfg = properties.getPositionPriceMonitor();
        if (cfg == null || !cfg.isEnabled()) {
            return;
        }
        scanRoundCount.incrementAndGet();
        Instant now = Instant.now();
        List<SmartWalletPositionEntity> positions = loadOpenPositions(cfg);
        if (positions.isEmpty()) {
            return;
        }
        int refreshed = 0;
        int alertsTriggered = 0;
        for (SmartWalletPositionEntity p : positions) {
            try {
                ScanOutcome outcome = processOne(p, cfg, now);
                if (outcome == ScanOutcome.REFRESHED) {
                    refreshed++;
                } else if (outcome == ScanOutcome.ALERT_TRIGGERED) {
                    refreshed++;
                    alertsTriggered++;
                }
            } catch (Exception e) {
                log.warn("{} eval_failed positionId={} err={}", LOG_PREFIX, p.getId(), e.toString());
            }
        }
        if (refreshed > 0 || alertsTriggered > 0) {
            log.debug("{} scan_done total={} refreshed={} alertsTriggered={}",
                    LOG_PREFIX, positions.size(), refreshed, alertsTriggered);
        }
    }

    /**
     * 单仓位评估流程。包级可见以便测试直接调用。
     *
     * @return 该仓位本轮的处理结果, 供调用方统计
     */
    ScanOutcome processOne(SmartWalletPositionEntity p,
                           SmartWalletProperties.PositionPriceMonitor cfg,
                           Instant now) {
        // C: tier 优先按实时 mark price 判断, 缺失或非正时退回 entry price。
        // 这样"entry 0.50 但现价 0.05"的仓位也会落到低价档,享受 2min 高频刷新节奏。
        int tierIntervalSec = resolveTierIntervalSeconds(p, cfg);
        Instant last = lastRefreshedAt.get(p.getId());
        if (last != null && Duration.between(last, now).getSeconds() < tierIntervalSec) {
            return ScanOutcome.SKIPPED_COOLDOWN;
        }

        String tokenId = p.getTokenId();
        if (tokenId == null || tokenId.isBlank()) {
            return ScanOutcome.SKIPPED_NO_TOKEN;
        }

        // 强制刷新: 失效缓存让 getOrderBook 走真实 fetch
        marketDataCacheService.invalidateOrderBook(tokenId);
        com.metaverse.coin.strategy.job.smartwallet.service.PolymarketApiClient.OrderBook book;
        try {
            book = marketDataCacheService.getOrderBook(tokenId);
        } catch (Exception e) {
            log.warn("{} fetch_failed positionId={} tokenId={} err={}",
                    LOG_PREFIX, p.getId(), tokenId, e.toString());
            // 不更新 lastRefreshedAt: 失败时希望下轮立刻重试, 而不是等满 tier 间隔
            return ScanOutcome.FETCH_FAILED;
        }
        lastRefreshedAt.put(p.getId(), now);

        boolean emptyBid = book == null
                || book.getBids() == null
                || book.getBids().isEmpty();
        BigDecimal bestBid = emptyBid ? null : book.getBids().get(0).getPrice();
        BigDecimal bestAsk = (book == null || book.getAsks() == null || book.getAsks().isEmpty())
                ? null : book.getAsks().get(0).getPrice();

        if (!emptyBid) {
            // 非空 bid: 清零连续空 bid 计数器
            consecutiveEmptyBidCount.remove(p.getId());
            return ScanOutcome.REFRESHED;
        }
        if (!cfg.isEmptyBidAlertEnabled()) {
            return ScanOutcome.REFRESHED;
        }

        // D2: 累计连续空 bid 次数, 不到阈值不触发, 防瞬时盘口抖动误报
        int streak = consecutiveEmptyBidCount.merge(p.getId(), 1, Integer::sum);
        if (streak < Math.max(1, cfg.getConsecutiveEmptyBidThreshold())) {
            log.debug("{} empty_bid_streak positionId={} count={} threshold={}",
                    LOG_PREFIX, p.getId(), streak, cfg.getConsecutiveEmptyBidThreshold());
            return ScanOutcome.REFRESHED;
        }

        // 告警 cooldown / E1 自动减仓重试 cooldown 在 maybeTriggerAutoExit 内部独立判定。
        Instant lastAlert = lastAlertedAt.get(p.getId());
        boolean alertCoolingDown = lastAlert != null
                && Duration.between(lastAlert, now).getSeconds() < cfg.getAlertCooldownSeconds();

        // A + B2: 即使 alert 仍在 cooldown, 也尝试评估自动减仓 (有自己的 cooldown / retry 逻辑),
        // 但不再 publish 重复告警。
        AutoExitOutcome autoExit = maybeTriggerAutoExit(p, cfg, now);

        if (alertCoolingDown) {
            return ScanOutcome.REFRESHED;
        }
        lastAlertedAt.put(p.getId(), now);
        emptyBidAlertCount.incrementAndGet();
        publishEmptyBidAlert(p, bestAsk, now, cfg, autoExit);
        return ScanOutcome.ALERT_TRIGGERED;
    }

    /**
     * 评估并 (在条件满足时) 触发自动减仓。返回结果用于告警附带 + 测试断言。
     *
     * <p>三条件 AND:
     * <ol>
     *   <li>{@code autoExitEnabled=true}</li>
     *   <li>{@code averageEntryPrice < autoExitMaxEntryPrice} (默认 0.05)</li>
     *   <li>{@code now - openedAt ≥ autoExitMinHoldingMinutes} (默认 60min)</li>
     * </ol>
     * 全部满足后再按 LIVE/PAPER mode 二级开关决定是否真下单, 默认 PAPER 只写日志, LIVE 关。</p>
     */
    AutoExitOutcome maybeTriggerAutoExit(SmartWalletPositionEntity p,
                                         SmartWalletProperties.PositionPriceMonitor cfg,
                                         Instant now) {
        AutoExitOutcome outcome = evaluateAndExecuteAutoExit(p, cfg, now);
        bumpOutcomeCounter(outcome);
        if (outcome == AutoExitOutcome.LIVE_ORDER_FAILED) {
            lastAutoExitFailureAt.put(p.getId(), now);
            autoExitFailureStreak.merge(p.getId(), 1, Integer::sum);
        } else if (outcome == AutoExitOutcome.LIVE_ORDER_PLACED || outcome == AutoExitOutcome.LIVE_ORDER_REPLACED
                || outcome == AutoExitOutcome.LIVE_ORDER_SKIPPED_MATCHING_ACTIVE) {
            // 成功路径清空失败 streak
            autoExitFailureStreak.remove(p.getId());
            lastAutoExitFailureAt.remove(p.getId());
        }
        return outcome;
    }

    private AutoExitOutcome evaluateAndExecuteAutoExit(SmartWalletPositionEntity p,
                                                       SmartWalletProperties.PositionPriceMonitor cfg,
                                                       Instant now) {
        if (!cfg.isAutoExitEnabled()) {
            return AutoExitOutcome.SKIP_DISABLED;
        }
        // D1: 触发口径与 tier 一致 — mark 优先, entry fallback
        BigDecimal reference = pickReferencePrice(p);
        if (reference == null || reference.compareTo(cfg.getAutoExitMaxReferencePrice()) >= 0) {
            return AutoExitOutcome.SKIP_HIGH_ENTRY;
        }
        Date openedAt = p.getOpenedAt();
        if (openedAt == null) {
            return AutoExitOutcome.SKIP_NO_OPENED_AT;
        }
        long heldMin = Duration.between(openedAt.toInstant(), now).toMinutes();
        if (heldMin < cfg.getAutoExitMinHoldingMinutes()) {
            return AutoExitOutcome.SKIP_INSUFFICIENT_HOLDING;
        }
        // mode 路由: executionAddress 含 "paper" → PAPER, 否则 LIVE
        boolean paperMode = p.getExecutionAddress() != null
                && p.getExecutionAddress().toLowerCase().contains("paper");
        if (paperMode) {
            if (!cfg.isAutoExitPaperEnabled()) {
                return AutoExitOutcome.SKIP_PAPER_DISABLED;
            }
            return runPaperAutoExit(p, cfg, reference, heldMin);
        }
        if (!cfg.isAutoExitLiveEnabled()) {
            return AutoExitOutcome.SKIP_LIVE_DISABLED;
        }
        if (clobExecutionService == null) {
            log.warn("{} AUTO_EXIT_SKIPPED_NO_EXECUTION_BEAN positionId={}", LOG_PREFIX, p.getId());
            return AutoExitOutcome.SKIP_NO_EXECUTION_BEAN;
        }
        // E1: LIVE 失败重试 cooldown (与告警 cooldown 分离, 默认更短)
        AutoExitOutcome retryCheck = checkRetryCooldown(p.getId(), cfg, now);
        if (retryCheck != null) {
            return retryCheck;
        }
        return runLiveAutoExit(p, cfg, reference, heldMin);
    }

    /**
     * E1: 检查 LIVE 自动减仓的失败重试 cooldown。
     * 连续失败 ≤ maxRetries 次: 用 autoExitFailureRetrySeconds (默认 120s) 短间隔;
     * 超过 maxRetries: 退化到 alertCooldownSeconds 长间隔, 防止持续打 API。
     */
    private AutoExitOutcome checkRetryCooldown(Long positionId,
                                               SmartWalletProperties.PositionPriceMonitor cfg,
                                               Instant now) {
        Instant lastFail = lastAutoExitFailureAt.get(positionId);
        if (lastFail == null) {
            return null;
        }
        int streak = autoExitFailureStreak.getOrDefault(positionId, 0);
        int waitSec = streak < cfg.getAutoExitFailureMaxRetries()
                ? cfg.getAutoExitFailureRetrySeconds()
                : cfg.getAlertCooldownSeconds();
        if (Duration.between(lastFail, now).getSeconds() < waitSec) {
            return AutoExitOutcome.SKIP_RETRY_COOLDOWN;
        }
        return null;
    }

    /** PAPER 自动减仓: 调 ShadowFillSimulationService 算影子成交价 + 滑点。 */
    private AutoExitOutcome runPaperAutoExit(SmartWalletPositionEntity p,
                                             SmartWalletProperties.PositionPriceMonitor cfg,
                                             BigDecimal reference, long heldMin) {
        BigDecimal qty = p.getQuantity() != null ? p.getQuantity() : BigDecimal.ZERO;
        BigDecimal floor = cfg.getAutoExitTickFloorPrice();
        BigDecimal stake = qty.multiply(floor);
        if (shadowFillSimulationService == null || marketSnapshotCollectorService == null
                || p.getMarketId() == null || p.getOutcome() == null) {
            log.warn("{} AUTO_EXIT_PAPER_LOG_ONLY positionId={} ref={} heldMin={} floor={} reason=shadow_services_unavailable",
                    LOG_PREFIX, p.getId(), reference, heldMin, floor);
            return AutoExitOutcome.PAPER_LOGGED;
        }
        SmartWalletMarketSnapshotEntity snapshot = marketSnapshotCollectorService
                .findFreshSnapshot(p.getMarketId(), p.getOutcome());
        if (snapshot == null) {
            log.warn("{} AUTO_EXIT_PAPER_NO_SNAPSHOT positionId={} ref={} heldMin={} floor={}",
                    LOG_PREFIX, p.getId(), reference, heldMin, floor);
            return AutoExitOutcome.PAPER_LOGGED;
        }
        ShadowFillSimulationService.ShadowFill fill =
                shadowFillSimulationService.simulate("SELL", stake.compareTo(BigDecimal.ZERO) > 0 ? stake : floor, snapshot);
        log.warn("{} AUTO_EXIT_PAPER_SHADOW positionId={} ref={} heldMin={} floor={} simEntry={} simSize={} simSlippageBps={} simStatus={}",
                LOG_PREFIX, p.getId(), reference, heldMin, floor,
                fill.getEntryPrice(), fill.getEntrySize(), fill.getSlippageBps(), fill.getStatus());
        return AutoExitOutcome.PAPER_SHADOW_FILLED;
    }

    /**
     * LIVE 自动减仓: B2 先查活跃 SELL 单, 一致则跳过, 不一致则 cancel 旧的再下新单。
     * 调用 B1 的 forcedPrice 通道, 真正在 tickFloor 价位挂单, 不被 lastTradePrice 覆盖。
     */
    private AutoExitOutcome runLiveAutoExit(SmartWalletPositionEntity p,
                                            SmartWalletProperties.PositionPriceMonitor cfg,
                                            BigDecimal reference, long heldMin) {
        BigDecimal floor = cfg.getAutoExitTickFloorPrice();
        BigDecimal currentQty = p.getQuantity() != null ? p.getQuantity() : BigDecimal.ZERO;

        // B2: 查活跃 SELL 单
        List<SmartWalletOrderEntity> activeSells = findActiveSellOrders(p.getId());
        if (!activeSells.isEmpty()) {
            if (!cfg.isAutoExitReplaceExistingOrder()) {
                log.info("{} AUTO_EXIT_LIVE_HAS_ACTIVE_SELL positionId={} activeCount={} replaceDisabled=true",
                        LOG_PREFIX, p.getId(), activeSells.size());
                return AutoExitOutcome.LIVE_ORDER_SKIPPED_MATCHING_ACTIVE;
            }
            // 比对: size 与 currentQty 一致, price 与 floor 一致 → 跳过下单 (scale 归一)
            boolean allMatching = activeSells.stream().allMatch(o ->
                    priceMatches(o.getSize(), currentQty) && priceMatches(o.getPrice(), floor));
            if (allMatching) {
                log.info("{} AUTO_EXIT_LIVE_MATCHING_ACTIVE positionId={} activeCount={} qty={} floor={}",
                        LOG_PREFIX, p.getId(), activeSells.size(), currentQty, floor);
                return AutoExitOutcome.LIVE_ORDER_SKIPPED_MATCHING_ACTIVE;
            }
            // 不一致 → best-effort cancel 所有活跃 SELL 后再决定: 收集每条 cancel 是否成功,
            // 失败 1 条不立刻 return, 继续尝试其它 → 防止"前 1 条 cancel 后中断, 中间留下其它孤儿单"
            int cancelAttempted = 0;
            int cancelFailed = 0;
            for (SmartWalletOrderEntity o : activeSells) {
                if (o.getExternalOrderId() == null || o.getExternalOrderId().isBlank()) {
                    continue;
                }
                cancelAttempted++;
                try {
                    clobExecutionService.cancelOrder(o.getExternalOrderId());
                    log.warn("{} AUTO_EXIT_LIVE_CANCELLED_OLD positionId={} cancelledOrderId={} oldSize={} oldPrice={}",
                            LOG_PREFIX, p.getId(), o.getExternalOrderId(), o.getSize(), o.getPrice());
                } catch (RuntimeException ex) {
                    cancelFailed++;
                    log.warn("{} AUTO_EXIT_LIVE_CANCEL_FAILED positionId={} orderId={} err={}",
                            LOG_PREFIX, p.getId(), o.getExternalOrderId(), ex.toString());
                    // 不立即 return, 继续 cancel 剩余
                }
            }
            if (cancelFailed > 0) {
                log.warn("{} AUTO_EXIT_LIVE_CANCEL_PARTIAL positionId={} attempted={} failed={} — aborting before placing new SELL",
                        LOG_PREFIX, p.getId(), cancelAttempted, cancelFailed);
                return AutoExitOutcome.LIVE_ORDER_FAILED;
            }
            return placeLiveAutoExit(p, cfg, reference, heldMin, floor, currentQty,
                    AutoExitOutcome.LIVE_ORDER_REPLACED);
        }
        return placeLiveAutoExit(p, cfg, reference, heldMin, floor, currentQty,
                AutoExitOutcome.LIVE_ORDER_PLACED);
    }

    /**
     * Scale-aware 等价比较: 两个 BigDecimal 数值相同就算 match, 不计较 scale。
     *
     * <p>{@link BigDecimal#compareTo} 本身就忽略 scale (0.001 ↔ 0.0010 视为相等), 这里抽出成
     * 命名清晰的 helper 防止后续 maintainer 误改成 {@link BigDecimal#equals} (后者会区分 scale,
     * 等价比较会失败)。任一为 null 返回 false。</p>
     */
    static boolean priceMatches(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return false;
        }
        return a.compareTo(b) == 0;
    }

    private AutoExitOutcome placeLiveAutoExit(SmartWalletPositionEntity p,
                                              SmartWalletProperties.PositionPriceMonitor cfg,
                                              BigDecimal reference, long heldMin,
                                              BigDecimal floor, BigDecimal qty,
                                              AutoExitOutcome successOutcome) {
        try {
            SmartWalletExecutionResult result = clobExecutionService.executeLiveExitAtForcedPrice(
                    p, floor, "EMPTY_BID_AUTO_EXIT");
            log.warn("{} AUTO_EXIT_LIVE positionId={} ref={} heldMin={} floor={} qty={} success={} extOrderId={} status={}",
                    LOG_PREFIX, p.getId(), reference, heldMin, floor, qty,
                    result.isSuccess(), result.getExternalOrderId(), result.getStatus());
            return result.isSuccess() ? successOutcome : AutoExitOutcome.LIVE_ORDER_FAILED;
        } catch (RuntimeException ex) {
            log.warn("{} AUTO_EXIT_LIVE_EXCEPTION positionId={} err={}",
                    LOG_PREFIX, p.getId(), ex.toString());
            return AutoExitOutcome.LIVE_ORDER_FAILED;
        }
    }

    /** 活跃 SELL 单状态集合 — 与 SmartWalletPositionSyncService 同口径。 */
    private static final List<String> ACTIVE_ORDER_STATUSES = List.of(
            "LIVE", "PARTIALLY_FILLED", "OPEN", "PENDING", "PLACED", "UNMATCHED");

    private List<SmartWalletOrderEntity> findActiveSellOrders(Long positionId) {
        if (orderMapper == null || positionId == null) {
            return List.of();
        }
        List<SmartWalletOrderEntity> rows = orderMapper.selectList(
                new LambdaQueryWrapper<SmartWalletOrderEntity>()
                        .eq(SmartWalletOrderEntity::getPositionId, positionId)
                        .eq(SmartWalletOrderEntity::getSide, "SELL")
                        .in(SmartWalletOrderEntity::getStatus, ACTIVE_ORDER_STATUSES));
        return rows == null ? List.of() : rows;
    }

    /** E2: 把 outcome 计数 +1。 */
    private void bumpOutcomeCounter(AutoExitOutcome outcome) {
        if (outcome == null) {
            return;
        }
        autoExitOutcomeCounters
                .computeIfAbsent(outcome, k -> new AtomicLong())
                .incrementAndGet();
    }

    /**
     * 把持仓的"参考价"映射到三档刷新间隔 (秒)。
     *
     * <p>C: 参考价优先取 {@code lastMarkPrice} (反映当前市场价位), 仅在 mark price 缺失 / 非正
     * 时才退回 {@code averageEntryPrice}。这样一个 entry=0.50 但当前 mark=0.05 的浮亏仓位也会
     * 进入低价档高频刷新,而不是被原 entry 价误判到高价档慢节奏。</p>
     *
     * <p>包级可见以便测试。</p>
     */
    int resolveTierIntervalSeconds(SmartWalletPositionEntity position,
                                   SmartWalletProperties.PositionPriceMonitor cfg) {
        BigDecimal price = pickReferencePrice(position);
        if (price == null) {
            return cfg.getHighTierIntervalSeconds();
        }
        if (price.compareTo(cfg.getTierLowPrice()) < 0) {
            return cfg.getLowTierIntervalSeconds();
        }
        if (price.compareTo(cfg.getTierMidPrice()) < 0) {
            return cfg.getMidTierIntervalSeconds();
        }
        return cfg.getHighTierIntervalSeconds();
    }

    /** 参考价: 优先 lastMarkPrice (>0), 否则 averageEntryPrice。 */
    private BigDecimal pickReferencePrice(SmartWalletPositionEntity position) {
        if (position == null) {
            return null;
        }
        BigDecimal mark = position.getLastMarkPrice();
        if (mark != null && mark.signum() > 0) {
            return mark;
        }
        return position.getAverageEntryPrice();
    }

    private void publishEmptyBidAlert(SmartWalletPositionEntity p, BigDecimal bestAsk, Instant now,
                                      SmartWalletProperties.PositionPriceMonitor cfg,
                                      AutoExitOutcome autoExit) {
        BigDecimal entry = p.getAverageEntryPrice();
        BigDecimal qty = p.getQuantity();
        String marketId = p.getMarketId();
        String outcome = p.getOutcome();
        String autoExitTag = autoExit == null ? "NONE" : autoExit.name();

        String title = String.format("空 bid 告警 marketId=%s outcome=%s autoExit=%s", marketId, outcome, autoExitTag);
        String message = String.format(
                "Position id=%s marketId=%s outcome=%s entry=%s quantity=%s bestAsk=%s autoExit=%s — best bid book "
                        + "empty, no buyer willing to take this position at any price; likely to settle at 0. "
                        + "autoExit field shows whether auto-exit was triggered (PAPER_LOGGED/LIVE_ORDER_PLACED) or "
                        + "skipped (with reason). Operator should still review for manual action.",
                p.getId(), marketId, outcome, entry, qty, bestAsk, autoExitTag);

        log.warn("{} EMPTY_BID_BOOK positionId={} marketId={} outcome={} entry={} quantity={} bestAsk={} autoExit={}",
                LOG_PREFIX, p.getId(), marketId, outcome, entry, qty, bestAsk, autoExitTag);

        if (auditAlertSink != null) {
            try {
                auditAlertSink.publish(AlertType.POSITION_EMPTY_BID_BOOK, title, message,
                        // metricValue 用 entry (反映"被卡住的浮亏基线"), threshold 用 0 (空盘口)
                        entry, BigDecimal.ZERO);
            } catch (RuntimeException ex) {
                log.warn("{} auditAlertSink_failed positionId={} err={}",
                        LOG_PREFIX, p.getId(), ex.toString());
            }
        }
        recordRecentAlert(p, bestAsk, now, cfg, autoExit);
    }

    private synchronized void recordRecentAlert(SmartWalletPositionEntity p, BigDecimal bestAsk, Instant now,
                                                SmartWalletProperties.PositionPriceMonitor cfg,
                                                AutoExitOutcome autoExit) {
        AlertObservation obs = AlertObservation.builder()
                .positionId(p.getId())
                .marketId(p.getMarketId())
                .outcome(p.getOutcome())
                .entryPrice(p.getAverageEntryPrice())
                .markPrice(p.getLastMarkPrice())
                .quantity(p.getQuantity())
                .bestAsk(bestAsk)
                .observedAt(now)
                .autoExitOutcome(autoExit == null ? null : autoExit.name())
                .build();
        recentAlerts.addLast(obs);
        int cap = Math.max(1, cfg.getRecentAlertsBufferSize());
        while (recentAlerts.size() > cap) {
            recentAlerts.pollFirst();
        }
    }

    /** 暴露最近告警快照, 供 dashboard / readiness 端点回看; 不保证顺序 (旧→新)。 */
    public synchronized List<AlertObservation> recentAlerts() {
        return new ArrayList<>(recentAlerts);
    }

    /**
     * E2: 暴露自动减仓 outcome 累计计数快照。键是 AutoExitOutcome, 值是累计次数。
     * 不持久化, 进程重启后从 0 计起。
     */
    public Map<AutoExitOutcome, Long> autoExitOutcomeSnapshot() {
        Map<AutoExitOutcome, Long> snap = new EnumMap<>(AutoExitOutcome.class);
        for (Map.Entry<AutoExitOutcome, AtomicLong> e : autoExitOutcomeCounters.entrySet()) {
            snap.put(e.getKey(), e.getValue().get());
        }
        return snap;
    }

    /** E2: 累计空 bid 告警次数 (since process start)。 */
    public long emptyBidAlertCount() {
        return emptyBidAlertCount.get();
    }

    /** E2: 累计 scan 轮次, 用于反推平均扫描频率。 */
    public long scanRoundCount() {
        return scanRoundCount.get();
    }

    /**
     * E1: 当前仍在重试 cooldown / 长 cooldown 里的仓位失败 streak 快照。
     * 键是 positionId, 值是连续失败次数。运维通过此能看到"有多少仓位卡在重试链里"。
     */
    public Map<Long, Integer> activeFailureStreaks() {
        return new java.util.LinkedHashMap<>(autoExitFailureStreak);
    }

    private List<SmartWalletPositionEntity> loadOpenPositions(SmartWalletProperties.PositionPriceMonitor cfg) {
        List<SmartWalletPositionEntity> raw = positionMapper.selectList(
                new LambdaQueryWrapper<SmartWalletPositionEntity>()
                        .in(SmartWalletPositionEntity::getStatus, "OPEN", "PARTIALLY_CLOSED")
                        .orderByAsc(SmartWalletPositionEntity::getOpenedAt)
                        .last("LIMIT " + Math.max(1, cfg.getMaxPositionsPerRound())));
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        marketInfoService.populatePositions(raw);
        return raw;
    }

    /** processOne 的执行结果分类, 供扫描循环计数 + 测试断言。 */
    enum ScanOutcome {
        REFRESHED,
        ALERT_TRIGGERED,
        SKIPPED_COOLDOWN,
        SKIPPED_NO_TOKEN,
        FETCH_FAILED
    }

    /** 自动减仓评估结果, 标记本轮 empty-bid 告警时是否触发了自动减仓 + 跳过原因。 */
    enum AutoExitOutcome {
        SKIP_DISABLED,
        /** D1: 参考价 (mark > entry fallback) 高于 autoExitMaxReferencePrice。 */
        SKIP_HIGH_ENTRY,
        SKIP_NO_OPENED_AT,
        SKIP_INSUFFICIENT_HOLDING,
        SKIP_PAPER_DISABLED,
        SKIP_LIVE_DISABLED,
        SKIP_NO_EXECUTION_BEAN,
        /** E1: LIVE 失败后短 retry cooldown 期内, 暂不重试。 */
        SKIP_RETRY_COOLDOWN,
        /** PAPER 模式: shadowFillSimulation 缺失时, 仅打日志。 */
        PAPER_LOGGED,
        /** E4: PAPER 模式: ShadowFillSimulationService 真模拟成交。 */
        PAPER_SHADOW_FILLED,
        /** LIVE 首次下单成功。 */
        LIVE_ORDER_PLACED,
        /** B2: LIVE 发现已有活跃 SELL 不一致, cancel 旧单后重下成功。 */
        LIVE_ORDER_REPLACED,
        /** B2: LIVE 已有活跃 SELL 与目标完全一致, 跳过下单。 */
        LIVE_ORDER_SKIPPED_MATCHING_ACTIVE,
        /** LIVE 下单 / cancel 失败。 */
        LIVE_ORDER_FAILED
    }

    /** 内存告警快照记录, 仅用于 dashboard / 测试; 不持久化。 */
    @Data
    @Builder
    public static class AlertObservation {
        private Long positionId;
        private String marketId;
        private String outcome;
        private BigDecimal entryPrice;
        private BigDecimal markPrice;
        private BigDecimal quantity;
        private BigDecimal bestAsk;
        private Instant observedAt;
        /** 自动减仓动作结果 (AutoExitOutcome 字符串名), 未触发评估则为 null。 */
        private String autoExitOutcome;
    }
}
