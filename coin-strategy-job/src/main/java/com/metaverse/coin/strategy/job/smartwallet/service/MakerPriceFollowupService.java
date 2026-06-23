package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.PendingOrderTimeoutTrackerMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.PendingOrderTimeoutTrackerEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketSnapshotEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletMarketInfoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * F-P1-15 第五轮：MAKER 重报循环 (maker-price-followup repost loop)。
 *
 * <p><b>背景</b>：MAKER 单挂着不成交时，若盘口 bestBid 朝远离限价方向移动，
 * 单子会被甩开而长时间不成交（最差等到 GTC 60s 超时被撤）。本服务在 GTC 超时之前
 * 周期性检查 bestBid 与挂单 limitPrice 的偏差，超过 {@code makerPriceFollowupDriftBps}
 * 时主动 cancel + 用最新 bestBid 重报，最大重报次数受 {@code makerPriceFollowupMaxRetries} 限制。
 *
 * <p><b>对开单效率 / 资金使用率 / 是否会开单 / 盈亏点 / 胜率</b>：
 * <ul>
 *   <li>开单效率: ⬆️ — 减少 MAKER 单等到 GTC 超时被无脑撤掉的概率；</li>
 *   <li>资金使用率: ⬆️ — 重报后更贴近盘口，资金锁定时间缩短；</li>
 *   <li>是否会开单: ⬆️ — 提升 MAKER 路径成交率，否则容易回退为 SKIP；</li>
 *   <li>盈亏点: 🟡 中性偏正 — bestBid 移动后重报，避免追入更不利价；</li>
 *   <li>胜率: ⚪ 不变 — 不改变信号 / 风控；只影响订单管理层。</li>
 * </ul>
 *
 * <p><b>开关</b>：{@code smart-wallet.execution-wallet.enable-maker-price-followup}（默认 OFF），
 * test 灰度稳定后再 prod。和 {@link GtcOrderTimeoutTracker} 协同：本服务在 expire 前抢先 cancel，
 * 失败时回退到 GtcOrderTimeoutTracker 的 expire scan 兜底。
 *
 * <p><b>状态</b>：repostAttempts 维护在内存 {@code attemptsByOrderId} 中（按 externalOrderId 索引），
 * 进程重启即清零（重启后新一轮 repost 从 0 开始计数；不在 DB 持久化避免再拉一个表）。
 *
 * <p><b>不与 GtcOrderTimeoutTracker 重复 cancel</b>：本服务 cancel 成功后立刻
 * {@code gtcOrderTimeoutTracker.unregister(oldExternalOrderId, "MAKER_PRICE_FOLLOWUP_REPOST")}；
 * 重报成功后 {@code gtcOrderTimeoutTracker.register(...)} 注册新订单（保留 GTC 兜底）。
 *
 * <p>详见 docs/polymarket/minimal-implementations-fix-plan.md §三 F-P1-15。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MakerPriceFollowupService {

    private static final String LOG_PREFIX = "[MakerPriceFollowup]";

    private final SmartWalletProperties properties;
    private final ExecutionWalletContextLoader walletContextLoader;
    private final PolymarketClobClientRouter clobClientRouter;
    private final PolymarketOrderRequestFactory orderRequestFactory;
    private final MarketSnapshotCollectorService marketSnapshotCollectorService;
    private final SmartWalletMarketInfoService marketInfoService;

    @Autowired(required = false)
    private PendingOrderTimeoutTrackerMapper trackerMapper;
    @Autowired(required = false)
    private SmartWalletOrderMapper orderMapper;
    @Autowired(required = false)
    private GtcOrderTimeoutTracker gtcOrderTimeoutTracker;
    @Autowired(required = false)
    private MakerOnlyOrderTimeoutTracker makerOnlyOrderTimeoutTracker;

    /**
     * externalOrderId → 已尝试 repost 次数。进程内存维护，重启清零。
     * 当 repost 成功后，新 externalOrderId 继承旧条目的 attempts + 1。
     */
    private final Map<String, Integer> attemptsByOrderId = new ConcurrentHashMap<>();

    @Scheduled(fixedDelayString = "${smart-wallet.execution-wallet.maker-price-followup-interval-ms:3000}")
    public void scan() {
        SmartWalletProperties.ExecutionWallet ew = ew();
        if (ew == null || !ew.isEnableMakerPriceFollowup()) return;
        if (trackerMapper == null || orderMapper == null) return;
        try {
            List<PendingOrderTimeoutTrackerEntity> pending = trackerMapper.selectList(
                    new LambdaQueryWrapper<PendingOrderTimeoutTrackerEntity>()
                            .eq(PendingOrderTimeoutTrackerEntity::getCancelStatus, "PENDING")
                            .likeRight(PendingOrderTimeoutTrackerEntity::getOrderStrategy, "MAKER"));
            if (pending.isEmpty()) return;
            int driftBps = ew.getMakerPriceFollowupDriftBps();
            int maxRetries = ew.getMakerPriceFollowupMaxRetries();
            long snapshotMaxAge = ew.getMakerPriceFollowupSnapshotMaxAgeMs();
            int triggered = 0;
            int skipped = 0;
            for (PendingOrderTimeoutTrackerEntity entry : pending) {
                if (entry == null || entry.getExternalOrderId() == null) { skipped++; continue; }
                String externalOrderId = entry.getExternalOrderId();
                int attempts = attemptsByOrderId.getOrDefault(externalOrderId, 0);
                if (attempts >= maxRetries) { skipped++; continue; }
                // 还在容忍期内不要太频繁地查 snapshot — 由 scheduler 间隔本身控制（默认 3s）
                FollowupOutcome outcome = attemptRepostForEntry(entry, driftBps, snapshotMaxAge);
                if (outcome.triggered) triggered++; else skipped++;
            }
            if (triggered > 0) {
                log.info("{} action=scan-done triggered={} skipped={} totalPending={}",
                        LOG_PREFIX, triggered, skipped, pending.size());
            }
        } catch (RuntimeException ex) {
            log.warn("{} action=scan failed err={}", LOG_PREFIX, ex.toString());
        }
    }

    /**
     * 单条 tracker entry 的 repost 评估 + 执行。
     */
    private FollowupOutcome attemptRepostForEntry(PendingOrderTimeoutTrackerEntity entry,
                                                  int driftBps,
                                                  long snapshotMaxAge) {
        String externalOrderId = entry.getExternalOrderId();
        try {
            // 1. 查到当前订单的 limitPrice / size / tokenId 等
            SmartWalletOrderEntity order = orderMapper.selectOne(
                    new LambdaQueryWrapper<SmartWalletOrderEntity>()
                            .eq(SmartWalletOrderEntity::getExternalOrderId, externalOrderId)
                            .last("LIMIT 1"));
            if (order == null || order.getPrice() == null || order.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
                return FollowupOutcome.skipped("order missing or price=0");
            }
            // 填充 market_info（含 tokenId / question）—— mapper.selectOne 不自动 join
            try {
                marketInfoService.populate(order);
            } catch (RuntimeException ignore) {
                // populate 失败不阻断；后续 prepareLocalDraft 内部会再走 metadata 兜底
            }
            // 已成交 / 已取消的不重报（status 类型很多，简化为：只有 PENDING/PLACED/OPEN 才考虑）
            String status = order.getStatus() == null ? "" : order.getStatus().toUpperCase(Locale.ROOT);
            if (!isOpenOrderStatus(status)) {
                return FollowupOutcome.skipped("order status=" + status);
            }
            // 2. 拉最新 bestBid
            BigDecimal bestBid = fetchBestBid(entry.getMarketId(), entry.getOutcome(), snapshotMaxAge);
            if (bestBid == null) {
                return FollowupOutcome.skipped("bestBid unavailable / stale");
            }
            // 3. 计算 drift bps —— |bestBid - limitPrice| / limitPrice × 10000
            BigDecimal limitPrice = order.getPrice();
            BigDecimal diff = bestBid.subtract(limitPrice).abs();
            BigDecimal driftBpsActual = diff.multiply(new BigDecimal("10000"))
                    .divide(limitPrice, 4, RoundingMode.HALF_UP);
            if (driftBpsActual.compareTo(new BigDecimal(driftBps)) < 0) {
                return FollowupOutcome.skipped("drift " + driftBpsActual + "bps < threshold " + driftBps);
            }
            // 4. 触发 cancel + repost
            return executeRepost(entry, order, bestBid, driftBpsActual);
        } catch (RuntimeException ex) {
            log.warn("{} action=attemptRepost-failed externalOrderId={} err={}",
                    LOG_PREFIX, externalOrderId, ex.toString());
            return FollowupOutcome.skipped("exception: " + ex.getMessage());
        }
    }

    /**
     * 执行 cancel + 重新 prepareLocalDraft + placeOrder + 更新 tracker。
     */
    private FollowupOutcome executeRepost(PendingOrderTimeoutTrackerEntity entry,
                                          SmartWalletOrderEntity order,
                                          BigDecimal bestBid,
                                          BigDecimal driftBpsActual) {
        String oldExternalOrderId = entry.getExternalOrderId();
        ExecutionWalletContextLoader.ExecutionWalletContext wallet =
                walletContextLoader.requireLiveContext();
        // 1. cancel old
        try {
            clobClientRouter.cancelOrder(wallet, oldExternalOrderId);
        } catch (RuntimeException ex) {
            // cancel 失败可能是订单已经撮合 / 已被外部撤掉，不算 repost 失败，记一次 attempt 然后跳过
            attemptsByOrderId.merge(oldExternalOrderId, 1, Integer::sum);
            log.warn("{} action=cancel-before-repost-failed externalOrderId={} err={}",
                    LOG_PREFIX, oldExternalOrderId, ex.toString());
            return FollowupOutcome.skipped("cancel failed: " + ex.getMessage());
        }
        // 通知 tracker：旧条目作废
        try {
            if (gtcOrderTimeoutTracker != null) {
                gtcOrderTimeoutTracker.unregister(oldExternalOrderId, "MAKER_PRICE_FOLLOWUP_REPOST");
            }
            if (makerOnlyOrderTimeoutTracker != null) {
                // MakerOnlyOrderTimeoutTracker.unregister 是单参（无 reason），与 GTC tracker 不同
                makerOnlyOrderTimeoutTracker.unregister(oldExternalOrderId);
            }
        } catch (RuntimeException ignore) {
            // tracker 通知失败不阻塞 repost 主流程；下一轮 scan 自然纠正
        }

        // 2. 计算新限价 —— 复用 PolymarketOrderRequestFactory.prepareLocalDraft 的逻辑
        //    （此处直接传 rawPrice = bestBid，让 factory 内部走 best-bid pricing 路径得到 maker-safe 价）
        // marketId / outcome 优先取 order（populated 后从 marketInfo 来），fallback 到 entry（tracker 直接存）
        String outcome = order.getOutcome() != null ? order.getOutcome() : entry.getOutcome();
        String marketId = order.getMarketId() != null ? order.getMarketId() : entry.getMarketId();
        String question = order.getQuestion();
        String tokenId = order.getTokenId();
        BigDecimal stake = order.getNotionalUsdc();
        PolymarketOrderRequestFactory.PreparedPolymarketOrder reprepared;
        // 强制 MAKER 路径：原单的 strategy 若不是 MAKER_ONLY / MAKER_FIRST 则透传 MAKER_FIRST，
        // 让 factory 内的 override 把 effectiveStrategy 拉回 MAKER，避免重报变 TAKER 越线吃单。
        String priceImpactMode = entry.getOrderStrategy();
        if (priceImpactMode == null || !(priceImpactMode.equalsIgnoreCase("MAKER_ONLY")
                || priceImpactMode.equalsIgnoreCase("MAKER_FIRST"))) {
            priceImpactMode = "MAKER_FIRST";
        }
        try {
            reprepared = orderRequestFactory.prepareLocalDraft(
                    "repost-" + oldExternalOrderId,
                    marketId,
                    question,
                    outcome,
                    stake,
                    bestBid,
                    tokenId,
                    null,  // tickSize 由 factory 通过 metadata 兜底解析
                    false, // negRisk 由 factory 通过 metadata 解析；此处保守传 false（不影响下单结构）
                    wallet,
                    priceImpactMode);
        } catch (RuntimeException ex) {
            log.warn("{} action=prepare-repost-failed externalOrderId={} err={}",
                    LOG_PREFIX, oldExternalOrderId, ex.toString());
            attemptsByOrderId.merge(oldExternalOrderId, 1, Integer::sum);
            return FollowupOutcome.skipped("prepare failed: " + ex.getMessage());
        }

        // 3. place 新单
        Map<String, Object> placeResp;
        try {
            placeResp = clobClientRouter.placeOrder(wallet, reprepared);
        } catch (RuntimeException ex) {
            log.warn("{} action=place-repost-failed externalOrderId={} err={}",
                    LOG_PREFIX, oldExternalOrderId, ex.toString());
            attemptsByOrderId.merge(oldExternalOrderId, 1, Integer::sum);
            return FollowupOutcome.skipped("place failed: " + ex.getMessage());
        }
        String newExternalOrderId = stringify(firstNonNull(placeResp.get("orderID"),
                placeResp.get("orderId"), placeResp.get("id")));
        if (newExternalOrderId == null) {
            log.warn("{} action=repost-no-order-id externalOrderId={} resp={}",
                    LOG_PREFIX, oldExternalOrderId, placeResp);
            attemptsByOrderId.merge(oldExternalOrderId, 1, Integer::sum);
            return FollowupOutcome.skipped("no orderId in repost response");
        }

        // 4. 注册新订单到 GTC tracker（保留 60s 兜底超时）；继承 attempts + 1
        int prevAttempts = attemptsByOrderId.getOrDefault(oldExternalOrderId, 0);
        attemptsByOrderId.remove(oldExternalOrderId);
        attemptsByOrderId.put(newExternalOrderId, prevAttempts + 1);
        try {
            if (gtcOrderTimeoutTracker != null) {
                gtcOrderTimeoutTracker.register(
                        order.getId(),
                        newExternalOrderId,
                        wallet.getAddress(),
                        reprepared.getOrderStrategy(),
                        marketId,
                        outcome,
                        order.getFollowTaskId());
            }
        } catch (RuntimeException ignore) {
            // tracker 注册失败仅记录，主路径已成功
        }
        log.info("{} action=repost-success oldOrderId={} newOrderId={} marketId={} outcome={} oldPrice={} newLimitPrice={} bestBid={} driftBps={} attempts={}",
                LOG_PREFIX, oldExternalOrderId, newExternalOrderId, marketId, outcome,
                order.getPrice(), reprepared.getLimitPrice(), bestBid, driftBpsActual, prevAttempts + 1);
        return FollowupOutcome.triggered("reposted " + oldExternalOrderId + " → " + newExternalOrderId);
    }

    private boolean isOpenOrderStatus(String status) {
        if (status == null || status.isEmpty()) return true; // 状态未知保守判定为 open
        return status.contains("PENDING") || status.contains("PLACED")
                || status.contains("OPEN") || status.contains("LIVE")
                || status.contains("ACCEPTED");
    }

    private BigDecimal fetchBestBid(String marketId, String outcome, long maxAgeMs) {
        if (marketId == null || outcome == null) return null;
        try {
            SmartWalletMarketSnapshotEntity snapshot = marketSnapshotCollectorService
                    .findLatestSnapshotAtOrBefore(marketId, outcome, new Date());
            if (snapshot == null || snapshot.getSnapshotAt() == null) return null;
            if (System.currentTimeMillis() - snapshot.getSnapshotAt().getTime() > maxAgeMs) return null;
            return snapshot.getBestBid();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private SmartWalletProperties.ExecutionWallet ew() {
        return properties == null ? null : properties.getExecutionWallet();
    }

    private static Object firstNonNull(Object... vs) {
        for (Object v : vs) if (v != null) return v;
        return null;
    }

    private static String stringify(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** 仅用于测试观察 / 单元测试 — 当前 in-memory 重报计数表大小。 */
    public int trackedRepostCount() {
        return attemptsByOrderId.size();
    }

    private static final class FollowupOutcome {
        final boolean triggered;
        final String detail;
        private FollowupOutcome(boolean triggered, String detail) {
            this.triggered = triggered;
            this.detail = detail;
        }
        static FollowupOutcome triggered(String detail) { return new FollowupOutcome(true, detail); }
        static FollowupOutcome skipped(String detail) { return new FollowupOutcome(false, detail); }
    }

}
